package com.evanchubbuck.blindstone.mixin;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityAccess {
    @Invoker("updatePlayers") void blindstone$updatePlayers(List<ServerPlayer> players);
    @Invoker("updatePlayer") void blindstone$updatePlayer(ServerPlayer player);
}
