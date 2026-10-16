package com.evanchubbuck.blindstone.mixin;

import com.evanchubbuck.blindstone.runtime.BlindstoneRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Covers listener sends, direct Connection sends, and pending actions before the Netty dispatch. */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Shadow private Channel channel;
    @Shadow public abstract PacketListener getPacketListener();
    @Shadow public abstract void disconnect(Component reason);

    @WrapMethod(method = "sendPacket")
    private void blindstone$filterBeforeChannelDispatch(Packet<?> packet, ChannelFutureListener callback,
            boolean flush, Operation<Void> original) {
        if (!(getPacketListener() instanceof ServerGamePacketListenerImpl game) || transportSafe(packet)) {
            original.call(packet, callback, flush);
            return;
        }
        var server = game.player.level().getServer();
        // No mutable world content or viewer state may be consulted from a channel/worker thread.
        if (!server.isSameThread()) {
            disconnect(Component.literal("Blindstone rejected a world packet sent outside the owning server thread."));
            complete(callback, new IllegalStateException("Unsafe asynchronous world packet"));
            return;
        }
        try {
            var runtime = BlindstoneRuntime.get(server);
            if (runtime == null) throw new IllegalStateException("Blindstone runtime is not ready");
            Packet<?> safe = runtime.filter(game.player, packet);
            if (safe == null) complete(callback, null);
            else original.call(safe, callback, flush);
        } catch (RuntimeException failure) {
            disconnect(Component.literal("Blindstone stopped an unsafe packet; protection could not maintain this connection."));
            complete(callback, failure);
        }
    }

    private static boolean transportSafe(Packet<?> packet) {
        return packet instanceof ClientboundDisconnectPacket || packet instanceof ClientboundKeepAlivePacket
                || packet instanceof ClientboundPingPacket || packet instanceof ClientboundSystemChatPacket
                || packet instanceof ClientboundPlayerChatPacket;
    }

    private void complete(ChannelFutureListener callback, Throwable failure) {
        if (callback == null) return;
        channel.eventLoop().execute(() -> {
            try { callback.operationComplete(failure == null ? channel.newSucceededFuture() : channel.newFailedFuture(failure)); }
            catch (Exception exception) { disconnect(Component.literal("Blindstone packet completion failed.")); }
        });
    }
}
