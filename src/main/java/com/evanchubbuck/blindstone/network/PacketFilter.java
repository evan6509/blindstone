package com.evanchubbuck.blindstone.network;

import com.evanchubbuck.blindstone.ChunkFilter;
import com.evanchubbuck.blindstone.ConcealmentPolicy;
import com.evanchubbuck.blindstone.SectionVisibility;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Vanilla 26.3 packet boundary. No input packet, shared chunk, or input light array is mutated.
 * This is an experimental, fail-closed filter: light is deliberately dark at EVERY section,
 * including unprotected and neighboring boundary sections. This prevents copied hidden light
 * from escaping at the cost of incorrect lighting. Production admission must remain disabled
 * until filtered-world light reconstruction and client gameplay validation exist.
 */
public final class PacketFilter {
    private PacketFilter() { }

    public interface Context {
        ServerLevel level();
        ConcealmentPolicy policy();
        boolean isAuthorized(BlockPos pos);
        /** Must require both authorization and a spawn delivered to this viewer. */
        boolean isEntityVisible(int entityId);
        Map<Integer, SectionVisibility> masks(ChunkPos chunk);
        default Vec3 viewerPosition() { return Vec3.ZERO; }
        /** Must reject ids absent from the authoritative server level, even if supplied by a packet. */
        default boolean maySpawnEntity(int entityId) { return isEntityVisible(entityId); }
        default boolean isPlayerEntity(int entityId) { return false; }
        default boolean isEntityKnown(int entityId) { return isEntityVisible(entityId); }
        default void entitySpawned(int entityId) { }
        default void entityRemoved(int entityId) { }
    }

    /* Explicit 26.3 class identities. Unknown/custom packets are dropped, never implicitly allowed. */
    private static final Set<Class<?>> PASSTHROUGH = Set.of(
            ClientboundBlockChangedAckPacket.class, ClientboundAwardStatsPacket.class,
            ClientboundChunksBiomesPacket.class, ClientboundChunkBatchFinishedPacket.class,
            ClientboundChunkBatchStartPacket.class, ClientboundChangeDifficultyPacket.class,
            ClientboundCommandSuggestionsPacket.class, ClientboundContainerClosePacket.class,
            ClientboundClearTitlesPacket.class, ClientboundContainerSetContentPacket.class,
            ClientboundContainerSetSlotPacket.class, ClientboundBossEventPacket.class,
            ClientboundCooldownPacket.class, ClientboundDeleteChatPacket.class,
            ClientboundDisguisedChatPacket.class, ClientboundCustomChatCompletionsPacket.class,
            ClientboundContainerSetDataPacket.class, ClientboundForgetLevelChunkPacket.class,
            ClientboundGameEventPacket.class, ClientboundCommandsPacket.class,
            ClientboundGameRuleValuesPacket.class, ClientboundInitializeBorderPacket.class,
            ClientboundLowDiskSpaceWarningPacket.class, ClientboundMerchantOffersPacket.class,
            ClientboundMapItemDataPacket.class, ClientboundOpenScreenPacket.class,
            ClientboundMoveVehiclePacket.class, ClientboundOpenBookPacket.class,
            ClientboundLoginPacket.class, ClientboundPlaceGhostRecipePacket.class,
            ClientboundPlayerAbilitiesPacket.class, ClientboundPlayerCombatEnterPacket.class,
            ClientboundPlayerInfoRemovePacket.class, ClientboundPlayerRotationPacket.class,
            ClientboundPlayerPositionPacket.class, ClientboundPlayerCombatEndPacket.class,
            ClientboundPlayerChatPacket.class, ClientboundPlayerInfoUpdatePacket.class,
            ClientboundRecipeBookAddPacket.class, ClientboundRespawnPacket.class,
            ClientboundSelectAdvancementsTabPacket.class, ClientboundRecipeBookSettingsPacket.class,
            ClientboundServerDataPacket.class, ClientboundRecipeBookRemovePacket.class,
            ClientboundSetActionBarTextPacket.class, ClientboundSetBorderLerpSizePacket.class,
            ClientboundSetBorderSizePacket.class, ClientboundSetBorderWarningDistancePacket.class,
            ClientboundSetBorderCenterPacket.class, ClientboundSetDisplayObjectivePacket.class,
            ClientboundResetScorePacket.class, ClientboundSetChunkCacheCenterPacket.class,
            ClientboundSetDefaultSpawnPositionPacket.class, ClientboundSetExperiencePacket.class,
            ClientboundSetPlayerInventoryPacket.class, ClientboundSetCursorItemPacket.class,
            ClientboundSetScorePacket.class, ClientboundSetSimulationDistancePacket.class,
            ClientboundSetHeldSlotPacket.class, ClientboundSetHealthPacket.class,
            ClientboundSetChunkCacheRadiusPacket.class, ClientboundSetSubtitleTextPacket.class,
            ClientboundSetTimePacket.class, ClientboundSetTitlesAnimationPacket.class,
            ClientboundSetPlayerTeamPacket.class, ClientboundSetObjectivePacket.class,
            ClientboundSystemChatPacket.class, ClientboundSetTitleTextPacket.class,
            ClientboundTabListPacket.class, ClientboundSetBorderWarningDelayPacket.class,
            ClientboundTickingStepPacket.class, ClientboundStopSoundPacket.class,
            ClientboundSoundPacket.class, ClientboundUpdateRecipesPacket.class,
            ClientboundStartConfigurationPacket.class, ClientboundUpdateAdvancementsPacket.class,
            ClientboundTickingStatePacket.class,
            net.minecraft.network.protocol.common.ClientboundKeepAlivePacket.class,
            net.minecraft.network.protocol.common.ClientboundPingPacket.class,
            net.minecraft.network.protocol.common.ClientboundDisconnectPacket.class,
            net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket.class);

