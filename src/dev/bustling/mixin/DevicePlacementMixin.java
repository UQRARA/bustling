package dev.bustling.mixin;

import dev.bustling.DeviceBlocks;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ItemStack.class)
public abstract class DevicePlacementMixin {
    @Redirect(method = "useOn", require = 1, at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/common/CommonHooks;onPlaceItemIntoWorld(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult bustling$placeVanillaDevice(UseOnContext context) {
        return DeviceBlocks.place(context);
    }
}
