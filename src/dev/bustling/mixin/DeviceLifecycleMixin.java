package dev.bustling.mixin;

import dev.bustling.DeviceBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class DeviceLifecycleMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"), require = 1)
    private void bustling$trackSpawnerReplacement(BlockPos pos, BlockState next, boolean moving,
                                                  CallbackInfoReturnable<BlockState> callback) {
        BlockState previous = callback.getReturnValue();
        if (previous != null && !next.is(Blocks.SPAWNER) && previous.is(Blocks.SPAWNER)
                && ((LevelChunk)(Object)this).getLevel() instanceof ServerLevel level) {
            DeviceBlocks.replaced(level, pos);
        }
    }
}
