package com.evanchubbuck.blindstone.mixin;

import com.evanchubbuck.blindstone.runtime.BlindstoneRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {
    @Shadow @Final private Entity entity;
    @Shadow public abstract void removePlayer(ServerPlayer player);

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void blindstone$filterPairing(ServerPlayer player, CallbackInfo callback) {
        var runtime = BlindstoneRuntime.get(player.level().getServer());
        if (runtime == null) return;
        try {
            if (!runtime.entityMayBeSeen(player, entity)) {
                removePlayer(player);
                callback.cancel();
            }
        } catch (RuntimeException failure) {
            // Saturation is a per-viewer availability failure, never a vanilla unfiltered fallback.
            runtime.diagnostics().failed(com.evanchubbuck.blindstone.diagnostics.BlindstoneDiagnostics.Subsystem.ENTITIES,
                    com.evanchubbuck.blindstone.diagnostics.BlindstoneDiagnostics.Failure.QUEUE_SATURATION);
            runtime.disconnect(player, "Blindstone could not safely update entity visibility within its budget.");
            callback.cancel();
        }
    }
}
