package dev.bustling.mixin;

import dev.bustling.ClientDevicePlacements;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientDevicePlacementMixin {
    @Inject(method = "setServerVerifiedBlockState", at = @At("RETURN"), require = 1)
    private void bustling$readConfirmedCarrier(BlockPos pos, BlockState state, int flags, CallbackInfo callback) {
        ClientDevicePlacements.confirmed((ClientLevel) (Object) this, pos, state);
    }
}
