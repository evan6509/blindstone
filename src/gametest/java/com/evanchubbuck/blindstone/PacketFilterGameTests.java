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
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.resources.Identifier;
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
    public void mountainShaftAcrossChunkSeamCannotPublishEitherRoom(GameTestHelper helper) {
        var level=helper.getLevel();
        var left=mountain(level,false);
        var right=new LevelChunk(level,new ChunkPos(1,0));
        var template=mountain(level,false);
        for(int i=0;i<right.getSections().length;i++) right.getSections()[i]=template.getSections()[i].copy();
        for(int y=192;y<=208;y++) for(int z=6;z<=9;z++) for(int offset=0;offset<4;offset++) {
            left.getSection(left.getSectionIndex(y)).setBlockState(12+offset,y&15,z,Blocks.AIR.defaultBlockState());
            right.getSection(right.getSectionIndex(y)).setBlockState(offset,y&15,z,Blocks.AIR.defaultBlockState());
        }
        var policy=com.evanchubbuck.blindstone.config.BlindstoneConfig.defaults().policyFor(level);
        helper.assertTrue(snapshotState(left,policy,Map.of(),15,192,8).is(Blocks.STONE),"Seam shaft cannot expose left room");
        helper.assertTrue(snapshotState(right,policy,Map.of(),0,192,8).is(Blocks.STONE),"Seam shaft cannot expose right room");
        helper.assertTrue(snapshotState(left,policy,Map.of(),8,208,8).is(Blocks.GRASS_BLOCK),"Left exterior survives");
        helper.assertTrue(snapshotState(right,policy,Map.of(),8,208,8).is(Blocks.GRASS_BLOCK),"Right exterior survives");
        helper.succeed();
    }

    @GameTest
    public void independentOreHeightLimitPreservesHigherExterior(GameTestHelper helper) {
        var level=helper.getLevel();
        var chunk=new LevelChunk(level,new ChunkPos(0,0));
        for(int y: new int[]{255,256,272}) chunk.getSection(chunk.getSectionIndex(y)).setBlockState(8,y&15,8,Blocks.DIAMOND_ORE.defaultBlockState());
        var policy=com.evanchubbuck.blindstone.config.BlindstoneConfig.defaults().policyFor(level);
        helper.assertTrue(snapshotState(chunk,policy,Map.of(),8,255,8).is(Blocks.STONE),"Ore below exclusive limit hidden");
        helper.assertTrue(snapshotState(chunk,policy,Map.of(),8,256,8).is(Blocks.DIAMOND_ORE),"Ore at exclusive limit stays real outside geometry policy");
        helper.assertTrue(snapshotState(chunk,policy,Map.of(),8,272,8).is(Blocks.DIAMOND_ORE),"Higher exterior ore stays real");
        helper.succeed();
    }

    @GameTest
    public void netherOreTargetsAreHiddenWithoutMountainGeometry(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        helper.assertTrue(level != null, "Isolated harness must provide the Nether");
        var real = new LevelChunk(level, new ChunkPos(0, 0));
        var ores = List.of(Blocks.ANCIENT_DEBRIS, Blocks.NETHER_QUARTZ_ORE, Blocks.NETHER_GOLD_ORE, Blocks.GILDED_BLACKSTONE);
        for (int x=0;x<ores.size();x++) real.getSection(real.getSectionIndex(80)).setBlockState(x,0,8,ores.get(x).defaultBlockState());
        var policy = com.evanchubbuck.blindstone.config.BlindstoneConfig.defaults().policyFor(level);
        for (int x=0;x<ores.size();x++) helper.assertTrue(snapshotState(real, policy, Map.of(), x,80,8).is(Blocks.NETHERRACK),
                "Nether target must use netherrack independently of Overworld geometry");
        helper.assertTrue(snapshotState(real, policy, Map.of(), 8,80,8).isAir(), "Nether cave geometry stays real");
        helper.succeed();
    }

    @GameTest
    public void oreFilteringIsIndependentOfMountainGeometry(GameTestHelper helper) {
        var level = helper.getLevel();
        var real = new LevelChunk(level, new ChunkPos(0, 0));
        real.getSection(real.getSectionIndex(160)).setBlockState(8, 0, 8, Blocks.DIAMOND_ORE.defaultBlockState());
        var policy = com.evanchubbuck.blindstone.config.BlindstoneConfig.defaults()
                .forWorldBounds(level.getMinY(), level.getMaxY() + 1).policy();
        helper.assertTrue(snapshotState(real, policy, Map.of(), 8, 160, 8).is(Blocks.STONE),
                "Ore above an absent mountain boundary must still be hidden independently");
        var bits = new BitSet(); bits.set(SectionVisibility.index(8, 0, 8));
        helper.assertTrue(snapshotState(real, policy, Map.of(10, SectionVisibility.from(bits)), 8, 160, 8).is(Blocks.DIAMOND_ORE),
                "Only the discovering viewer receives the exact real ore");
        helper.assertTrue(snapshotState(real, policy, Map.of(), 8, 160, 8).is(Blocks.STONE),
                "Discovery must remain private on resend");
        helper.assertTrue(snapshotState(real, policy, Map.of(), 9, 160, 8).isAir(),
                "Ore policy must not fill exterior air");
        helper.succeed();
    }

    @GameTest
    public void broadMountainOpeningDoesNotPublishConnectedRoom(GameTestHelper helper) {
        var real = mountain(helper.getLevel(), false);
        // Seven-cell sky opening is wider than the existing radius-two closing.
        for (int y = 192; y <= 208; y++) for (int z = 5; z <= 11; z++) for (int x = 5; x <= 11; x++)
            real.getSection(real.getSectionIndex(y)).setBlockState(x, y & 15, z, Blocks.AIR.defaultBlockState());
        var policy = com.evanchubbuck.blindstone.config.BlindstoneConfig.defaults()
                .forWorldBounds(real.getMinY(), real.getMaxY() + 1).policy();
        helper.assertTrue(snapshotState(real, policy, Map.of(), 8, 192, 8).is(Blocks.STONE),
                "Broad sky opening must not publish private connected room air");
        helper.assertTrue(snapshotState(real, policy, Map.of(), 2, 208, 2).is(Blocks.GRASS_BLOCK),
                "Genuine exterior roof must remain real");
        helper.succeed();
    }

    @GameTest
    public void broadLoadedSeamOpeningKeepsBothRoomsPrivate(GameTestHelper helper) {
        var level=helper.getLevel();
        var cp=ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        var left=level.getChunk(cp.x(),cp.z());
        var right=level.getChunk(cp.x()+1,cp.z());
        var previous=new java.util.HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        try {
            for(int z=0;z<16;z++) for(int x=0;x<32;x++) {
                var pos=new BlockPos(cp.x()*16+x,208,cp.z()*16+z);
                previous.put(pos,level.getBlockState(pos));
                level.getChunkAt(pos).setBlockState(pos,(x>=12&&x<=19&&z>=4&&z<=11?Blocks.AIR:Blocks.GRASS_BLOCK).defaultBlockState(),0);
            }
            var policy=com.evanchubbuck.blindstone.config.BlindstoneConfig.defaults().policyFor(level);
            helper.assertTrue(snapshotState(left,policy,Map.of(),15,192,8).is(Blocks.STONE),"Broad loaded seam left remains private");
            helper.assertTrue(snapshotState(right,policy,Map.of(),0,192,8).is(Blocks.STONE),"Broad loaded seam right remains private");
            // Open the enclosure along its complete north/south axis into an ordinary valley.
            for(int z=0;z<16;z++) for(int x=12;x<=19;x++) {
                var pos=new BlockPos(cp.x()*16+x,208,cp.z()*16+z);
                level.getChunkAt(pos).setBlockState(pos,Blocks.AIR.defaultBlockState(),0);
            }
            helper.assertTrue(snapshotState(left,policy,Map.of(),15,192,8).isAir(),"Seam valley mutation invalidates left boundary");
            helper.assertTrue(snapshotState(right,policy,Map.of(),0,192,8).isAir(),"Seam valley mutation invalidates right boundary");
        } finally { previous.forEach((pos,state)->level.getChunkAt(pos).setBlockState(pos,state,0)); }
        helper.succeed();
    }

    @GameTest
    public void wideValleyAndCliffRemainExterior(GameTestHelper helper) {
        var level = helper.getLevel();
        var policy = com.evanchubbuck.blindstone.config.BlindstoneConfig.defaults()
                .forWorldBounds(level.getMinY(), level.getMaxY() + 1).policy();
        for (boolean cliff : new boolean[] {false, true}) {
            var real = new LevelChunk(level, new ChunkPos(0, 0));
            for (int z=0;z<16;z++) for (int x=0;x<16;x++) {
                int top = (x<4 || !cliff && x>11) ? 208 : 191;
                for (int y=190;y<=top;y++) real.getSection(real.getSectionIndex(y))
                        .setBlockState(x,y&15,z,(y==top?Blocks.GRASS_BLOCK:Blocks.STONE).defaultBlockState());
            }
            helper.assertTrue(snapshotState(real,policy,Map.of(),8,192,8).isAir(),
                    "Wide valley/cliff exterior air must remain real");
            helper.assertTrue(snapshotState(real,policy,Map.of(),8,191,8).is(Blocks.GRASS_BLOCK),
                    "Wide valley/cliff floor must remain grass");
        }
        helper.succeed();
    }

    @GameTest
    public void actualSurfaceAtMountainRoomElevationsRemainsReal(GameTestHelper helper) {
        var level = helper.getLevel();
        var policy = com.evanchubbuck.blindstone.config.BlindstoneConfig.defaults()
                .forWorldBounds(level.getMinY(), level.getMaxY() + 1).policy();
        for (int y : new int[] {96, 128, 192}) {
            var real = new LevelChunk(level, new ChunkPos(0, 0));
            real.getSection(real.getSectionIndex(y)).setBlockState(8, y & 15, 8, Blocks.GRASS_BLOCK.defaultBlockState());
            var sections = ChunkFilter.snapshot(real, policy, Map.of()).getReadBuffer();
            try {
                for (int i = 0; i < level.getSectionsCount(); i++) {
                    var section = new LevelChunkSection(level.palettedContainerFactory());
                    section.read(sections);
                    if (i == level.getSectionIndex(y)) {
                        helper.assertTrue(section.getBlockState(8, y & 15, 8).is(Blocks.GRASS_BLOCK),
                                "A real surface at Y=" + y + " must remain grass");
                        helper.assertTrue(section.getBlockState(9, y & 15, 8).isAir(),
                                "External air at Y=" + y + " must remain air");
                    }
                }
            } finally { sections.release(); }
        }
        helper.succeed();
    }

    /** Small detached mountain: three private elevations beneath a real grass roof. */
    @GameTest
    public void defaultMountainRoomsAreConcealedInSerializedSections(GameTestHelper helper) {
        assertMountainSerialization(helper, false);
        helper.succeed();
    }

    @GameTest
    public void defaultMountainOresAreConcealedInSerializedSections(GameTestHelper helper) {
        assertMountainSerialization(helper, true);
        helper.succeed();
    }

    private static void assertMountainSerialization(GameTestHelper helper, boolean ore) {
        var level = helper.getLevel();
        var real = mountain(level, ore);
        var policy = com.evanchubbuck.blindstone.config.BlindstoneConfig.defaults()
                .forWorldBounds(level.getMinY(), level.getMaxY() + 1).policy();
        helper.assertTrue(TerrainBoundary.surface(real, 10, 10) == 208,
                "Narrow shaft boundary must bridge the roof, got " + TerrainBoundary.surface(real, 10, 10));
        var data = ChunkFilter.snapshot(real, policy, Map.of());
        var encoded = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(encoded,
                    new ClientboundLevelChunkWithLightPacket(0, 0, data, PacketFilter.darkLight(level)));
            var decoded = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(encoded);
            List<BlockPos> hiddenEntities = new ArrayList<>();
            decoded.chunkData().forEachBlockEntityTag(0, 0, (p, type, tag) -> hiddenEntities.add(p.immutable()));
            helper.assertTrue(hiddenEntities.isEmpty(), "Mountain storage tags must be absent");
            var sections = decoded.chunkData().getReadBuffer();
            try {
                for (int i = 0; i < level.getSectionsCount(); i++) {
                    var section = new LevelChunkSection(level.palettedContainerFactory());
                    section.read(sections);
                    int sectionY = level.getMinSectionY() + i;
                    for (int y : new int[] {96, 128, 192}) if (sectionY == y >> 4)
                        helper.assertTrue(section.getBlockState(8, y & 15, 8).is(Blocks.STONE),
                                "Undiscovered mountain " + (ore ? "ore" : "room") + " at Y=" + y + " must serialize as stone");
                    for (int floor : new int[] {96, 128, 192}) if (sectionY == floor >> 4) {
                        for (int dy = 0; dy < 3; dy++) for (int z = 6; z <= 10; z++) for (int x = 6; x <= 10; x++)
                            helper.assertTrue(section.getBlockState(x, (floor + dy) & 15, z).is(Blocks.STONE),
                                    "Entire room including storage/light must be concealed at " + floor);
                    }
                    if (sectionY == 12)
                        helper.assertTrue(section.getBlockState(10, 15, 10).is(Blocks.STONE), "Shaft cannot authorize deep air");
                    if (sectionY == 13) {
                        helper.assertTrue(section.getBlockState(8, 0, 8).is(Blocks.GRASS_BLOCK), "Real mountain roof must survive");
                        helper.assertTrue(section.getBlockState(8, 1, 8).isAir(), "Sky above real mountain must stay air");
                    }
                }
            } finally { sections.release(); }
        } finally { encoded.release(); }
    }

    private static LevelChunk mountain(ServerLevel level, boolean ore) {
        var real = new LevelChunk(level, new ChunkPos(0, 0));
        for (int y = level.getMinY(); y <= 208; y++)
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                real.getSection(real.getSectionIndex(y)).setBlockState(x, y & 15, z,
                        (y == 208 ? Blocks.GRASS_BLOCK : Blocks.STONE).defaultBlockState());
        for (int floor : new int[] {96, 128, 192}) {
            for (int y = floor; y < floor + 3; y++)
                for (int z = 6; z <= 10; z++) for (int x = 6; x <= 10; x++)
                    real.getSection(real.getSectionIndex(y)).setBlockState(x, y & 15, z, Blocks.AIR.defaultBlockState());
            real.getSection(real.getSectionIndex(floor)).setBlockState(7, floor & 15, 8, Blocks.CHEST.defaultBlockState());
            var chest = new ChestBlockEntity(new BlockPos(7, floor, 8), Blocks.CHEST.defaultBlockState());
            chest.setLevel(level);
            real.getBlockEntities().put(chest.getBlockPos(), chest);
            real.getSection(real.getSectionIndex(floor + 1)).setBlockState(9, (floor + 1) & 15, 8, Blocks.TORCH.defaultBlockState());
        }
        // A sky-connected shaft must not publish the room it reaches.
        for (int y = 192; y <= 208; y++)
            real.getSection(real.getSectionIndex(y)).setBlockState(10, y & 15, 10, Blocks.AIR.defaultBlockState());
        for (int y : new int[] {96, 128, 192})
            real.getSection(real.getSectionIndex(y)).setBlockState(8, y & 15, 8,
                    (ore ? Blocks.DIAMOND_ORE : Blocks.AIR).defaultBlockState());
        return real;
    }

    @GameTest
    public void mountainExplorationIsPrivateAndTerrainRevisionIsLocal(GameTestHelper helper) {
        var level = helper.getLevel();
        var real = mountain(level, false);
        var untouched = mountain(level, false);
        var policy = com.evanchubbuck.blindstone.config.BlindstoneConfig.defaults()
                .forWorldBounds(level.getMinY(), level.getMaxY() + 1).policy();
        var bits = new BitSet(); bits.set(SectionVisibility.index(8, 0, 8));
        var explored = Map.of(12, SectionVisibility.from(bits));
        helper.assertTrue(snapshotState(real, policy, explored, 8, 192, 8).isAir(), "Explorer receives exact room air");
        helper.assertTrue(snapshotState(real, policy, Map.of(), 8, 192, 8).is(Blocks.STONE), "Second viewer keeps hidden room");
        helper.assertTrue(!policy.protects(real, 8, 220, 8), "Sky starts public");
        real.setBlockState(new BlockPos(8, 240, 8), Blocks.STONE.defaultBlockState(), 0);
        helper.assertTrue(policy.protects(real, 8, 220, 8), "Roof edit invalidates cached column through production mutation hook");
        helper.assertTrue(!policy.protects(untouched, 8, 220, 8), "Other chunk classification stays independent");
        real.setBlockState(new BlockPos(8, 240, 8), Blocks.AIR.defaultBlockState(), 0);
        helper.assertTrue(!policy.protects(real, 8, 220, 8), "Removing roof refreshes boundary");
        helper.assertTrue(snapshotState(real, policy, Map.of(), 8, 192, 8).is(Blocks.STONE), "Resend still hides room");
        helper.succeed();
    }

    private static net.minecraft.world.level.block.state.BlockState snapshotState(LevelChunk real,
            ConcealmentPolicy policy, Map<Integer, SectionVisibility> masks, int x, int y, int z) {
        var buffer = ChunkFilter.snapshot(real, policy, masks).getReadBuffer();
        try {
            for (int i = 0; i < real.getSections().length; i++) {
                var section = new LevelChunkSection(real.getLevel().palettedContainerFactory());
                section.read(buffer);
                if (i == real.getSectionIndex(y)) return section.getBlockState(x, y & 15, z);
            }
            throw new AssertionError("Missing section");
        } finally { buffer.release(); }
    }

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
    public void vanillaBrandPassesAndUnauditedCustomPayloadFailsClosed(GameTestHelper helper) {
        var brand = new ClientboundCustomPayloadPacket(new BrandPayload("fixture"));
        helper.assertTrue(PacketFilter.filter(brand, context(helper.getLevel(), Set.of())) == brand,
                "Plain vanilla/Fabric brand negotiation must pass without world data");
        boolean failed = false;
        try { PacketFilter.filter(new ClientboundCustomPayloadPacket(new DiscardedPayload(Identifier.parse("fixture:unknown"))), context(helper.getLevel(), Set.of())); }
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
                && filtered.getData() == 0, "Ordinary cow spawn has its actual vanilla zero data and precedes authorized references");
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
    public void visibleMetadataRetainsOrdinaryIntegerNameAndHealthFields(GameTestHelper helper) {
        var cow = helper.spawn(EntityTypes.COW, new BlockPos(1, 2, 1));
        cow.setAirSupply(100);
        cow.setTicksFrozen(12);
        cow.setCustomName(Component.literal("Visible fixture"));
        cow.setHealth(7);
        var context = visibleContext(helper.getLevel(), cow);
        var source = metadata(cow);
        var safe = (ClientboundSetEntityDataPacket) PacketFilter.filter(source, context);
        helper.assertTrue(safe.packedItems().size() == source.packedItems().size()
                        && safe.packedItems().stream().anyMatch(value -> value.serializer() == EntityDataSerializers.INT && value.value().equals(100))
                        && safe.packedItems().stream().anyMatch(value -> value.serializer() == EntityDataSerializers.FLOAT && value.value().equals(7.0F)),
                "Audited ordinary integer and scalar fields must survive instead of a minimal serializer allowlist");
        helper.assertTrue(cow.getAirSupply() == 100 && cow.getTicksFrozen() == 12 && cow.getHealth() == 7,
                "Metadata filtering must not modify authoritative entity state");
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

    @GameTest
    public void droppedItemMetadataKeepsActualStackAndDoesNotMutateIt(GameTestHelper helper) {
        var item = helper.spawn(EntityTypes.ITEM, new BlockPos(1, 2, 1));
        item.setItem(new ItemStack(Items.DIAMOND, 3));
        var context = visibleContext(helper.getLevel(), item);
        var source = metadata(item);
        var safe = (ClientboundSetEntityDataPacket) PacketFilter.filter(source, context);
        helper.assertTrue(safe.packedItems().stream().anyMatch(value -> value.serializer() == EntityDataSerializers.ITEM_STACK
                        && value.value() instanceof ItemStack stack && stack.is(Items.DIAMOND) && stack.getCount() == 3),
                "Visible dropped item must receive its real item stack and count");
        helper.assertTrue(item.getItem().is(Items.DIAMOND) && item.getItem().getCount() == 3,
                "Filtering must preserve server item entity state");
        helper.succeed();
    }

    @GameTest
    public void itemFrameFacingItemAndRotationSurviveFiltering(GameTestHelper helper) {
        var frame = new ItemFrame(helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 1)), Direction.EAST);
        helper.getLevel().addFreshEntity(frame);
        frame.setItem(new ItemStack(Items.EMERALD), false);
        frame.setRotation(5);
        var context = visibleContext(helper.getLevel(), frame);
        context.visible.add(frame.getPos());
        var source = new ClientboundAddEntityPacket(frame, Direction.EAST.get3DDataValue(), frame.getPos());
        var safe = (ClientboundAddEntityPacket) PacketFilter.filter(source, context);
        helper.assertTrue(safe != null && safe.getData() == Direction.EAST.get3DDataValue() && safe.getData() != 0,
                "Item-frame facing must retain its actual nonzero vanilla spawn-data meaning");
        var state = (ClientboundSetEntityDataPacket) PacketFilter.filter(metadata(frame), context);
        helper.assertTrue(state.packedItems().stream().anyMatch(value -> value.serializer() == EntityDataSerializers.ITEM_STACK
                        && value.value() instanceof ItemStack stack && stack.is(Items.EMERALD))
                        && state.packedItems().stream().anyMatch(value -> value.serializer() == EntityDataSerializers.INT && value.value().equals(5)),
                "Visible frame must retain its displayed item and rotation");
        helper.succeed();
    }

    @GameTest
    public void paintingKeepsFacingAndVariantMetadata(GameTestHelper helper) throws Exception {
        var initial = helper.spawn(EntityTypes.PAINTING, new BlockPos(1, 2, 1));
        var painting = new Painting(helper.getLevel(), helper.absolutePos(new BlockPos(2, 2, 1)), Direction.SOUTH, initial.getVariant());
        helper.getLevel().addFreshEntity(painting);
        var context = visibleContext(helper.getLevel(), painting);
        context.visible.add(painting.getPos());
        var packet = new ClientboundAddEntityPacket(painting, Direction.SOUTH.get3DDataValue(), painting.getPos());
        var safe = (ClientboundAddEntityPacket) PacketFilter.filter(packet, context);
        helper.assertTrue(safe != null && safe.getData() == Direction.SOUTH.get3DDataValue(),
                "Painting's orientation spawn data must remain intact");
        var field = Painting.class.getDeclaredField("DATA_PAINTING_VARIANT_ID");
        field.setAccessible(true);
        var accessor = (net.minecraft.network.syncher.EntityDataAccessor<?>) field.get(null);
        var sourceState = new ClientboundSetEntityDataPacket(painting.getId(), List.of(
                new SynchedEntityData.DataValue<>(accessor.id(), EntityDataSerializers.PAINTING_VARIANT, painting.getVariant())));
        var state = (ClientboundSetEntityDataPacket) PacketFilter.filter(sourceState, context);
        helper.assertTrue(state.packedItems().getFirst().value().equals(painting.getVariant()), "Painting variant remains intact");
        helper.succeed();
    }

    @GameTest
    public void frogAndSleepingMetadataRemoveOnlyConcealedTargets(GameTestHelper helper) {
        var frog = helper.spawn(EntityTypes.FROG, new BlockPos(1, 2, 1));
        var target = helper.spawn(EntityTypes.COW, new BlockPos(2, 2, 1));
        frog.setTongueTarget(target);
        frog.setSleepingPos(frog.blockPosition().east(20));
        var context = visibleContext(helper.getLevel(), frog);
        var source = metadata(frog);
        var safe = (ClientboundSetEntityDataPacket) PacketFilter.filter(source, context);
        helper.assertTrue(safe.packedItems().stream().filter(value -> value.serializer() == EntityDataSerializers.OPTIONAL_UNSIGNED_INT)
                        .allMatch(value -> ((java.util.OptionalInt) value.value()).isEmpty())
                        && safe.packedItems().stream().filter(value -> value.serializer() == EntityDataSerializers.OPTIONAL_BLOCK_POS)
                        .allMatch(value -> ((Optional<?>) value.value()).isEmpty()),
                "Hidden tongue ids and bed positions must clear using their actual optional schemas");
        context.known.add(target.getId()); context.spawnable.add(target.getId());
        var visible = (ClientboundSetEntityDataPacket) PacketFilter.filter(source, context);
        helper.assertTrue(visible.packedItems().stream().anyMatch(value -> value.serializer() == EntityDataSerializers.OPTIONAL_UNSIGNED_INT
                        && ((java.util.OptionalInt) value.value()).orElse(-1) == target.getId()),
                "Visible frog target id must survive without suppressing unrelated metadata");
        helper.succeed();
    }

    @GameTest
    public void fallingBlockKeepsItsBlockStateAndSanitizesHiddenStartPosition(GameTestHelper helper) {
        var falling = helper.spawn(EntityTypes.FALLING_BLOCK, new BlockPos(1, 2, 1));
        var hiddenStart = falling.blockPosition().offset(20, 0, 0);
        falling.setStartPos(hiddenStart);
        var context = visibleContext(helper.getLevel(), falling);
        var source = spawn(falling, Block.getId(falling.getBlockState()));
        var safe = (ClientboundAddEntityPacket) PacketFilter.filter(source, context);
        helper.assertTrue(safe.getData() == Block.getId(falling.getBlockState()) && safe.getData() != 0,
                "Falling sand must not become the zero/air spawn-data state");
        var state = (ClientboundSetEntityDataPacket) PacketFilter.filter(metadata(falling), context);
        helper.assertTrue(state.packedItems().stream().anyMatch(value -> value.serializer() == EntityDataSerializers.BLOCK_POS
                        && value.value().equals(falling.blockPosition())),
                "Concealed start positions must be replaced with the already visible entity position");
        helper.assertTrue(falling.getStartPos().equals(hiddenStart), "Filtering must not mutate the real falling-block start");
        helper.succeed();
    }

    @GameTest
    public void projectileOwnerReferenceIsPreservedOnlyAfterOwnerIsVisible(GameTestHelper helper) {
        var owner = helper.spawn(EntityTypes.COW, new BlockPos(1, 2, 1));
        var arrow = helper.spawn(EntityTypes.ARROW, new BlockPos(2, 2, 1));
        arrow.setOwner(owner);
        var context = visibleContext(helper.getLevel(), arrow);
        var source = spawn(arrow, owner.getId());
        var hiddenOwner = (ClientboundAddEntityPacket) PacketFilter.filter(source, context);
        helper.assertTrue(hiddenOwner.getData() == 0 && source.getData() == owner.getId(),
                "A hidden projectile owner must not survive in spawn data or mutate the original");
        context.spawnable.add(owner.getId());
        context.known.add(owner.getId());
        var visibleOwner = (ClientboundAddEntityPacket) PacketFilter.filter(source, context);
        helper.assertTrue(visibleOwner.getData() == owner.getId(), "Legitimate visible projectile ownership must remain functional");
        helper.succeed();
    }

    @GameTest
    public void villagerAndArmorStandTypedMetadataRemainUsable(GameTestHelper helper) {
        var villager = helper.spawn(EntityTypes.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData().withLevel(4));
        var stand = helper.spawn(EntityTypes.ARMOR_STAND, new BlockPos(2, 2, 1));
        stand.setCustomName(Component.literal("Visible stand"));
        var context = visibleContext(helper.getLevel(), villager, stand);
        var villageState = (ClientboundSetEntityDataPacket) PacketFilter.filter(metadata(villager), context);
        var standState = (ClientboundSetEntityDataPacket) PacketFilter.filter(metadata(stand), context);
        helper.assertTrue(villageState.packedItems().stream().anyMatch(value -> value.serializer() == EntityDataSerializers.VILLAGER_DATA
                        && value.value().equals(villager.getVillagerData())) && !standState.packedItems().isEmpty(),
                "Visible villager trade-level/variant and armor-stand metadata must not be blanked");
        helper.succeed();
    }

    @GameTest
    public void vehicleDamageDisplayStateAndOffsetRemainUsable(GameTestHelper helper) {
        var cart = helper.spawn(EntityTypes.MINECART, new BlockPos(1, 2, 1));
        cart.setCustomDisplayBlockState(Optional.of(Blocks.GOLD_BLOCK.defaultBlockState()));
        cart.setDisplayOffset(11);
        cart.setDamage(5);
        var boat = helper.spawn(EntityTypes.OAK_BOAT, new BlockPos(2, 2, 1));
        boat.setHurtTime(9);
        var context = visibleContext(helper.getLevel(), cart, boat);
        var cartState = (ClientboundSetEntityDataPacket) PacketFilter.filter(metadata(cart), context);
        var boatState = (ClientboundSetEntityDataPacket) PacketFilter.filter(metadata(boat), context);
        helper.assertTrue(cartState.packedItems().stream().anyMatch(value -> value.serializer() == EntityDataSerializers.INT && value.value().equals(11))
                        && cartState.packedItems().stream().anyMatch(value -> value.serializer() == EntityDataSerializers.OPTIONAL_BLOCK_STATE
                            && ((Optional<?>) value.value()).filter(state -> state.equals(Blocks.GOLD_BLOCK.defaultBlockState())).isPresent())
                        && boatState.packedItems().stream().anyMatch(value -> value.serializer() == EntityDataSerializers.INT && value.value().equals(9)),
                "Visible minecart display and ordinary boat hurt-time metadata must not be mistaken for entity ids");
        helper.succeed();
    }

    @GameTest
    public void unknownMetadataFieldCannotMasqueradeAsOrdinaryInteger(GameTestHelper helper) {
        var cow = helper.spawn(EntityTypes.COW, new BlockPos(1, 2, 1));
        boolean failed = false;
        try {
            PacketFilter.filter(new ClientboundSetEntityDataPacket(cow.getId(), List.of(
                    new SynchedEntityData.DataValue<>(254, EntityDataSerializers.INT, 123))), visibleContext(helper.getLevel(), cow));
        } catch (IllegalStateException expected) { failed = true; }
        helper.assertTrue(failed, "An unknown entity-field schema must fail closed rather than forward an arbitrary integer");
        helper.succeed();
    }

    @GameTest
    public void guardianAttackReferencesUseActualFieldSemantics(GameTestHelper helper) throws Exception {
        var guardian = helper.spawn(EntityTypes.GUARDIAN, new BlockPos(1, 2, 1));
        var target = helper.spawn(EntityTypes.COW, new BlockPos(2, 2, 1));
        var field = Guardian.class.getDeclaredField("DATA_ID_ATTACK_TARGET");
        field.setAccessible(true);
        var accessor = (net.minecraft.network.syncher.EntityDataAccessor<?>) field.get(null);
        var targetValue = new SynchedEntityData.DataValue<>(accessor.id(), EntityDataSerializers.INT, target.getId());
        var source = new ClientboundSetEntityDataPacket(guardian.getId(), List.of(targetValue));
        var context = visibleContext(helper.getLevel(), guardian);
        var hidden = (ClientboundSetEntityDataPacket) PacketFilter.filter(source, context);
        helper.assertTrue(hidden.packedItems().getFirst().value().equals(0), "Hidden attack target id must clear using Guardian's zero sentinel");
        context.known.add(target.getId()); context.spawnable.add(target.getId());
        var visible = (ClientboundSetEntityDataPacket) PacketFilter.filter(source, context);
        helper.assertTrue(visible.packedItems().getFirst().value().equals(target.getId()), "Known visible attack target id must survive");
        helper.succeed();
    }

    @GameTest
    public void relativeTeleportUsesAuthoritativeAbsolutePosition(GameTestHelper helper) {
        var cow = helper.spawn(EntityTypes.COW, new BlockPos(1, 2, 1));
        var context = visibleContext(helper.getLevel(), cow);
        var source = new ClientboundTeleportEntityPacket(cow.getId(), new PositionMoveRotation(new Vec3(100, 100, 100),
                Vec3.ZERO, 30, 20), Set.of(Relative.X, Relative.Y, Relative.Z, Relative.Y_ROT), false);
        var safe = (ClientboundTeleportEntityPacket) PacketFilter.filter(source, context);
        helper.assertTrue(safe.relatives().isEmpty() && safe.change().position().equals(cow.position())
                        && safe.change().yRot() == cow.getYRot(),
                "Relative teleport must use the actual accepted destination instead of a client's unknown old base");
        helper.succeed();
    }

    @GameTest
    public void passengerAndLeashPacketsPruneHiddenReferencesWithoutDroppingVisibleGraph(GameTestHelper helper) {
        var vehicle = helper.spawn(EntityTypes.COW, new BlockPos(1, 2, 1));
        var visible = helper.spawn(EntityTypes.COW, new BlockPos(2, 2, 1));
        var hidden = helper.spawn(EntityTypes.COW, new BlockPos(3, 2, 1));
        var context = visibleContext(helper.getLevel(), vehicle, visible);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(vehicle.getId()); buffer.writeVarIntArray(new int[] {visible.getId(), hidden.getId()});
            var original = ClientboundSetPassengersPacket.STREAM_CODEC.decode(buffer);
            var safe = (ClientboundSetPassengersPacket) PacketFilter.filter(original, context);
            helper.assertTrue(Arrays.equals(safe.getPassengers(), new int[] {visible.getId()}) && original.getPassengers().length == 2,
                    "Visible passenger graph survives while hidden ids are removed from an isolated packet");
        } finally { buffer.release(); }
        var leash = (ClientboundSetEntityLinkPacket) PacketFilter.filter(new ClientboundSetEntityLinkPacket(vehicle, hidden), context);
        helper.assertTrue(leash.getSourceId() == vehicle.getId() && leash.getDestId() == 0,
                "Hidden leash holder must detach rather than leave a stale unknown reference");
        helper.succeed();
    }

    @GameTest
    public void visibleTypedParticlesAndExplosionEffectsRemainFunctional(GameTestHelper helper) {
        var center = new BlockPos(2, 20, 3);
        Set<BlockPos> visible = new HashSet<>();
        for (int y = 19; y <= 21; y++) for (int z = 2; z <= 4; z++) for (int x = 1; x <= 3; x++) visible.add(new BlockPos(x, y, z));
        var context = new TestContext(helper.getLevel(), visible);
        var block = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());
        var typed = new ClientboundLevelParticlesPacket(block, false, false, 2, 20, 3, 0, 0, 0, 0, 1);
        helper.assertTrue(PacketFilter.filter(typed, context) == typed, "Visible block-state particles must retain ordinary break effects");
        var trail = new ClientboundLevelParticlesPacket(new TrailParticleOption(Vec3.atCenterOf(center.east()), 0xffffff, 10),
                false, false, 2, 20, 3, 0, 0, 0, 0, 1);
        helper.assertTrue(PacketFilter.filter(trail, context) == trail, "Visible compound particle destination may pass");
        var explosion = new ClientboundExplodePacket(new Vec3(2, 20, 3), 1, 7, Optional.empty(), ParticleTypes.EXPLOSION,
                SoundEvents.GENERIC_EXPLODE, WeightedList.of(new ExplosionParticleInfo(block, 1, 1)), true);
        var safe = (ClientboundExplodePacket) PacketFilter.filter(explosion, context);
        helper.assertTrue(safe.center().equals(explosion.center()) && safe.radius() == 1 && safe.blockCount() == 7
                        && !safe.blockParticles().isEmpty() && safe.playSound(),
                "Fully authorized visible explosion footprint must retain normal effects and aggregate state");
        helper.succeed();
    }

    private static TestContext visibleContext(ServerLevel level, Entity... entities) {
        Set<BlockPos> positions = new HashSet<>();
        var result = new TestContext(level, positions);
        for (Entity entity : entities) {
            positions.add(entity.blockPosition()); result.known.add(entity.getId()); result.spawnable.add(entity.getId());
        }
        return result;
    }

    private static ClientboundAddEntityPacket spawn(Entity entity, int data) {
        return new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), entity.getX(), entity.getY(), entity.getZ(),
                entity.getXRot(), entity.getYRot(), entity.getType(), data, entity.getDeltaMovement(), entity.getYHeadRot());
    }

    private static ClientboundSetEntityDataPacket metadata(Entity entity) {
        var data = entity.getEntityData().getNonDefaultValues();
        return new ClientboundSetEntityDataPacket(entity.getId(), data == null ? List.of() : data);
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
        @Override public ClientboundLightUpdatePacketData light(ChunkPos chunk) { return PacketFilter.darkLight(level); }
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
