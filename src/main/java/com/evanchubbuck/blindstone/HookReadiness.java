package com.evanchubbuck.blindstone;

import com.evanchubbuck.blindstone.mixin.CommandSourceAccess;
import com.evanchubbuck.blindstone.mixin.ServerCommonListenerAccess;
import com.evanchubbuck.blindstone.mixin.TrackedEntityAccess;
import com.evanchubbuck.blindstone.runtime.PacketCompletion;

/** Forces mandatory mixin targets to transform before protected-world admission is possible. */
public final class HookReadiness {
    public interface RequiredHook { }
    public interface AdmissionHook { }
    private static volatile boolean ready;
    private HookReadiness() { }

    public static void verify() {
        ready = false;
        ClassLoader loader = HookReadiness.class.getClassLoader();
        requireTarget(loader, "net.minecraft.network.Connection", PacketCompletion.class);
        requireTarget(loader, "net.minecraft.server.network.ServerCommonPacketListenerImpl", ServerCommonListenerAccess.class);
        requireTarget(loader, "net.minecraft.server.level.ChunkMap$TrackedEntity", TrackedEntityAccess.class);
        requireTarget(loader, "net.minecraft.server.players.PlayerList", AdmissionHook.class);
        requireTarget(loader, "net.minecraft.world.level.chunk.LevelChunk", RequiredHook.class);
        requireTarget(loader, "net.minecraft.server.level.ChunkMap", RequiredHook.class);
        requireTarget(loader, "net.minecraft.server.network.PlayerChunkSender", RequiredHook.class);
        requireTarget(loader, "net.minecraft.commands.CommandSourceStack", CommandSourceAccess.class);
        ready = true;
    }
    public static boolean ready() { return ready; }
    private static void requireTarget(ClassLoader loader, String name, Class<?> marker) {
        try { requireMarker(Class.forName(name, false, loader), marker); }
        catch (ClassNotFoundException | LinkageError error) {
            throw new IllegalStateException("Blindstone mandatory hook could not load: " + name + ". Check the exact supported Minecraft/Fabric versions and mixin conflicts.", error);
        }
    }
    static void requireMarker(Class<?> target, Class<?> marker) {
        if (!marker.isAssignableFrom(target)) throw new IllegalStateException(
                "Blindstone mandatory transformed hook is missing: " + target.getName() + ". Startup stopped before player admission.");
    }
}
