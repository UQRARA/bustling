package dev.bustling;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

public final class WandLaser {
    private static final int MAX_SHOTS = 5;
    private static final long TICK_NANOS = 50_000_000L;
    private static final long LIFE_NANOS = 6 * TICK_NANOS;
    private static final double BEAM_HALF_WIDTH = 0.03;
    private static final Deque<Shot> shots = new ArrayDeque<>(MAX_SHOTS);
    private static ClientLevel shotLevel;

    private WandLaser() {}

    public static void placed(Vec3 point, ToolConfig.Mode mode) {
        if (!WorldSystem.enabled || mode == ToolConfig.Mode.DELETE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !ToolItems.isRegion(mc.player.getMainHandItem())) return;

        if (shotLevel != mc.level) clear();
        shotLevel = mc.level;
        Vec3 target = point.add(0, 0.045, 0);
        Vec3 start = crystalOrigin(mc.player, mc.gameRenderer.getMainCamera(), target, mc.options.fov().get());
        if (shots.size() == MAX_SHOTS) shots.removeFirst();
        shots.addLast(new Shot(start, target));
        var random = mc.level.random;
        for (int spark = 0; spark < 5; spark++) {
            mc.level.addParticle(ParticleTypes.END_ROD, target.x, target.y, target.z,
                    (random.nextDouble() - 0.5) * 0.16, random.nextDouble() * 0.09,
                    (random.nextDouble() - 0.5) * 0.16);
        }
    }

    public static void placeFromInput(Player player, InputEvent.InteractionKeyMappingTriggered event) {
        Shot previous = shots.peekLast();
        ClientOnly.place(player);
        if (!shots.isEmpty() && previous != shots.peekLast()) event.setSwingHand(false);
    }

    public static void clear() {
        shots.clear();
        shotLevel = null;
    }

    public static void draw(RenderLevelStageEvent event) {
        if (shots.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Minecraft mc = Minecraft.getInstance();
        if (!WorldSystem.enabled || mc.level != shotLevel || mc.player == null
                || !ToolItems.isRegion(mc.player.getMainHandItem())) {
            clear();
            return;
        }

        long now = System.nanoTime();
        while (!shots.isEmpty() && shots.peekFirst().expired(now)) shots.removeFirst();
        if (shots.isEmpty() || !RenderPolicy.allowRender()) return;

        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        RenderType type = RenderType.lightning();
        var buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vertices = buffers.getBuffer(type);
        Matrix4f matrix = pose.last().pose();
        for (Shot shot : shots) {
            double ticks = shot.ageInTicks(now);
            double length = Math.pow(0.6, Math.max(0, ticks - 0.3));
            float alpha = (float) Math.min(1, (6 - ticks) / 3);
            Vec3 end = shot.target.subtract(camera);
            Vec3 start = shot.target.lerp(shot.origin, length).subtract(camera);
            drawBeam(vertices, matrix, start, end, length * BEAM_HALF_WIDTH, alpha);
        }
        buffers.endBatch(type);
    }

    private static Vec3 crystalOrigin(LocalPlayer player, Camera camera, Vec3 target, int worldFov) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double yaw = Math.toRadians(player.getYRot());
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 up = right.cross(look);
        double hand = player.getMainArm() == HumanoidArm.LEFT ? -1 : 1;
        double reach = Math.min(1, eye.distanceTo(target) / 1.15);
        if (camera.isDetached()) {
            return eye.add(look.scale(reach * 0.38)).add(right.scale(reach * hand * 0.37))
                    .add(up.scale(reach * -0.66));
        }
        // The held item is projected at 70 degrees even when the player's world FOV changes.
        double projectionScale = Math.tan(Math.toRadians(worldFov * 0.5)) / Math.tan(Math.toRadians(35));
        return camera.getPosition().add(look.scale(reach * 0.97))
                .add(right.scale(reach * hand * 0.60 * projectionScale)).add(up.scale(reach * -0.116 * projectionScale));
    }

    private static void drawBeam(VertexConsumer vertices, Matrix4f matrix, Vec3 start, Vec3 end,
                                 double halfWidth, float alpha) {
        Vec3 axis = end.subtract(start).normalize();
        if (axis.lengthSqr() == 0) return;
        Vec3 reference = Math.abs(axis.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 across = axis.cross(reference).normalize().scale(halfWidth);
        Vec3 up = axis.cross(across);
        Vec3[] corners = {across.add(up), up.subtract(across), across.add(up).scale(-1), across.subtract(up)};
        for (int i = 0; i < corners.length; i++) {
            Vec3 a = corners[i];
            Vec3 b = corners[(i + 1) % corners.length];
            vertex(vertices, matrix, start.add(a), alpha);
            vertex(vertices, matrix, start.add(b), alpha);
            vertex(vertices, matrix, end.add(b), alpha);
            vertex(vertices, matrix, end.add(a), alpha);
        }
    }

    private static void vertex(VertexConsumer vertices, Matrix4f matrix, Vec3 point, float alpha) {
        vertices.addVertex(matrix, (float) point.x, (float) point.y, (float) point.z).setColor(1, 1, 1, alpha);
    }

    private static final class Shot {
        private final Vec3 origin;
        private final Vec3 target;
        private long firstFrame = Long.MIN_VALUE;

        private Shot(Vec3 origin, Vec3 target) {
            this.origin = origin;
            this.target = target;
        }

        private boolean expired(long now) {
            return firstFrame != Long.MIN_VALUE && now - firstFrame >= LIFE_NANOS;
        }

        private double ageInTicks(long now) {
            // Building a new path can delay its first frame. Always show the full beam there.
            if (firstFrame == Long.MIN_VALUE) firstFrame = now;
            return (double) (now - firstFrame) / TICK_NANOS;
        }
    }
}
