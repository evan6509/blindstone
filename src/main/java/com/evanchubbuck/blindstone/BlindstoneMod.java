package com.evanchubbuck.blindstone;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.LoggerFactory;

public final class BlindstoneMod implements ModInitializer {
    public static final String ADMISSION_DENIED = "Blindstone development spike: protection is incomplete; player admission is disabled.";
    public static final String STARTUP_REFUSED = "Blindstone protection cannot activate: mandatory outgoing packet hooks are not implemented. "
            + "For isolated development tests only, set -Dblindstone.development=true; player admission remains disabled.";

    @Override
    public void onInitialize() {
        requireDevelopmentMode(Boolean.getBoolean("blindstone.development"));
        LoggerFactory.getLogger("blindstone").warn(ADMISSION_DENIED);
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) ->
                dispatcher.register(Commands.literal("blindstone")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.literal("status").executes(context -> {
                            var level = context.getSource().getServer().overworld();
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Blindstone DEVELOPMENT ONLY; protection INACTIVE; player admission DENIED. "
                                    + "Prototype bounds: minecraft:overworld Y[" + level.getMinY() + ",64). "
                                    + "Active viewers: 0; backlog: 0. Minecraft 26.3 / Loader 0.19.5 / Fabric API 0.163.0+26.3."), false);
                            return 1;
                        }))
                        .then(Commands.literal("verify").executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Packet hooks: NOT IMPLEMENTED. Observed/filtered outgoing paths: NONE. "
                                    + "Chunk data codec: isolated prototype only. Automated evidence: see docs/TEST_REPORT.md. "
                                    + "Live gameplay, lighting, entities, profiler and performance: UNVALIDATED."), false);
                            return 1;
                        }))));
    }

    public static void requireDevelopmentMode(boolean development) {
        if (!development) throw new IllegalStateException(STARTUP_REFUSED);
    }
}
