package com.evanchubbuck.blindstone.mixin;

import com.evanchubbuck.blindstone.runtime.BlindstoneRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Apply backpressure before vanilla removes pending chunks or opens another acknowledged batch. */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin {
    @Inject(method = "sendNextChunks", at = @At("HEAD"), cancellable = true)
    private void blindstone$waitForPreparedBatch(ServerPlayer player, CallbackInfo callback) {
        var runtime = BlindstoneRuntime.get(player.level().getServer());
        if (runtime != null && runtime.chunkBacklogged(player)) callback.cancel();
    }
}
