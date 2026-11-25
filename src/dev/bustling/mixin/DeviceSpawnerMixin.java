package dev.bustling.mixin;

import dev.bustling.DeviceBlocks;
import dev.bustling.ServerCrowdStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpawnerBlockEntity.class)
public abstract class DeviceSpawnerMixin {
    @Unique private boolean bustling$classified;
    @Unique private boolean bustling$device;

    @Inject(method = "serverTick", at = @At("HEAD"), cancellable = true, require = 1)
    private static void bustling$skipDeviceTick(Level level, BlockPos pos, BlockState state,
                                                 SpawnerBlockEntity spawner, CallbackInfo callback) {
        DeviceSpawnerMixin self = (DeviceSpawnerMixin)(Object)spawner;
        if (!self.bustling$classified) {
            self.bustling$classified = true;
            ServerLevel server = (ServerLevel)level;
            self.bustling$device = ServerCrowdStore.hasDevice(server, pos);
            if (self.bustling$device) {
                DeviceBlocks.disableSpawner(server, pos, spawner);
                ServerCrowdStore.refreshDevicePower(server, pos);
            }
        }
        if (self.bustling$device) callback.cancel();
    }
}
