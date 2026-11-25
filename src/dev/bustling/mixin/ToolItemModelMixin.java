package dev.bustling.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.bustling.ToolModels;
import dev.bustling.ToolMotion;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemRenderer.class)
public abstract class ToolItemModelMixin {
    @Shadow public abstract void renderModelLists(BakedModel model, ItemStack stack, int light, int overlay,
                                                   PoseStack pose, VertexConsumer vertices);

    @Inject(method = "getModel", at = @At("HEAD"), cancellable = true, require = 1)
    private void bustling$useMarkedToolModel(ItemStack stack, Level level, LivingEntity entity, int seed,
                                           CallbackInfoReturnable<BakedModel> callback) {
        BakedModel model = ToolModels.itemModel(stack);
        if (model != null) callback.setReturnValue(model);
    }

    @Inject(method = "renderModelLists", at = @At("HEAD"), cancellable = true, require = 1)
    private void bustling$drawAnimatedPart(BakedModel model, ItemStack stack, int light, int overlay,
                                           PoseStack pose, VertexConsumer vertices, CallbackInfo callback) {
        if (!(model instanceof ToolMotion.Part part)) return;
        callback.cancel();
        pose.pushPose();
        try {
            part.applyMotion(pose);
            if (part.pulses()) {
                renderModelLists(part.source(), stack, LightTexture.FULL_BRIGHT, overlay, pose, part.litVertices(vertices));
            } else {
                renderModelLists(part.source(), stack, light, overlay, pose, vertices);
            }
        } finally {
            pose.popPose();
        }
    }
}
