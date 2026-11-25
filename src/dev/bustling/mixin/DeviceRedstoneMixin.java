package dev.bustling.mixin;

import dev.bustling.ServerCrowdStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SpawnerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SpawnerBlock.class)
public abstract class DeviceRedstoneMixin extends BaseEntityBlock {
    protected DeviceRedstoneMixin(Properties properties) { super(properties); }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block source, BlockPos sourcePos, boolean moving) {
        super.neighborChanged(state, level, pos, source, sourcePos, moving);
        if (level instanceof ServerLevel server) ServerCrowdStore.refreshDevicePower(server, pos);
    }
}
