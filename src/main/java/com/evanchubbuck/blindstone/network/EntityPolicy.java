package com.evanchubbuck.blindstone.network;

import io.netty.buffer.Unpooled;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.EntityPositionSource;
import org.jspecify.annotations.Nullable;

/** Vanilla 26.3 field semantics, independent of entity simulation and shared packet mutation. */
public final class EntityPolicy {
    private EntityPolicy() { }

    private record Field(EntityDataSerializer<?> serializer, String owner, String name) { }
    private static final Set<EntityDataSerializer<?>> VANILLA_SERIALIZERS = vanillaSerializers();
    private static final ClassValue<Map<Integer, Field>> SCHEMAS = new ClassValue<>() {
        @Override protected Map<Integer, Field> computeValue(Class<?> type) {
            Map<Integer, Field> result = new HashMap<>();
            for (Class<?> owner = type; owner != null && Entity.class.isAssignableFrom(owner); owner = owner.getSuperclass()) {
                if (!owner.getName().startsWith("net.minecraft.world.entity."))
                    throw new IllegalStateException("Unsupported non-vanilla entity metadata schema");
                for (var declaration : owner.getDeclaredFields()) {
                    if (!Modifier.isStatic(declaration.getModifiers()) || declaration.getType() != EntityDataAccessor.class) continue;
                    try {
                        declaration.setAccessible(true);
                        var accessor = (EntityDataAccessor<?>) declaration.get(null);
                        if (!VANILLA_SERIALIZERS.contains(accessor.serializer())) throw new IllegalStateException("Unsupported metadata serializer");
                        var old = result.putIfAbsent(accessor.id(), new Field(accessor.serializer(), owner.getSimpleName(), declaration.getName()));
                        if (old != null && old.serializer() != accessor.serializer()) throw new IllegalStateException("Conflicting metadata schema");
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException("Cannot validate vanilla entity metadata schema", failure);
                    }
                }
            }
            return Map.copyOf(result);
        }
    };

    /** Audited getAddEntityPacket overrides: Projectile/FishingHook, FallingBlock, painting/frame, Warden, knot. */
    public static @Nullable ClientboundAddEntityPacket spawn(ClientboundAddEntityPacket packet, PacketFilter.Context context) {
        Entity source = context.level().getEntity(packet.getId());
        if (source == null || source.getType() != packet.getType() || !source.getUUID().equals(packet.getUUID())
                || !context.maySpawnEntity(packet.getId())) return null;
        if (context.isPlayerEntity(packet.getId())) {
            context.entitySpawned(packet.getId());
            return packet;
        }
        requireVanilla(source);
        if (!authorized(context, BlockPos.containing(packet.getX(), packet.getY(), packet.getZ()))) return null;
        int data;
        if (source instanceof Projectile projectile) {
            Entity owner = projectile.getOwner();
            data = owner != null && context.isEntityVisible(owner.getId()) ? owner.getId() : 0;
        } else if (source instanceof FallingBlockEntity falling) data = Block.getId(falling.getBlockState());
        else if (source instanceof ItemFrame frame) data = frame.getDirection().get3DDataValue();
        else if (source instanceof Painting painting) data = painting.getDirection().get3DDataValue();
        else if (source instanceof Warden warden) data = warden.hasPose(Pose.EMERGING) ? 1 : 0;
        else data = 0; // Entity's default and LeashFenceKnotEntity contain no spawn-data reference.
        var safe = data == packet.getData() ? packet : new ClientboundAddEntityPacket(packet.getId(), packet.getUUID(),
                packet.getX(), packet.getY(), packet.getZ(), packet.getXRot(), packet.getYRot(), packet.getType(),
                data, packet.getMovement(), packet.getYHeadRot());
        context.entitySpawned(packet.getId());
        return safe;
    }

    /** Validate actual vanilla field ids/serializers; keep ordinary item, variant, name, health, vehicle and pose data. */
    public static @Nullable ClientboundSetEntityDataPacket metadata(ClientboundSetEntityDataPacket packet, PacketFilter.Context context) {
        if (!context.isEntityVisible(packet.id())) return null;
        if (context.isPlayerEntity(packet.id())) return packet;
        Entity source = context.level().getEntity(packet.id());
        if (source == null) return null;
        requireVanilla(source);
        var schema = SCHEMAS.get(source.getClass());
        List<SynchedEntityData.DataValue<?>> safe = new ArrayList<>();
        for (var value : packet.packedItems()) {
            var field = schema.get(value.id());
            if (field == null || field.serializer() != value.serializer())
                throw new IllegalStateException("Unsupported vanilla entity metadata field or serializer");
            safe.add(sanitize(value, field, source, context));
        }
        return new ClientboundSetEntityDataPacket(packet.id(), List.copyOf(safe));
    }