    /* These vanilla serializers cannot encode block positions, particle destinations, or entity ids.
       INT/LONG/OPTIONAL_UNSIGNED_INT are deliberately absent: semantics depend on entity+field. */
    private static final Set<EntityDataSerializer<?>> SAFE_VISIBLE_METADATA = Set.of(
            EntityDataSerializers.BYTE, EntityDataSerializers.FLOAT, EntityDataSerializers.STRING,
            EntityDataSerializers.BOOLEAN, EntityDataSerializers.ROTATIONS, EntityDataSerializers.DIRECTION,
            EntityDataSerializers.POSE, EntityDataSerializers.VILLAGER_DATA,
            EntityDataSerializers.CAT_VARIANT, EntityDataSerializers.CAT_SOUND_VARIANT,
            EntityDataSerializers.CHICKEN_VARIANT, EntityDataSerializers.CHICKEN_SOUND_VARIANT,
            EntityDataSerializers.COW_VARIANT, EntityDataSerializers.COW_SOUND_VARIANT,
            EntityDataSerializers.WOLF_VARIANT, EntityDataSerializers.WOLF_SOUND_VARIANT,
            EntityDataSerializers.FROG_VARIANT, EntityDataSerializers.PIG_VARIANT,
            EntityDataSerializers.PIG_SOUND_VARIANT, EntityDataSerializers.ZOMBIE_NAUTILUS_VARIANT,
            EntityDataSerializers.PAINTING_VARIANT, EntityDataSerializers.ARMADILLO_STATE,
            EntityDataSerializers.SNIFFER_STATE, EntityDataSerializers.WEATHERING_COPPER_STATE,
            EntityDataSerializers.COPPER_GOLEM_STATE, EntityDataSerializers.HUMANOID_ARM,
            EntityDataSerializers.DYE_COLOR);

