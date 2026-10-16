package com.evanchubbuck.blindstone.mixin;

import com.evanchubbuck.blindstone.BlindstoneMod;
import com.evanchubbuck.blindstone.HookReadiness;
import com.evanchubbuck.blindstone.runtime.BlindstoneRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ordinary admission requires validated runtime and transformed hooks; test mode stays isolated. */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin implements HookReadiness.AdmissionHook {
    @Shadow public abstract MinecraftServer getServer();

    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void blindstone$validateAdmission(CallbackInfoReturnable<Component> callback) {
        var runtime = BlindstoneRuntime.get(getServer());
        Component rejection = BlindstoneMod.admissionRejection(Boolean.getBoolean("blindstone.development"),
                HookReadiness.ready(), runtime != null && runtime.admitsPlayers());
        if (rejection != null) callback.setReturnValue(rejection);
    }
}
