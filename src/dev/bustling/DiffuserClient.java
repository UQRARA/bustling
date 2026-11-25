package dev.bustling;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

public final class DiffuserClient {
    private enum Ring {
        IDLE(.72f, .37f, .98f, .12f, .80f, .042),
        TARGET(.35f, 1f, .45f, .27f, 1f, .075),
        DELETE(1f, .26f, .22f, .23f, 1f, .075);

        final float red, green, blue, fill, outline;
        final double width;

        Ring(float red, float green, float blue, float fill, float outline, double width) {
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.fill = fill;
            this.outline = outline;
            this.width = width;
        }
    }

    private static final int SEGMENTS = 72;
    private static final double[] X = new double[SEGMENTS + 1], Z = new double[SEGMENTS + 1];
    static {
        for (int i = 0; i <= SEGMENTS; i++) {
            double angle = i * Math.PI * 2 / SEGMENTS;
            X[i] = Math.cos(angle);
            Z[i] = Math.sin(angle);
        }
    }

    private DiffuserClient() {}

    public static boolean openTarget(Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (player == null || mc.level == null || WorldHooks.openDiffuser == null
                || !ToolItems.isDrawingTool(player.getMainHandItem())) return false;
        String dimension = mc.level.dimension().location().toString();
        Vec3 eye = player.getEyePosition();
        Vec3 direction = player.getViewVector(1);
        Vec3 hit = ClientOnly.raycast(player, 70);
        CrowdData.Emitter target = target(dimension, eye, direction, hit);
        if (target == null) return false;
        EditTool.selectMode = false;
        EditTool.groundMode = false;
        WorldHooks.openDiffuser.accept(target.id);
        return true;
    }

    public static boolean openEditTarget(Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (player == null || mc.level == null || !ToolItems.isObject(player.getMainHandItem())) return false;
        String dim = mc.level.dimension().location().toString();
        CrowdData.Emitter found = target(dim, player.getEyePosition(), player.getViewVector(1),
                ClientOnly.raycast(player, 70));
        if (found == null) return false;
        EditTool.selectDiffuser(found);
        EditTool.selectMode = false;
        EditTool.groundMode = false;
        mc.setScreen(new EditScreen());
        return true;
    }

    static CrowdData.Emitter target(String dimension, Vec3 eye, Vec3 direction, Vec3 hit) {
        CrowdData.Emitter closest = null;
        double bestDepth = Double.POSITIVE_INFINITY, bestCenter = Double.POSITIVE_INFINITY;
        double reach = hit == null ? 70 : Math.min(70, (hit.x - eye.x) * direction.x
                + (hit.y - eye.y) * direction.y + (hit.z - eye.z) * direction.z + .75);
        for (CrowdData.Emitter e : CrowdData.EMITTERS) {
            if (!DiffuserData.isDiffuser(e) || !dimension.equals(e.dim) || e.a == null || e.a.length != 3) continue;
            double x = e.a[0], y = e.a[1], z = e.a[2];
            if (hit != null && hit.x == x && hit.z == z && hit.y >= y - 1 && hit.y <= y + .001) return e;
            if ((!e.enabled && !Boolean.TRUE.equals(e.redstoneControlled)) || Math.abs(direction.y) < 1e-6) continue;
            double depth = (y - .96 - eye.y) / direction.y;
            if (depth < 0 || depth > reach || depth > bestDepth + 1e-6) continue;
            double center = Math.hypot(eye.x + depth * direction.x - x, eye.z + depth * direction.z - z);
            if (center > e.diffuserRadius || Math.abs(depth - bestDepth) <= 1e-6 && center >= bestCenter) continue;
            closest = e;
            bestDepth = depth;
            bestCenter = center;
        }
        return closest;
    }

    public static void draw(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !ToolAim.holdingTool()
                || !RenderPolicy.allowToolRender()) return;
        String dimension = mc.level.dimension().location().toString();
        boolean drawingRegion = ToolItems.isRegion(mc.player.getMainHandItem());
        boolean deleting = drawingRegion && ToolConfig.mode == ToolConfig.Mode.DELETE;
        CrowdData.Emitter hovered = null;
        if (ToolItems.isDrawingTool(mc.player.getMainHandItem()) || ToolItems.isCopy(mc.player.getMainHandItem())) {
            hovered = ToolAim.fixedSelectionMode() ? EditTool.selected() : ToolAim.current(mc).diffuser();
        }
        Ring highlighted = deleting ? Ring.DELETE : Ring.TARGET;
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderType type = RenderType.debugQuads();
        VertexConsumer vertices = null;
        Matrix4f matrix = null;
        try {
            for (CrowdData.Emitter emitter : CrowdData.EMITTERS) {
                if ((emitter != hovered && !emitter.enabled && !Boolean.TRUE.equals(emitter.redstoneControlled))
                        || !DiffuserData.isDiffuser(emitter) || !dimension.equals(emitter.dim)
                        || emitter.a == null || emitter.a.length != 3) continue;
                double x = emitter.a[0], y = emitter.a[1] - .96, z = emitter.a[2], radius = emitter.diffuserRadius;
                if (Math.abs(camera.y - y) > 48 || Math.hypot(camera.x - x, camera.z - z) > radius + 24) continue;
                if (vertices == null) {
                    vertices = buffers.getBuffer(type);
                    pose.pushPose();
                    pose.translate(-camera.x, -camera.y, -camera.z);
                    matrix = pose.last().pose();
                }
                circle(vertices, matrix, camera, x, y, z, radius, emitter == hovered ? highlighted : Ring.IDLE);
            }
        } finally {
            if (vertices != null) {
                pose.popPose();
                buffers.endBatch(type);
            }
        }
    }

    private static void circle(VertexConsumer vertices, Matrix4f matrix, Vec3 camera,
                               double x, double y, double z, double radius, Ring ring) {
        double[] previous = {x + radius, y, z};
        double[] next = new double[3];
        for (int i = 1; i <= SEGMENTS; i++) {
            next[0] = x + radius * X[i];
            next[1] = y;
            next[2] = z + radius * Z[i];
            SelectionRenderer.quad(vertices, matrix, (float)x, (float)y, (float)z,
                    (float)next[0], (float)y, (float)next[2],
                    (float)previous[0], (float)y, (float)previous[2],
                    (float)previous[0], (float)y, (float)previous[2], ring.red, ring.green, ring.blue, ring.fill);
            SelectionRenderer.thickSeg(vertices, matrix, camera, previous, next, ring.red, ring.green, ring.blue, ring.outline, ring.width);
            double[] swap = previous;
            previous = next;
            next = swap;
        }
        SelectionRenderer.thickSeg(vertices, matrix, camera, new double[]{x, y + .46, z},
                new double[]{x + radius, y, z}, ring.red, ring.green, ring.blue, ring.outline, ring.width);
    }
}
