package com.evanchubbuck.blindstone.mixin;

import com.evanchubbuck.blindstone.HookReadiness;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

/** Required injection mixins on these same targets are configured with defaultRequire=1. */
@Mixin({LevelChunk.class, ChunkMap.class, PlayerChunkSender.class})
public abstract class HookReadinessMixin implements HookReadiness.RequiredHook { }
