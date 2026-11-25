package dev.bustling;

import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import net.neoforged.neoforge.common.util.TriState;

/** Select the diffuser through the block model data path used by chunk renderers. */
final class DeviceCarrierModel extends BakedModelWrapper<BakedModel> {
    private static final ModelProperty<Boolean> DEVICE = new ModelProperty<>();
    private final BakedModel diffuser;

    DeviceCarrierModel(BakedModel spawner, BakedModel diffuser) {
        super(spawner);
        this.diffuser = diffuser;
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
        ModelData original = originalModel.getModelData(level, pos, state, data);
        return ToolModels.rendersDevice(pos) ? original.derive().with(DEVICE, true).build() : original;
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random,
                                    ModelData data, RenderType layer) {
        if (Boolean.TRUE.equals(data.get(DEVICE))) return diffuser.getQuads(state, side, random, ModelData.EMPTY, null);
        return originalModel.getQuads(state, side, random, data, layer);
    }

    @Override
    public TriState useAmbientOcclusion(BlockState state, ModelData data, RenderType layer) {
        return Boolean.TRUE.equals(data.get(DEVICE)) ? TriState.FALSE : super.useAmbientOcclusion(state, data, layer);
    }
}
