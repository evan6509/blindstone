package com.evanchubbuck.blindstone.mixin;

import com.evanchubbuck.blindstone.runtime.BlindstoneRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void blindstone$invalidateGeometry(BlockPos pos, BlockState state, int flags,
            CallbackInfoReturnable<BlockState> callback) {
        var chunk = (LevelChunk) (Object) this;
        if (callback.getReturnValue() == null || !(chunk.getLevel() instanceof ServerLevel level)) return;
        var runtime = BlindstoneRuntime.get(level.getServer());
        if (runtime != null) runtime.geometryChanged(level, pos);
    }
}