    /** Returns a safe replacement, the unchanged safe packet, or null to suppress. */
    public static @Nullable Packet<?> filter(Packet<?> packet, Context context) {
        if (!context.level().getServer().isSameThread()) {
            throw new IllegalStateException("Outgoing filtering requires the owning server thread");
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            List<Packet<? super ClientGamePacketListener>> safe = new ArrayList<>();
            for (var child : bundle.subPackets()) addGamePacket(safe, filter(child, context));
            return safe.isEmpty() ? null : new ClientboundBundlePacket(safe);
        }
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            LevelChunk source = context.level().getChunkSource().getChunkNow(chunk.x(), chunk.z());
            if (source == null) throw new IllegalStateException("Cannot safely rebuild an unavailable chunk");
            return chunk(source, context);
        }
        if (packet instanceof ClientboundLightUpdatePacket light) {
            return new ClientboundLightUpdatePacket(light.x(), light.z(), darkLight(context.level()));
        }
        if (packet instanceof ClientboundBlockUpdatePacket update) {
            return new ClientboundBlockUpdatePacket(update.getPos().immutable(), authorized(context, update.getPos())
                    ? update.getBlockState() : context.policy().replacement(update.getPos().getY()));
        }
        if (packet instanceof ClientboundSectionBlocksUpdatePacket update) return section(update, context);
        if (packet instanceof ClientboundBlockEntityDataPacket update) return authorized(context, update.getPos()) ? packet : null;
        if (packet instanceof ClientboundBlockEventPacket event) return authorized(context, event.getPos()) ? packet : null;
        if (packet instanceof ClientboundAddTransientBlockPacket event) return authorized(context, event.getPos()) ? packet : null;
        if (packet instanceof ClientboundBlockDestructionPacket event) {
            return authorized(context, event.getPos()) && context.isEntityVisible(event.getId()) ? packet : null;
        }
        if (packet instanceof ClientboundLevelEventPacket event) return authorized(context, event.getPos()) ? packet : null;
        if (packet instanceof ClientboundOpenSignEditorPacket sign) return authorized(context, sign.pos()) ? packet : null;
        if (packet instanceof ClientboundLevelParticlesPacket particles) {
            // Compound options can carry a hidden destination/entity (e.g. vibration/trail), not just an origin.
            return particles.particle() instanceof SimpleParticleType
                    && authorized(context, BlockPos.containing(particles.x(), particles.y(), particles.z())) ? packet : null;
        }
        if (packet instanceof ClientboundExplodePacket explosion) return explosion(explosion, context);
        if (packet instanceof ClientboundSoundEntityPacket sound) {
            if (context.isEntityVisible(sound.getId())) return packet;
            Entity entity = context.level().getEntity(sound.getId());
            return entity == null ? null : new ClientboundSoundPacket(sound.getSound(), sound.getSource(),
                    entity.getX(), entity.getY(), entity.getZ(), sound.getVolume(), sound.getPitch(), sound.getSeed());
        }
        if (packet instanceof ClientboundAddEntityPacket spawn) {
            Entity source = context.level().getEntity(spawn.getId());
            if (source == null || source.getType() != spawn.getType() || !source.getUUID().equals(spawn.getUUID())
                    || !context.maySpawnEntity(spawn.getId())) return null;
            if (!context.isPlayerEntity(spawn.getId())
                    && !authorized(context, BlockPos.containing(spawn.getX(), spawn.getY(), spawn.getZ()))) return null;
            context.entitySpawned(spawn.getId());
            if (context.isPlayerEntity(spawn.getId())) return packet;
            // Some vanilla spawn data fields encode an owner entity id. Neutralizing all data is safe
            // but breaks item-frame direction, falling-block appearance, etc.; re-pair on reveal remains
            // experimental until each type's spawn-data semantics has a dedicated adapter.
            return new ClientboundAddEntityPacket(spawn.getId(), spawn.getUUID(), spawn.getX(), spawn.getY(), spawn.getZ(),
                    spawn.getXRot(), spawn.getYRot(), spawn.getType(), 0, spawn.getMovement(), spawn.getYHeadRot());
        }
        if (packet instanceof ClientboundSetEntityDataPacket metadata) {
            if (!context.isEntityVisible(metadata.id())) return null;
            if (context.isPlayerEntity(metadata.id())) return packet;
            var values = metadata.packedItems().stream()
                    .filter(value -> SAFE_VISIBLE_METADATA.contains(value.serializer())).toList();
            return values.isEmpty() ? null : new ClientboundSetEntityDataPacket(metadata.id(), values);
        }
        if (packet instanceof ClientboundRemoveEntitiesPacket removals) {
            var known = new IntArrayList();
            for (int id : removals.entityIds()) {
                if (context.isEntityKnown(id)) { known.add(id); context.entityRemoved(id); }
            }
            return known.isEmpty() ? null : new ClientboundRemoveEntitiesPacket(known);
        }
        if (packet instanceof ClientboundTeleportEntityPacket movement) {
            if (!context.isEntityVisible(movement.id())) return null;
            if (context.isPlayerEntity(movement.id())) return packet;
            // Relative coordinates need the viewer's exact prior base, which this prototype does
            // not track. Never interpret a relative vector as a world position.
            if (movement.relatives().contains(Relative.X) || movement.relatives().contains(Relative.Y)
                    || movement.relatives().contains(Relative.Z)) return null;
            return authorized(context, BlockPos.containing(movement.change().position())) ? packet : null;
        }
        if (packet instanceof ClientboundEntityPositionSyncPacket movement) {
            if (!context.isEntityVisible(movement.id())) return null;
            if (!context.isPlayerEntity(movement.id())) {
                if (!authorized(context, BlockPos.containing(movement.position().endPosition()))) return null;
                if (movement.position() instanceof PositionPath.Stepped path) {
                    for (var step : path.steps()) if (!authorized(context, BlockPos.containing(step.position()))) return null;
                }
            }
            return packet;
        }
        if (packet instanceof ClientboundMoveMinecartPacket movement) {
            if (!context.isEntityVisible(movement.entityId())) return null;
            for (var step : movement.lerpSteps()) if (!authorized(context, BlockPos.containing(step.position()))) return null;
            return packet;
        }
        if (packet instanceof ClientboundSetPassengersPacket passengers) {
            if (!context.isEntityVisible(passengers.getVehicle())) return null;
            for (int id : passengers.getPassengers()) if (!context.isEntityVisible(id)) return null;
            return packet;
        }
        if (packet instanceof ClientboundSetEntityLinkPacket link) {
            return context.isEntityVisible(link.getSourceId())
                    && (link.getDestId() == 0 || context.isEntityVisible(link.getDestId())) ? packet : null;
        }
        if (packet instanceof ClientboundTakeItemEntityPacket pickup) {
            return context.isEntityVisible(pickup.getItemId()) && context.isEntityVisible(pickup.getPlayerId()) ? packet : null;
        }
        if (packet instanceof ClientboundDamageEventPacket damage) {
            if (!context.isEntityVisible(damage.entityId())) return null;
            int cause = context.isEntityVisible(damage.sourceCauseId()) ? damage.sourceCauseId() : -1;
            int direct = context.isEntityVisible(damage.sourceDirectId()) ? damage.sourceDirectId() : -1;
            return new ClientboundDamageEventPacket(damage.entityId(), damage.sourceType(), cause, direct,
                    damage.sourcePosition().filter(pos -> authorized(context, BlockPos.containing(pos))));
        }
        int entityId = entityId(packet, context.level());
        if (entityId != Integer.MIN_VALUE) return context.isEntityVisible(entityId) ? packet : null;
        // Debug, tag-query, waypoint, player-look-at, and custom payload paths intentionally fail closed.
        if (PASSTHROUGH.contains(packet.getClass())) return packet;
        if (packet instanceof ClientboundDebugBlockValuePacket || packet instanceof ClientboundDebugChunkValuePacket
                || packet instanceof ClientboundDebugEntityValuePacket || packet instanceof ClientboundDebugEventPacket
                || packet instanceof ClientboundDebugSamplePacket || packet instanceof ClientboundTagQueryPacket
                || packet instanceof ClientboundTrackedWaypointPacket || packet instanceof ClientboundPlayerLookAtPacket
                || packet instanceof ClientboundGameTestHighlightPosPacket || packet instanceof ClientboundTestInstanceBlockStatus
                ) return null;
        throw new IllegalStateException("Unaudited outgoing packet: " + packet.getClass().getName());
    }

    public static ClientboundLevelChunkWithLightPacket chunk(LevelChunk source, Context context) {
        return new ClientboundLevelChunkWithLightPacket(source.getPos().x(), source.getPos().z(),
                ChunkFilter.snapshot(source, context.policy(), context.masks(source.getPos())), darkLight(context.level()));
    }

    /** Independent masks for every vanilla light section, including the extra sections at both ends. */
    public static ClientboundLightUpdatePacketData darkLight(ServerLevel level) {
        int count = level.getLightEngine().getLightSectionCount();
        BitSet emptySky = new BitSet(count);
        emptySky.set(0, count);
        return new ClientboundLightUpdatePacketData(new BitSet(), new BitSet(), emptySky,
                (BitSet) emptySky.clone(), List.of(), List.of());
    }

    private static boolean authorized(Context context, BlockPos pos) {
        return !context.policy().protects(pos.getY()) || context.isAuthorized(pos);
    }

    private static Packet<?> section(ClientboundSectionBlocksUpdatePacket input, Context context) {
        var changes = new ShortOpenHashSet();
        var output = new LevelChunkSection(context.level().palettedContainerFactory());
        SectionPos[] section = new SectionPos[1];
        input.runUpdates((pos, state) -> {
            SectionPos current = SectionPos.of(pos);
            if (section[0] != null && !section[0].equals(current)) throw new IllegalStateException("Mixed section update");
            section[0] = current;
            changes.add(SectionPos.sectionRelativePos(pos));
            output.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15,
                    authorized(context, pos) ? state : context.policy().replacement(pos.getY()));
        });
        return section[0] == null ? null : new ClientboundSectionBlocksUpdatePacket(section[0], changes, output);
    }

    private static Packet<?> explosion(ClientboundExplodePacket source, Context context) {
        boolean visible = authorized(context, BlockPos.containing(source.center()));
        var safe = new ClientboundExplodePacket(visible ? source.center() : context.viewerPosition(), 0, 0,
                source.playerKnockback(), ParticleTypes.EXPLOSION, source.explosionSound(), WeightedList.of(),
                visible && source.playSound());
        if (visible || !source.playSound()) return safe;
        var sound = new ClientboundSoundPacket(source.explosionSound(), SoundSource.BLOCKS,
                source.center().x(), source.center().y(), source.center().z(), 4.0F, 0.7F, 0);
        return new ClientboundBundlePacket(List.of(safe, sound));
    }

    private static int entityId(Packet<?> packet, ServerLevel level) {
        if (packet instanceof ClientboundPlayerCombatKillPacket p) return p.playerId();
        if (packet instanceof ClientboundAnimatePacket p) return p.getId();
        if (packet instanceof ClientboundHurtAnimationPacket p) return p.id();
        if (packet instanceof ClientboundProjectilePowerPacket p) return p.getId();
        if (packet instanceof ClientboundRemoveMobEffectPacket p) return p.entityId();
        if (packet instanceof ClientboundSetEquipmentPacket p) return p.getEntity();
        if (packet instanceof ClientboundSetEntityMotionPacket p) return p.id();
        if (packet instanceof ClientboundUpdateAttributesPacket p) return p.getEntityId();
        if (packet instanceof ClientboundUpdateMobEffectPacket p) return p.getEntityId();
        if (packet instanceof ClientboundTeleportEntityPacket p) return p.id();
        if (packet instanceof ClientboundMoveMinecartPacket p) return p.entityId();
        if (packet instanceof ClientboundEntityPositionSyncPacket p) return p.id();
        if (packet instanceof ClientboundMountScreenOpenPacket p) return p.getEntityId();
        if (packet instanceof ClientboundSwingAnimationPacket p) return p.entityId();
        Entity entity = null;
        if (packet instanceof ClientboundMoveEntityPacket p) entity = p.getEntity(level);
        else if (packet instanceof ClientboundEntityEventPacket p) entity = p.getEntity(level);
        else if (packet instanceof ClientboundRotateHeadPacket p) entity = p.getEntity(level);
        else if (packet instanceof ClientboundSetCameraPacket p) entity = p.getEntity(level);
        else return Integer.MIN_VALUE;
        return entity == null ? -1 : entity.getId();
    }

    @SuppressWarnings("unchecked")
    private static void addGamePacket(List<Packet<? super ClientGamePacketListener>> output, @Nullable Packet<?> packet) {
        if (packet == null) return;
        // No common/login packets are nested in a vanilla game bundle.
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (var child : bundle.subPackets()) output.add(child);
        } else output.add((Packet<? super ClientGamePacketListener>) packet);
    }
}