    private static SynchedEntityData.DataValue<?> sanitize(SynchedEntityData.DataValue<?> value, Field field,
            Entity source, PacketFilter.Context context) {
        var serializer = value.serializer();
        Object original = value.value();
        Object safe = original;
        if (serializer == EntityDataSerializers.INT) {
            boolean directId = field.owner().equals("Guardian") && field.name().equals("DATA_ID_ATTACK_TARGET")
                    || field.owner().equals("WitherBoss") && Set.of("DATA_TARGET_A", "DATA_TARGET_B", "DATA_TARGET_C").contains(field.name());
            boolean offsetId = field.owner().equals("FishingHook") && field.name().equals("DATA_HOOKED_ENTITY");
            int id = (Integer) original;
            if ((directId || offsetId) && id != 0 && !context.isEntityVisible(offsetId ? id - 1 : id)) safe = 0;
        } else if (serializer == EntityDataSerializers.OPTIONAL_UNSIGNED_INT) {
            if (!(field.owner().equals("Frog") && field.name().equals("DATA_TONGUE_TARGET_ID"))
                    && !(field.owner().equals("FireworkRocketEntity") && field.name().equals("DATA_ATTACHED_TO_TARGET")))
                throw new IllegalStateException("Unsupported optional entity-id metadata field");
            OptionalInt id = (OptionalInt) original;
            if (id.isPresent() && !context.isEntityVisible(id.getAsInt())) safe = OptionalInt.empty();
        } else if (serializer == EntityDataSerializers.BLOCK_POS) {
            if (!authorized(context, (BlockPos) original)) safe = source.blockPosition().immutable();
        } else if (serializer == EntityDataSerializers.OPTIONAL_BLOCK_POS) {
            Optional<?> position = (Optional<?>) original;
            if (position.isPresent() && !authorized(context, (BlockPos) position.get())) safe = Optional.empty();
        } else if (serializer == EntityDataSerializers.OPTIONAL_GLOBAL_POS) {
            Optional<?> position = (Optional<?>) original;
            if (position.isPresent()) {
                GlobalPos global = (GlobalPos) position.get();
                if (!global.dimension().equals(context.level().dimension()) || !authorized(context, global.pos())) safe = Optional.empty();
            }
        } else if (serializer == EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE) {
            Optional<?> reference = (Optional<?>) original;
            if (reference.isPresent()) {
                var entity = context.level().getEntity(((EntityReference<?>) reference.get()).getUUID());
                if (entity == null || !context.isEntityVisible(entity.getId())) safe = Optional.empty();
            }
        } else if (serializer == EntityDataSerializers.PARTICLE) {
            var particle = particle((ParticleOptions) original, context);
            safe = particle == null ? ParticleTypes.SMOKE : particle;
        } else if (serializer == EntityDataSerializers.PARTICLES) {
            List<ParticleOptions> particles = new ArrayList<>();
            for (Object option : (List<?>) original) {
                var particle = particle((ParticleOptions) option, context);
                if (particle != null) particles.add(particle);
            }
            safe = List.copyOf(particles);
        }
        return replace(value, safe);
    }

    /** All vanilla option schemas; only vibration/trail carry extra positions or identifying references. */
    public static @Nullable ParticleOptions particle(ParticleOptions option, PacketFilter.Context context) {
        if (!BuiltInRegistries.PARTICLE_TYPE.getKey(option.getType()).toString().startsWith("minecraft:")) return null;
        if (option instanceof VibrationParticleOption vibration) {
            var destination = vibration.getDestination();
            if (destination instanceof BlockPositionSource block)
                return block.getPosition(context.level()).filter(pos -> authorized(context, BlockPos.containing(pos))).isPresent() ? option : null;
            if (destination instanceof EntityPositionSource entity) {
                var buffer = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    // Encoding reads the reference id without mutating the source's lazy resolution cache.
                    EntityPositionSource.STREAM_CODEC.encode(buffer, entity);
                    int id = buffer.readVarInt();
                    float offset = buffer.readFloat();
                    Entity target = context.level().getEntity(id);
                    return target != null && context.isEntityVisible(id)
                            && authorized(context, BlockPos.containing(target.position().add(0, offset, 0))) ? option : null;
                } finally { buffer.release(); }
            }
            return null;
        }
        if (option instanceof TrailParticleOption trail) return authorized(context, BlockPos.containing(trail.target())) ? option : null;
        return option instanceof SimpleParticleType || option instanceof BlockParticleOption || option instanceof ItemParticleOption
                || option instanceof DustParticleOptions || option instanceof DustColorTransitionOptions || option instanceof ColorParticleOption
                || option instanceof ShriekParticleOption || option instanceof SculkChargeParticleOptions || option instanceof PowerParticleOption
                || option instanceof SpellParticleOption || option instanceof GeyserParticleOptions || option instanceof GeyserBaseParticleOptions ? option : null;
    }

    private static void requireVanilla(Entity source) {
        if (!source.getClass().getName().startsWith("net.minecraft.world.entity.")
                || !BuiltInRegistries.ENTITY_TYPE.getKey(source.getType()).toString().startsWith("minecraft:"))
            throw new IllegalStateException("Unsupported custom entity schema");
    }

    @SuppressWarnings("unchecked")
    private static <T> SynchedEntityData.DataValue<T> replace(SynchedEntityData.DataValue<T> value, Object replacement) {
        return new SynchedEntityData.DataValue<>(value.id(), value.serializer(), (T) replacement);
    }

    private static boolean authorized(PacketFilter.Context context, BlockPos pos) {
        return !context.policy().protects(context.level(), pos) || context.isAuthorized(pos);
    }

    private static Set<EntityDataSerializer<?>> vanillaSerializers() {
        Set<EntityDataSerializer<?>> result = new HashSet<>();
        for (var field : EntityDataSerializers.class.getDeclaredFields()) {
            if (!Modifier.isPublic(field.getModifiers()) || !Modifier.isStatic(field.getModifiers())
                    || field.getType() != EntityDataSerializer.class) continue;
            try { result.add((EntityDataSerializer<?>) field.get(null)); }
            catch (IllegalAccessException failure) { throw new ExceptionInInitializerError(failure); }
        }
        return Set.copyOf(result);
    }
}
