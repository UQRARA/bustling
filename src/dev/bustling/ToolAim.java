package dev.bustling;

import net.minecraft.client.Minecraft;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Read-only target for the crosshair preview. Committed selections belong to the edit panel. */
public final class ToolAim {
    private static final double REACH = 70;
    private static Preview preview;

    private ToolAim() {}

    record Preview(Vec3 hit, CrowdData.Emitter diffuser, CrowdData.Emitter shape) {
        CrowdData.Emitter target() {
            return diffuser == null ? shape : diffuser;
        }
    }

    static void beginFrame(RenderFrameEvent.Pre event) {
        preview = null;
    }

    static Preview current(Minecraft mc) {
        if (preview == null) {
            Player player = mc.player;
            String dimension = mc.level.dimension().location().toString();
            Vec3 solid = ClientOnly.raycast(player, REACH);
            CrowdData.Emitter diffuser = DiffuserClient.target(dimension, player.getEyePosition(),
                    player.getViewVector(1), solid);
            boolean deleting = selectionMode() == ToolConfig.Mode.DELETE;
            Vec3 hit = deleting && diffuser != null ? solid : layoutRay(player, REACH);
            CrowdData.Emitter shape = diffuser == null ? shapeAt(dimension, hit, deleting) : null;
            preview = new Preview(hit, diffuser, shape);
        }
        return preview;
    }

    public static boolean previewEnabled(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return false;
        return (WorldSystem.enabled || holdingTool()) && RenderPolicy.allowToolRender();
    }

    public static boolean holdingTool() {
        Player player = Minecraft.getInstance().player;
        return player != null && (ToolItems.kind(player.getMainHandItem()) != null
                || ToolItems.kind(player.getOffhandItem()) != null);
    }

    public static ToolConfig.Mode selectionMode() {
        Minecraft mc = Minecraft.getInstance();
        return isDrawing(mc) && ToolItems.isRegion(mc.player.getMainHandItem())
                ? ToolConfig.mode : ToolConfig.Mode.AREA;
    }

    public static Vec3 previewRay(Player player, double reach) {
        Minecraft mc = Minecraft.getInstance();
        if (player == mc.player && reach == REACH && isDrawing(mc)) return current(mc).hit();
        return layoutRay(player, reach);
    }

    public static Vec3 placementRay(Player player, double reach) {
        Minecraft mc = Minecraft.getInstance();
        if (player == null || mc.level == null) return null;
        if (ToolConfig.mode == ToolConfig.Mode.DELETE) {
            Vec3 solid = ClientOnly.raycast(player, reach);
            if (solid != null && DiffuserClient.target(mc.level.dimension().location().toString(),
                    player.getEyePosition(), player.getViewVector(1), solid) != null) return solid;
        }
        return layoutRay(player, reach);
    }

    public static Vec3 layoutRay(Player player, double reach) {
        var level = Minecraft.getInstance().level;
        if (player == null || level == null) return null;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1).scale(reach));
        var hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.WATER, player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;

        var pos = hit.getBlockPos();
        double x = pos.getX() + .5, z = pos.getZ() + .5;
        double y = hit.getLocation().y;
        if (level.getFluidState(pos).is(FluidTags.WATER)) {
            double surface = CrowdWater.surface(level, x, pos.getY(), z);
            if (Double.isFinite(surface)) y = Math.max(y, surface);
        }
        return new Vec3(x, y, z);
    }

    public static CrowdData.Emitter highlightShape(double[] point) {
        Minecraft mc = Minecraft.getInstance();
        if (!isDrawing(mc)) return PolygonTool.pickAt(point);
        return fixedSelectionMode() ? null : current(mc).shape();
    }

    public static CrowdData.Emitter deletionHighlight() {
        Minecraft mc = Minecraft.getInstance();
        return isDrawing(mc) ? current(mc).shape() : null;
    }

    public static CrowdData.Emitter editingSelection() {
        return fixedSelectionMode() ? EditTool.selected() : null;
    }

    public static CrowdData.Emitter editShapeAt(Vec3 point) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        String dimension = mc.level.dimension().location().toString();
        CrowdData.Emitter shape = shapeAt(dimension, point, false);
        return shape == null ? shapeAt(dimension, point, true) : shape;
    }

    public static CrowdData.Emitter nearbyShapeAt(Vec3 point) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? null : shapeAt(mc.level.dimension().location().toString(), point, true);
    }

    static boolean fixedSelectionMode() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && ToolItems.isObject(mc.player.getMainHandItem())
                && (EditTool.groundMode || EditTool.selectMode);
    }

    private static boolean isDrawing(Minecraft mc) {
        return mc.level != null && mc.player != null && ToolItems.isDrawingTool(mc.player.getMainHandItem());
    }

    private static CrowdData.Emitter shapeAt(String dimension, Vec3 point, boolean nearby) {
        if (point == null) return null;
        CrowdData.Emitter closest = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (CrowdData.Emitter emitter : CrowdData.EMITTERS) {
            if (!dimension.equals(emitter.dim) || DiffuserData.isDiffuser(emitter)) continue;
            if (!nearby && (!emitter.enabled || !CrowdBounds.inside(emitter, point.x, point.z, 0))) continue;
            double distance = DeleteTool.distance(emitter, point);
            double limit = !nearby && emitter.isLine()
                    ? Math.hypot(DeleteTool.HIT_RADIUS, Math.max(.5, emitter.width) / 2) : DeleteTool.HIT_RADIUS;
            if (!(distance <= limit && distance < bestDistance)) continue;
            bestDistance = distance;
            closest = emitter;
        }
        return closest;
    }
}
