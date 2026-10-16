package com.evanchubbuck.blindstone;

import com.evanchubbuck.blindstone.diagnostics.BlindstoneDiagnostics;
import com.evanchubbuck.blindstone.runtime.BlindstoneRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.LoggerFactory;

public final class BlindstoneMod implements ModInitializer {
    public static final String ADMISSION_DENIED = "Blindstone development prototype: lighting and entity policies are incomplete; player admission is disabled.";
    public static final String STARTUP_REFUSED = "Blindstone protection cannot activate: release gates for filtered lighting, entity data, gameplay and performance are incomplete. "
            + "For isolated development tests only, set -Dblindstone.development=true; player admission remains disabled.";

    @Override
    public void onInitialize() {
        requireDevelopmentMode(Boolean.getBoolean("blindstone.development"));
        LoggerFactory.getLogger("blindstone").warn(ADMISSION_DENIED);
        ServerLifecycleEvents.SERVER_STARTED.register(BlindstoneRuntime::start);
        ServerLifecycleEvents.SERVER_STOPPED.register(BlindstoneRuntime::stop);
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            var runtime = BlindstoneRuntime.get(server);
            if (runtime != null) runtime.beginTick();
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
                                    : runtime.diagnostics().verify() + " Live client/gameplay/performance evidence remains absent."), false);
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
                                context.getSource().sendSuccess(() -> Component.literal("Blindstone configuration validated and reloaded; player admission remains CLOSED."), true);
                                return 1;
                            } catch (Exception failure) {
                                context.getSource().sendFailure(Component.literal("Reload rejected; the previous configuration remains active. " + failure.getMessage()));
                                return 0;
                            }
                        }))
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

    public static void requireDevelopmentMode(boolean development) {
        if (!development) throw new IllegalStateException(STARTUP_REFUSED);
    }
}
