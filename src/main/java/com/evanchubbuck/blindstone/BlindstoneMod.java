package com.evanchubbuck.blindstone;

import com.evanchubbuck.blindstone.diagnostics.BlindstoneDiagnostics;
import com.evanchubbuck.blindstone.runtime.BlindstoneRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.FullChunkStatus;
import com.evanchubbuck.blindstone.mixin.CommandSourceAccess;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.LoggerFactory;

public final class BlindstoneMod implements ModInitializer {
    public static final String ADMISSION_DENIED = "Blindstone isolated development tests: player admission is disabled.";
    public static final String STARTUP_REFUSED = "Blindstone protection is unavailable: mandatory hooks or runtime are not ready. Check the server log and exact supported versions.";

    @Override
    public void onInitialize() {
        HookReadiness.verify();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            BlindstoneRuntime.start(server);
            var runtime = BlindstoneRuntime.get(server);
            boolean development = Boolean.getBoolean("blindstone.development");
            runtime.diagnostics().activation(development ? BlindstoneDiagnostics.Activation.DEVELOPMENT_ONLY
                    : BlindstoneDiagnostics.Activation.ENFORCING);
            for (var path : BlindstoneDiagnostics.PacketPath.values()) runtime.diagnostics().hookLoaded(path);
            LoggerFactory.getLogger("blindstone").info(development ? ADMISSION_DENIED
                    : "Blindstone private alpha active: Overworld underground filtering enabled. Player tracking and ordinary audible cues remain available; see the acceptance-test limits.");
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(BlindstoneRuntime::stop);
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            var runtime = BlindstoneRuntime.get(server);
            if (runtime != null) runtime.beginTick();
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            var runtime = BlindstoneRuntime.get(level.getServer());
            if (runtime != null) runtime.chunksChanged(level, chunk.getPos());
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            var runtime = BlindstoneRuntime.get(level.getServer());
            if (runtime != null) runtime.chunksChanged(level, chunk.getPos());
        });
        ServerChunkEvents.FULL_CHUNK_STATUS_CHANGE.register((level, chunk, before, after) -> {
            if (before != FullChunkStatus.INACCESSIBLE && after != FullChunkStatus.INACCESSIBLE) return;
            var runtime = BlindstoneRuntime.get(level.getServer());
            if (runtime != null) runtime.chunksChanged(level, chunk.getPos());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var runtime = BlindstoneRuntime.get(server);
            if (runtime != null) runtime.remove(handler.player);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) ->
                dispatcher.register(Commands.literal("blindstone")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.literal("status").executes(context -> {
                            var runtime = BlindstoneRuntime.get(context.getSource().getServer());
                            context.getSource().sendSuccess(() -> Component.literal(runtime == null ? "Blindstone unavailable; admission DENIED."
                                    : runtime.diagnostics().status(runtime.configuration().current())), false);
                            return 1;
                        }))
                        .then(Commands.literal("verify").executes(context -> {
                            var runtime = BlindstoneRuntime.get(context.getSource().getServer());
                            context.getSource().sendSuccess(() -> Component.literal(runtime == null ? "Runtime not ready; no protection evidence."
                                    : runtime.diagnostics().verify() + " Graphical gameplay, adversarial clients and SMP performance require separate acceptance checks."), false);
                            return 1;
                        }))
                        .then(Commands.literal("inspect").then(Commands.argument("player", EntityArgument.player()).executes(context -> {
                            var runtime = BlindstoneRuntime.get(context.getSource().getServer());
                            if (runtime == null) return 0;
                            var player = EntityArgument.getPlayer(context, "player");
                            context.getSource().sendSuccess(() -> Component.literal(runtime.inspect(player)), false);
                            return 1;
                        })))
                        .then(Commands.literal("reload").executes(context -> {
                            var runtime = BlindstoneRuntime.get(context.getSource().getServer());
                            if (runtime == null) return 0;
                            try {
                                runtime.reload();
                                context.getSource().sendSuccess(() -> Component.literal("Blindstone configuration validated and reloaded; affected views will refresh safely."), true);
                                return 1;
                            } catch (Exception failure) {
                                context.getSource().sendFailure(Component.literal("Reload rejected; the previous configuration remains active. " + failure.getMessage()));
                                return 0;
                            }
                        }))
                        .then(Commands.literal("disable")
                                .requires(BlindstoneMod::isServerConsole)
                                .executes(context -> {
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Disabling Blindstone resumes real world delivery on reconnect and disconnects tracked viewers. Run blindstone disable confirm to proceed."), false);
                                    return 0;
                                })
                                .then(Commands.literal("confirm").executes(context -> {
                                    var runtime = BlindstoneRuntime.get(context.getSource().getServer());
                                    if (runtime == null) return 0;
                                    runtime.disable();
                                    runtime.diagnostics().activation(BlindstoneDiagnostics.Activation.DISABLED_BY_CONSOLE);
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Blindstone disabled by server console; real world delivery resumes on reconnect. Restart the server to re-enable protection."), false);
                                    return 1;
                                })))
                        .then(Commands.literal("report").executes(context -> {
                            var runtime = BlindstoneRuntime.get(context.getSource().getServer());
                            if (runtime == null) return 0;
                            var file = FabricLoader.getInstance().getConfigDir().resolve("blindstone-reports/latest.json");
                            try {
                                runtime.diagnostics().writeReport(file, runtime.configuration().current(), runtime.configuration().generation());
                                context.getSource().sendSuccess(() -> Component.literal("Saved local sanitized diagnostics to config/blindstone-reports/latest.json"), false);
                                return 1;
                            } catch (Exception failure) {
                                context.getSource().sendFailure(Component.literal("Blindstone report could not be saved; check filesystem permissions."));
                                return 0;
                            }
                        }))));
    }

    public static Component admissionRejection(boolean development, boolean hooksReady, boolean runtimeReady) {
        if (development) return Component.literal(ADMISSION_DENIED);
        return hooksReady && runtimeReady ? null : Component.literal(STARTUP_REFUSED);
    }

    public static boolean isServerConsole(CommandSourceStack source) {
        return ((CommandSourceAccess) source).blindstone$source() == source.getServer();
    }
}
