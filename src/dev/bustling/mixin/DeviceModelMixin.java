package dev.bustling.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.bustling.ToolModels;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BlockRenderDispatcher.class)
public abstract class DeviceModelMixin {
    @Redirect(method = "renderBatched(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;Lnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V",
            require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;getBlockModel(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/client/resources/model/BakedModel;"))
    private BakedModel bustling$useDeviceModel(BlockRenderDispatcher dispatcher, BlockState queried, BlockState state,
                                                BlockPos pos, BlockAndTintGetter level, PoseStack pose,
                                                VertexConsumer vertices, boolean sides, RandomSource random,
                                                ModelData data, RenderType layer) {
        BakedModel custom = state.is(Blocks.SPAWNER) ? ToolModels.deviceModel(pos) : null;
        return custom != null ? custom : dispatcher.getBlockModel(queried);
    }
}
