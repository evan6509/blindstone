package com.evanchubbuck.blindstone.mixin;

import com.evanchubbuck.blindstone.BlindstoneMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Closed admission while the isolated prototype has no complete outgoing packet coverage. */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void blindstone$denyDevelopmentAdmission(CallbackInfoReturnable<Component> callback) {
        callback.setReturnValue(Component.literal(BlindstoneMod.ADMISSION_DENIED));
    }
}
