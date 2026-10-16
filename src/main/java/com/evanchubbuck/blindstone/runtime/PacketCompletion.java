package com.evanchubbuck.blindstone.runtime;

import io.netty.channel.ChannelFutureListener;

/** Completes intentionally suppressed/deferred transport frames without generating network traffic. */
public interface PacketCompletion {
    void blindstone$complete(ChannelFutureListener callback, Throwable failure);
}
