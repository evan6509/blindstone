package com.evanchubbuck.blindstone.mixin;

import com.evanchubbuck.blindstone.runtime.BlindstoneRuntime;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.List;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Int2ObjectMap<?> entityMap;
    @Unique private long blindstone$trackingCursor;

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void blindstone$refreshBeforeTracking(CallbackInfo callback) {
        var runtime = BlindstoneRuntime.get(level.getServer());
        if (runtime == null || !level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
        runtime.refresh(level);
        // Vanilla normally refreshes pairing only when sections change. Visibility can change in place.
        var players = List.copyOf(level.players());
        if (players.isEmpty() || entityMap.isEmpty()) return;
        var entities = List.copyOf(entityMap.values());
        long slots = (long) entities.size() * players.size();
        long started = System.nanoTime();
        try {
            for (long visited = 0; visited < slots && runtime.tryTrackingStep(); visited++) {
                long slot = Math.floorMod(blindstone$trackingCursor++, slots);
                ((TrackedEntityAccess) entities.get((int) (slot / players.size())))
                        .blindstone$updatePlayer(players.get((int) (slot % players.size())));
            }
        } finally { runtime.recordTrackingWork(System.nanoTime() - started); }
    }
}
