package com.evanchubbuck.blindstone;

import com.evanchubbuck.blindstone.network.PacketFilter;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.phys.Vec3;

/** Decoded vanilla packet fixtures. These tests do not establish real-client behavior. */
public final class PacketFilterGameTests {
    @GameTest
    public void fullChunkCodecContainsNoHiddenRoomOrBlockEntity(GameTestHelper helper) {
        var level = helper.getLevel();
        var real = new LevelChunk(level, new ChunkPos(0, 0));
        var pos = new BlockPos(2, 20, 3);
        real.getSection(real.getSectionIndex(20)).setBlockState(2, 4, 3, Blocks.CHEST.defaultBlockState());
        var chest = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
        chest.setLevel(level);
        real.getBlockEntities().put(pos, chest);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buffer, PacketFilter.chunk(real, context(level, Set.of())));
            var decoded = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buffer);
            helper.assertTrue(!buffer.isReadable(), "Full chunk codec must consume the payload");
            List<BlockPos> entities = new ArrayList<>();
            decoded.chunkData().forEachBlockEntityTag(0, 0, (p, type, tag) -> entities.add(p.immutable()));
            helper.assertTrue(entities.isEmpty(), "Full packet must omit hidden block entity entries");
            var sections = decoded.chunkData().getReadBuffer();
            try {
                for (int i = 0; i < level.getSectionsCount(); i++) {
                    var section = new LevelChunkSection(level.palettedContainerFactory());
                    section.read(sections);
                    if (i == level.getSectionIndex(20)) {
                        helper.assertTrue(section.getBlockState(2, 4, 3).is(Blocks.STONE), "Hidden chest must decode as stone");
                        helper.assertTrue(section.getBlockState(3, 4, 3).is(Blocks.STONE), "Hidden air must decode as stone");
                    }
                }
            } finally { sections.release(); }
            assertDark(helper, decoded.lightData());
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest
    public void fullChunkViewersNeverMutateSharedChunkOrPacket(GameTestHelper helper) {
        var level = helper.getLevel();
        var source = level.getChunkAt(helper.absolutePos(BlockPos.ZERO));
        var original = new ClientboundLevelChunkWithLightPacket(source, level.getLightEngine(), null, null);
        byte[] before = encode(level, original);
        var hidden = (ClientboundLevelChunkWithLightPacket) PacketFilter.filter(original, context(level, Set.of()));
        helper.assertTrue(hidden != original && hidden.chunkData() != original.chunkData(), "Each output must be isolated");
        helper.assertTrue(Arrays.equals(before, encode(level, original)), "Input packet must remain byte-identical");
        var second = (ClientboundLevelChunkWithLightPacket) PacketFilter.filter(original, context(level, Set.of()));
        helper.assertTrue(Arrays.equals(encode(level, hidden), encode(level, second)), "Second viewer stays concealed");
        helper.assertTrue(Arrays.equals(before, encode(level,
                new ClientboundLevelChunkWithLightPacket(source.getPos().x(), source.getPos().z(),
                        new ClientboundLevelChunkPacketData(source), original.lightData()))), "World must remain unchanged");
        helper.succeed();
    }

    @GameTest
    public void singleUpdatesPreserveFakeTerrainAndExactAuthorization(GameTestHelper helper) {
        var hidden = new BlockPos(-14, -12, -13);
        var visible = hidden.east();
        var context = context(helper.getLevel(), Set.of(visible));
        var original = new ClientboundBlockUpdatePacket(hidden, Blocks.DIAMOND_ORE.defaultBlockState());
        var filtered = (ClientboundBlockUpdatePacket) PacketFilter.filter(original, context);
        helper.assertTrue(filtered.getBlockState().is(Blocks.DEEPSLATE), "Negative hidden position uses replacement");
        helper.assertTrue(original.getBlockState().is(Blocks.DIAMOND_ORE), "Shared original is unchanged");
        var allowed = (ClientboundBlockUpdatePacket) PacketFilter.filter(
                new ClientboundBlockUpdatePacket(visible, Blocks.AIR.defaultBlockState()), context);
        helper.assertTrue(allowed.getBlockState().isAir(), "Exact authorized air is preserved");
        helper.succeed();
    }

    @GameTest
    public void sectionUpdatesPreserveOnlyAuthorizedRealStates(GameTestHelper helper) {
        var first = new BlockPos(1, 20, 3);
        var second = first.east();
        var section = new LevelChunkSection(helper.getLevel().palettedContainerFactory());
        section.setBlockState(1, 4, 3, Blocks.CHEST.defaultBlockState());
        section.setBlockState(2, 4, 3, Blocks.DIAMOND_ORE.defaultBlockState());
        var changes = new ShortOpenHashSet();
        changes.add(SectionPos.sectionRelativePos(first));
        changes.add(SectionPos.sectionRelativePos(second));
        var source = new ClientboundSectionBlocksUpdatePacket(SectionPos.of(first), changes, section);
        var safe = (ClientboundSectionBlocksUpdatePacket) PacketFilter.filter(source, context(helper.getLevel(), Set.of(first)));
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> states = new HashMap<>();
        safe.runUpdates((pos, state) -> states.put(pos.immutable(), state));
        helper.assertTrue(states.size() == 2 && states.get(first).is(Blocks.CHEST)
                && states.get(second).is(Blocks.STONE), "Multi update follows exact per-block mask");
        helper.succeed();
    }

    @GameTest
    public void laterBlockEntityAndRemoteEventsAreSuppressed(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = new BlockPos(2, 20, 3);
        var chest = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
        chest.setLevel(level);
        var be = ClientboundBlockEntityDataPacket.create(chest);
        helper.assertTrue(PacketFilter.filter(be, context(level, Set.of())) == null, "Hidden BE update must be dropped");
        helper.assertTrue(PacketFilter.filter(be, context(level, Set.of(pos))) == be, "Visible BE may follow authorized block");
        helper.assertTrue(PacketFilter.filter(new ClientboundBlockEventPacket(pos, Blocks.CHEST, 1, 1), context(level, Set.of())) == null,
                "Hidden block event must be dropped");
        helper.assertTrue(PacketFilter.filter(new ClientboundLevelEventPacket(2001, pos, 0, false), context(level, Set.of())) == null,
                "Hidden positional world event must be dropped");
        helper.assertTrue(PacketFilter.filter(new ClientboundAddTransientBlockPacket(pos, Blocks.DIAMOND_BLOCK.defaultBlockState()),
                context(level, Set.of())) == null, "Hidden transient block must be dropped");
        helper.succeed();
    }

    @GameTest
    public void lightUpdatesClearEverySectionAndNeverMutateSource(GameTestHelper helper) {
        var level = helper.getLevel();
        var mask = new BitSet();
        mask.set(0, level.getLightEngine().getLightSectionCount());
        byte[] sourceBytes = new byte[2048];
        Arrays.fill(sourceBytes, (byte) 0xff);
        var updates = new ArrayList<byte[]>();
        for (int i = 0; i < mask.cardinality(); i++) updates.add(sourceBytes.clone());
        var source = new ClientboundLightUpdatePacketData(mask, (BitSet) mask.clone(), new BitSet(), new BitSet(), updates, updates);
        for (int x : new int[] {-1, 0, 1}) {
            var safe = (ClientboundLightUpdatePacket) PacketFilter.filter(new ClientboundLightUpdatePacket(x, -1, source), context(level, Set.of()));
            assertDark(helper, safe.lightData());
        }
        helper.assertTrue(mask.cardinality() == level.getLightEngine().getLightSectionCount()
                && source.skyUpdates().getFirst()[0] == (byte) 0xff, "Shared bright source remains unchanged");
        helper.succeed();
    }

    @GameTest
    public void lightMasksAreIndependentAcrossViewers(GameTestHelper helper) {
        var a = PacketFilter.darkLight(helper.getLevel());
        var b = PacketFilter.darkLight(helper.getLevel());
        a.emptySkyYMask().clear();
        helper.assertTrue(!b.emptySkyYMask().isEmpty() && !a.emptyBlockYMask().isEmpty(), "No shared mutable masks");
        helper.succeed();
    }

    @GameTest
    public void bundlesCannotBypassBlockOrEntityFiltering(GameTestHelper helper) {
        var pos = new BlockPos(1, 20, 3);
        var source = new ClientboundBundlePacket(List.of(
                new ClientboundBundlePacket(List.of(new ClientboundBlockUpdatePacket(pos, Blocks.CHEST.defaultBlockState()))),
                new ClientboundSetEntityMotionPacket(77, new Vec3(1, 2, 3))));
        var result = (ClientboundBundlePacket) PacketFilter.filter(source, context(helper.getLevel(), Set.of()));
        List<Packet<?>> packets = new ArrayList<>();
        result.subPackets().forEach(packets::add);
        helper.assertTrue(packets.size() == 1 && packets.getFirst() instanceof ClientboundBlockUpdatePacket block
                && block.getBlockState().is(Blocks.STONE), "Nested sensitive packets must be recursively filtered");
        helper.succeed();
    }

    @GameTest
    public void particlesCannotLeakHiddenDestinations(GameTestHelper helper) {
        var pos = new BlockPos(1, 20, 3);
        var context = context(helper.getLevel(), Set.of(pos));
        var vibration = new ClientboundLevelParticlesPacket(new VibrationParticleOption(new BlockPositionSource(pos.east(10)), 20),
                false, false, 1, 20, 3, 0, 0, 0, 0, 1);
        helper.assertTrue(PacketFilter.filter(vibration, context) == null, "Visible origin must not authorize hidden vibration destination");
        var simple = new ClientboundLevelParticlesPacket(ParticleTypes.FLAME, false, false, 1, 20, 3, 0, 0, 0, 0, 1);
        helper.assertTrue(PacketFilter.filter(simple, context) == simple, "Simple particle at visible origin may pass");
        helper.assertTrue(PacketFilter.filter(simple, context(helper.getLevel(), Set.of())) == null, "Hidden particle source drops");
        helper.succeed();
    }

    @GameTest
    public void explosionsPreserveKnockbackWithoutCompoundHiddenPayload(GameTestHelper helper) {
        var knockback = new Vec3(1, 2, 3);
        var source = new ClientboundExplodePacket(new Vec3(2, 20, 3), 12, 876, Optional.of(knockback),
                ParticleTypes.EXPLOSION, SoundEvents.GENERIC_EXPLODE, WeightedList.of(), false);
        var safe = (ClientboundExplodePacket) PacketFilter.filter(source, context(helper.getLevel(), Set.of()));
        helper.assertTrue(safe.playerKnockback().orElseThrow().equals(knockback), "Recipient knockback must survive");
        helper.assertTrue(safe.center().equals(Vec3.ZERO) && safe.radius() == 0 && safe.blockCount() == 0
                && safe.blockParticles().isEmpty() && !safe.playSound(), "Hidden explosion compound data must be sanitized");
        helper.succeed();
    }

    @GameTest
    public void hiddenPickupAndUnauditedMetadataDoNotCreateDanglingReferences(GameTestHelper helper) {
        var context = context(helper.getLevel(), Set.of());
        helper.assertTrue(PacketFilter.filter(new ClientboundTakeItemEntityPacket(100, 101, 1), context) == null,
                "Pickup referencing hidden entities must drop");
        helper.assertTrue(PacketFilter.filter(new ClientboundSetEntityDataPacket(100, List.of()), context) == null,
                "Unaudited metadata cannot bypass entity policy");
        helper.succeed();
    }

    @GameTest
    public void unauditedCustomPayloadFailsClosed(GameTestHelper helper) {
        boolean failed = false;
        try { PacketFilter.filter(new ClientboundCustomPayloadPacket(new BrandPayload("fixture")), context(helper.getLevel(), Set.of())); }
        catch (IllegalStateException expected) { failed = true; }
        helper.assertTrue(failed, "Unsupported custom path must fail closed for runtime disconnect");
        helper.succeed();
    }

    @GameTest
    public void spawnBeforeMetadataAndHiddenRemovalReferencesAreIsolated(GameTestHelper helper) {
        var entity = helper.spawn(EntityTypes.COW, new BlockPos(1, 2, 1));
        int id = entity.getId();
        int hiddenId = id + 1000000;
        var context = new TestContext(helper.getLevel(), Set.of(entity.blockPosition()));
        context.spawnable.add(id);
        var spawn = new ClientboundAddEntityPacket(id, entity.getUUID(), entity.getX(), entity.getY(), entity.getZ(), 0, 0,
                EntityTypes.COW, 918, Vec3.ZERO, 0);
        var source = new ClientboundBundlePacket(List.of(spawn,
                new ClientboundSetEntityMotionPacket(id, new Vec3(1, 2, 3)),
                new ClientboundSetEntityMotionPacket(hiddenId, new Vec3(4, 5, 6))));
        var safe = (ClientboundBundlePacket) PacketFilter.filter(source, context);
        List<Packet<?>> packets = new ArrayList<>();
        safe.subPackets().forEach(packets::add);
        helper.assertTrue(packets.size() == 2 && packets.getFirst() instanceof ClientboundAddEntityPacket filtered
                && filtered.getData() == 0, "Spawn must precede only authorized references and neutralize embedded owner data");
        helper.assertTrue(spawn.getData() == 918, "Original spawn data is unchanged");
        context.players.add(id);
        var metadata = new ClientboundSetEntityDataPacket(id, List.of());
        helper.assertTrue(PacketFilter.filter(metadata, context) == metadata, "Player metadata remains normal");
        var remove = (ClientboundRemoveEntitiesPacket) PacketFilter.filter(new ClientboundRemoveEntitiesPacket(id, hiddenId), context);
        helper.assertTrue(remove.entityIds().size() == 1 && remove.entityIds().getInt(0) == id && context.known.isEmpty(),
                "Removal omits unknown IDs and clears sent tracking");
        helper.succeed();
    }

    @GameTest
    public void unknownSpawnCannotCreateAnEntityEvenIfContextWasOverpermissive(GameTestHelper helper) {
        var context = new TestContext(helper.getLevel(), Set.of(new BlockPos(1, 20, 3)));
        int id = Integer.MAX_VALUE;
        context.spawnable.add(id);
        var forged = new ClientboundAddEntityPacket(id, UUID.randomUUID(), 1, 20, 3, 0, 0,
                EntityTypes.COW, 0, Vec3.ZERO, 0);
        helper.assertTrue(PacketFilter.filter(forged, context) == null && context.known.isEmpty(),
                "Packet-supplied IDs must exist in the authoritative level before they can spawn");
        helper.succeed();
    }

    @GameTest
    public void visibleMetadataRetainsSafeFieldsAndRemovesEmbeddedReferences(GameTestHelper helper) {
        var context = new TestContext(helper.getLevel(), Set.of());
        context.known.add(7);
        context.spawnable.add(7);
        var flag = new SynchedEntityData.DataValue<>(0, EntityDataSerializers.BYTE, (byte) 1);
        var health = new SynchedEntityData.DataValue<>(1, EntityDataSerializers.FLOAT, 17.0F);
        var entityReference = new SynchedEntityData.DataValue<>(2, EntityDataSerializers.INT, 100);
        var hiddenPos = new SynchedEntityData.DataValue<>(3, EntityDataSerializers.BLOCK_POS, new BlockPos(2, 20, 3));
        var source = new ClientboundSetEntityDataPacket(7, List.of(flag, health, entityReference, hiddenPos));
        var safe = (ClientboundSetEntityDataPacket) PacketFilter.filter(source, context);
        helper.assertTrue(safe.packedItems().equals(List.of(flag, health)),
                "Visible entity may receive flags/health without hidden integer references or block positions");
        helper.assertTrue(source.packedItems().size() == 4, "Shared metadata packet remains unchanged");
        helper.succeed();
    }

    @GameTest
    public void damagePacketsStripConcealedCausesAndSourcePositions(GameTestHelper helper) {
        var context = new TestContext(helper.getLevel(), Set.of());
        context.known.add(7);
        context.spawnable.add(7);
        var source = new ClientboundDamageEventPacket(7, helper.getLevel().damageSources().generic().typeHolder(),
                8, 9, Optional.of(new Vec3(2, 20, 3)));
        var safe = (ClientboundDamageEventPacket) PacketFilter.filter(source, context);
        helper.assertTrue(safe.entityId() == 7 && safe.sourceCauseId() == -1 && safe.sourceDirectId() == -1
                && safe.sourcePosition().isEmpty(), "Recipient damage survives without hidden references");
        helper.succeed();
    }

    @GameTest
    public void hiddenEntitySoundsBecomeOrdinaryPositionalSounds(GameTestHelper helper) {
        var entity = helper.spawn(EntityTypes.COW, new BlockPos(1, 2, 1));
        var source = new ClientboundSoundEntityPacket(SoundEvents.GENERIC_EXPLODE, SoundSource.NEUTRAL,
                entity, 1.0F, 0.75F, 123);
        var filtered = PacketFilter.filter(source, context(helper.getLevel(), Set.of()));
        helper.assertTrue(filtered instanceof ClientboundSoundPacket, "Hidden entity sound must become positional");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            ClientboundSoundPacket.STREAM_CODEC.encode(buffer, (ClientboundSoundPacket) filtered);
            helper.assertTrue(SoundEvent.STREAM_CODEC.decode(buffer).equals(source.getSound())
                    && buffer.readEnum(SoundSource.class) == source.getSource(), "Sound type and source must survive");
            // Vanilla stores integer eighth-block coordinates. Its getX/getY/getZ divide using
            // float, which loses sub-block precision at the GameTest runner's distant locations.
            // Inspect encoded integers instead of imposing a false precision requirement on vanilla.
            helper.assertTrue(buffer.readInt() == (int) (entity.getX() * 8.0)
                    && buffer.readInt() == (int) (entity.getY() * 8.0)
                    && buffer.readInt() == (int) (entity.getZ() * 8.0), "Position must use exact vanilla sound quantization");
            helper.assertTrue(buffer.readFloat() == 1.0F && buffer.readFloat() == 0.75F
                    && buffer.readLong() == 123 && !buffer.isReadable(),
                    "Volume, pitch and seed survive without an extra entity reference");
        } finally { buffer.release(); }
        helper.succeed();
    }

    private static PacketFilter.Context context(ServerLevel level, Set<BlockPos> visible) {
        return new TestContext(level, visible);
    }

    private static final class TestContext implements PacketFilter.Context {
        private final ServerLevel level;
        private final Set<BlockPos> visible;
        private final Set<Integer> spawnable = new HashSet<>();
        private final Set<Integer> known = new HashSet<>();
        private final Set<Integer> players = new HashSet<>();
        private TestContext(ServerLevel level, Set<BlockPos> visible) { this.level = level; this.visible = visible; }
        @Override public ServerLevel level() { return level; }
        @Override public ConcealmentPolicy policy() { return new ConcealmentPolicy(level.getMinY(), 64); }
        @Override public boolean isAuthorized(BlockPos pos) { return visible.contains(pos); }
        @Override public boolean isEntityVisible(int id) { return known.contains(id) && spawnable.contains(id); }
        @Override public boolean maySpawnEntity(int id) { return spawnable.contains(id); }
        @Override public boolean isEntityKnown(int id) { return known.contains(id); }
        @Override public boolean isPlayerEntity(int id) { return players.contains(id); }
        @Override public void entitySpawned(int id) { known.add(id); }
        @Override public void entityRemoved(int id) { known.remove(id); }
        @Override public Map<Integer, SectionVisibility> masks(ChunkPos chunk) {
            Map<Integer, BitSet> bits = new HashMap<>();
            for (var pos : visible) if (ChunkPos.containing(pos).equals(chunk)) {
                bits.computeIfAbsent(Math.floorDiv(pos.getY(), 16), ignored -> new BitSet())
                        .set(SectionVisibility.index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15));
            }
            Map<Integer, SectionVisibility> masks = new HashMap<>();
            bits.forEach((y, value) -> masks.put(y, SectionVisibility.from(value)));
            return masks;
        }
    }

    private static byte[] encode(ServerLevel level, ClientboundLevelChunkWithLightPacket packet) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buffer, packet);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally { buffer.release(); }
    }

    private static void assertDark(GameTestHelper helper, ClientboundLightUpdatePacketData light) {
        int count = helper.getLevel().getLightEngine().getLightSectionCount();
        helper.assertTrue(light.skyYMask().isEmpty() && light.blockYMask().isEmpty()
                && light.skyUpdates().isEmpty() && light.blockUpdates().isEmpty(), "No real light data may remain");
        helper.assertTrue(light.emptySkyYMask().cardinality() == count && light.emptyBlockYMask().cardinality() == count,
                "All light sections and both boundaries must be explicitly cleared");
    }
}
