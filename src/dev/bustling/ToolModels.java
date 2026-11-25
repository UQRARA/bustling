package dev.bustling;

import dev.bustling.ToolMotion.Movement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.event.ModelEvent;

/** Models are baked as standalone assets; the vanilla item and block registries stay untouched. */
public final class ToolModels {
    private static final ModelResourceLocation AREA = model("region_editor_area");
    private static final ModelResourceLocation PATH = model("region_editor_path");
    private static final ModelResourceLocation DELETE = model("region_editor_delete");
    private static final ModelResourceLocation OBJECT = model("object_editor");
    private static final ModelResourceLocation COPY = model("copy_tool");
    private static final ModelResourceLocation COPIED = model("copy_tool_loaded");
    private static final ModelResourceLocation SETTINGS = model("client_settings");
    private static final ModelResourceLocation DEVICE = model("diffusion_device");
    private static final List<ModelResourceLocation> MODELS = List.of(AREA, PATH, DELETE, OBJECT, COPY, COPIED, SETTINGS, DEVICE);
    private static final Map<ModelResourceLocation, List<PartDefinition>> PARTS = Map.of(
            AREA, staffParts("region_editor_area"),
            PATH, staffParts("region_editor_path"),
            DELETE, staffParts("region_editor_delete"),
            OBJECT, List.of(part("object_editor_lens", Movement.OBJECT_LENS)),
            SETTINGS, List.of(part("settings_gear_1", Movement.LEFT_GEAR),
                    part("settings_gear_2", Movement.CENTER_GEAR), part("settings_gear_3", Movement.RIGHT_GEAR),
                    part("settings_lamps", Movement.LAMPS)),
            COPIED, List.of(part("copy_runes", Movement.RUNES)));
    private static final Map<ModelResourceLocation, BakedModel> assemblies = new HashMap<>();

    private static volatile Map<ResourceLocation, Set<Long>> devicePositions = Map.of();

    private ToolModels() {}

    private static ModelResourceLocation model(String name) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath("bustling", "bb/" + name));
    }

    public static void register(ModelEvent.RegisterAdditional event) {
        MODELS.forEach(event::register);
        PARTS.values().stream().flatMap(List::stream).map(PartDefinition::location).distinct().forEach(event::register);
    }

    static void bakingCompleted(ModelEvent.BakingCompleted event) {
        assemblies.clear();
        ClientSkinPacks.clearModels();
    }

    static void wrapDeviceCarrier(ModelEvent.ModifyBakingResult event) {
        var key = BlockModelShaper.stateToModelLocation(Blocks.SPAWNER.defaultBlockState());
        BakedModel diffuser = event.getModels().get(DEVICE);
        if (diffuser != null) event.getModels().computeIfPresent(key,
                (id, carrier) -> new DeviceCarrierModel(carrier, diffuser));
    }

    private record PartDefinition(ModelResourceLocation location, Movement movement) {}

    private static PartDefinition part(String id, Movement movement) {
        return new PartDefinition(model("motion/" + id), movement);
    }

    private static List<PartDefinition> staffParts(String id) {
        return List.of(part(id + "_core", Movement.CRYSTAL_CORE),
                part(id + "_shell", Movement.CRYSTAL_SHELL));
    }

    private static ModelResourceLocation itemLocation(ToolItems.Kind kind) {
        return switch (kind) {
            case REGION -> switch (ToolConfig.mode) {
                case AREA -> AREA;
                case PATH -> PATH;
                case DELETE -> DELETE;
            };
            case OBJECT -> OBJECT;
            case COPY -> CopyTool.hasClipboard() ? COPIED : COPY;
            case SETTINGS -> SETTINGS;
            case DEVICE -> DEVICE;
        };
    }

    public static BakedModel itemModel(ItemStack stack) {
        ToolItems.Kind kind = ToolItems.kind(stack);
        if (kind == null) return null;
        ModelResourceLocation location = itemLocation(kind);
        if (!PARTS.containsKey(location)) return loadedModel(location);
        return assemblies.computeIfAbsent(location, ToolModels::assemble);
    }

    private static BakedModel assemble(ModelResourceLocation location) {
        BakedModel body = loadedModel(location);
        if (body == null) return null;
        List<BakedModel> passes = new ArrayList<>();
        passes.add(body);
        for (PartDefinition definition : PARTS.get(location)) {
            BakedModel model = loadedModel(definition.location());
            if (model == null) return null;
            passes.add(new ToolMotion.Part(model, definition.movement()));
        }
        return new ToolMotion.Assembly(body, passes);
    }

    private static BakedModel loadedModel(ModelResourceLocation location) {
        var manager = Minecraft.getInstance().getModelManager();
        BakedModel model = manager.getModel(location);
        return model == manager.getMissingModel() ? null : model;
    }

    public static BakedModel deviceModel(BlockPos pos) {
        return rendersDevice(pos) ? loadedModel(DEVICE) : null;
    }

    static boolean rendersDevice(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        var level = mc.level;
        return level != null && (isDevice(level.dimension().location(), pos)
                || ClientDevicePlacements.contains(level, pos));
    }

    public static boolean isDevice(ResourceLocation dimension, BlockPos pos) {
        return devicePositions.getOrDefault(dimension, Collections.emptySet()).contains(pos.asLong());
    }

    static void refreshDevices() {
        Map<ResourceLocation,Set<Long>> next = new HashMap<>();
        for (CrowdData.Emitter e : CrowdData.EMITTERS) {
            if (!DiffuserData.isDiffuser(e) || !DiffuserData.valid(e)) continue;
            ResourceLocation dimension = ResourceLocation.tryParse(e.dim);
            if (dimension == null) continue;
            long pos = BlockPos.asLong((int)Math.floor(e.a[0]), (int)e.a[1] - 1, (int)Math.floor(e.a[2]));
            next.computeIfAbsent(dimension, key -> new HashSet<>()).add(pos);
        }
        publish(next);
    }

    static void clearDevices() { publish(Map.of()); }

    private static void publish(Map<ResourceLocation, Set<Long>> next) {
        var previous = devicePositions;
        if (previous.equals(next)) return;
        devicePositions = next;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        ResourceLocation dimension = mc.level.dimension().location();
        Set<Long> before = previous.getOrDefault(dimension, Set.of());
        Set<Long> after = next.getOrDefault(dimension, Set.of());
        List<Long> changed = new ArrayList<>();
        for (long pos : before) if (!after.contains(pos)) changed.add(pos);
        for (long pos : after) if (!before.contains(pos)) changed.add(pos);
        for (long key : changed) {
            BlockPos pos = BlockPos.of(key);
            if (mc.level.hasChunkAt(pos)) mc.levelRenderer.setSectionDirtyWithNeighbors(
                    pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        }
    }
}
