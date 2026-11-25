package dev.bustling;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class PasteEffects {
    private static final ResourceLocation FONT = ResourceLocation.withDefaultNamespace("textures/font/ascii_sga.png");
    private static final int MAX_BURSTS = 3;
    private static final int GLYPHS = 15;
    private static final String LETTERS = "SYNCPFLOWBR";
    private static final double GLYPH_DELAY = .025;
    private static final long FLASH_HOLD = 90_000_000L;
    private static final long FLASH_END = 280_000_000L;
    private static final Deque<Burst> bursts = new ArrayDeque<>(MAX_BURSTS);
    private static ClientLevel level;
    private static Burst latest;

    private PasteEffects() {}

    /** Triggered only after the clipboard settings have been saved on the selected group. */
    public static void committed(Player player, Vec3 hit, CrowdData.Emitter emitter) {
        Minecraft mc = Minecraft.getInstance();
        if (player != mc.player || mc.level == null) return;
        if (level != mc.level) clear();
        level = mc.level;

        Vec3 destination = landingPoint(emitter, hit).add(0, .2, 0);
        Vec3 origin = bookOrigin(mc, player, destination);
        if (bursts.size() == MAX_BURSTS) bursts.removeFirst();
        latest = new Burst(origin, destination);
        bursts.addLast(latest);
    }

    static void clear() {
        bursts.clear();
        latest = null;
        level = null;
    }

    static float whiteFlash() {
        if (latest == null || Minecraft.getInstance().level != level) return 0;
        if (latest.firstFrame == 0) return 1;
        long age = System.nanoTime() - latest.firstFrame;
        if (age <= FLASH_HOLD) return 1;
        if (age >= FLASH_END) return 0;
        float remaining = (float) (FLASH_END - age) / (FLASH_END - FLASH_HOLD);
        return remaining * remaining;
    }

    static void draw(RenderLevelStageEvent event) {
        if (bursts.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) {
            clear();
            return;
        }
        if (!RenderPolicy.allowToolRender()) return;
        long now = System.nanoTime();
        bursts.removeIf(burst -> burst.expired(now));
        if (bursts.isEmpty()) {
            clear();
            return;
        }
        RenderType type = RenderType.entityTranslucentEmissive(FONT);
        var buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vertices = buffers.getBuffer(type);
        for (Burst burst : bursts) burst.draw(event, vertices, now);
        buffers.endBatch(type);
    }

    private static Vec3 bookOrigin(Minecraft mc, Player player, Vec3 destination) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 look = player.getLookAngle();
        double yaw = Math.toRadians(player.getYRot());
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 up = right.cross(look);
        double sign = player.getMainArm() == HumanoidArm.LEFT ? -1 : 1;
        if (camera.isDetached()) {
            return player.getEyePosition().add(look.scale(.38)).add(right.scale(sign * .35)).add(up.scale(-.64));
        }
        double reach = Math.clamp(camera.getPosition().distanceTo(destination) / 1.4, .34, 1);
        double fov = Math.tan(Math.toRadians(mc.options.fov().get() * .5)) / Math.tan(Math.toRadians(35));
        return camera.getPosition().add(look.scale(.98 * reach))
                .add(right.scale(sign * .39 * reach * fov)).add(up.scale(-.31 * reach * fov));
    }

    private static Vec3 landingPoint(CrowdData.Emitter emitter, Vec3 hit) {
        if (emitter.isLine()) return closestEdge(emitter.path, hit, false);
        if (CrowdBounds.inside(emitter, hit.x, hit.z, 0)) return hit;
        if (CrowdBounds.polygon(emitter)) return closestEdge(emitter.polygon, hit, true);
        return new Vec3(Math.clamp(hit.x, Math.min(emitter.a[0], emitter.b[0]), Math.max(emitter.a[0], emitter.b[0])),
                Math.min(emitter.a[1], emitter.b[1]),
                Math.clamp(hit.z, Math.min(emitter.a[2], emitter.b[2]), Math.max(emitter.a[2], emitter.b[2])));
    }

    private static Vec3 closestEdge(List<double[]> points, Vec3 hit, boolean closed) {
        if (points == null || points.isEmpty()) return hit;
        Vec3 closest = point(points.getFirst());
        double best = closest.distanceToSqr(hit);
        for (int i = 0, segments = closed ? points.size() : points.size() - 1; i < segments; i++) {
            Vec3 a = point(points.get(i));
            Vec3 b = point(points.get((i + 1) % points.size()));
            Vec3 segment = b.subtract(a);
            double length = segment.lengthSqr();
            if (length < 1.0e-8) continue;
            double fraction = Math.clamp(hit.subtract(a).dot(segment) / length, 0, 1);
            Vec3 candidate = a.lerp(b, fraction);
            double distance = candidate.distanceToSqr(hit);
            if (distance < best) {
                closest = candidate;
                best = distance;
            }
        }
        return closest;
    }

    private static Vec3 point(double[] location) {
        return new Vec3(location[0], location[1], location[2]);
    }

    private static final class Burst {
        private final Vec3 origin, target, side;
        private final double duration, height;
        private long firstFrame;

        private Burst(Vec3 origin, Vec3 target) {
            this.origin = origin;
            this.target = target;
            this.side = target.subtract(origin).cross(new Vec3(0, 1, 0)).normalize();
            double distance = target.distanceTo(origin);
            this.duration = Math.clamp(.46 + distance * .012, .48, 1.15);
            this.height = Math.clamp(distance * .065, .16, .9);
        }

        private boolean expired(long now) {
            return firstFrame != 0 && (now - firstFrame) / 1.0e9 > duration + GLYPHS * GLYPH_DELAY;
        }

        private void draw(RenderLevelStageEvent event, VertexConsumer vertices, long now) {
            if (firstFrame == 0) firstFrame = now;
            double age = (now - firstFrame) / 1.0e9;
            PoseStack pose = event.getPoseStack();
            Camera camera = event.getCamera();
            Vec3 cameraPosition = camera.getPosition();
            for (int i = 0; i < GLYPHS; i++) {
                double travel = (age - i * GLYPH_DELAY) / duration;
                if (travel < 0 || travel >= 1) continue;
                double arc = Math.sin(travel * Math.PI);
                double wave = Math.sin(travel * 8 + i * 2.39);
                Vec3 p = origin.lerp(target, travel)
                        .add(side.scale(arc * wave * (.14 + height * .21)))
                        .add(0, arc * height * (.8 + (i % 4) * .14), 0);
                pose.pushPose();
                pose.translate(p.x - cameraPosition.x, p.y - cameraPosition.y, p.z - cameraPosition.z);
                pose.mulPose(camera.rotation());
                float size = (float) (.048 + height * .045 + arc * .016);
                float violet = (float) Math.clamp(travel * 1.8, 0, 1);
                float alpha = (float) Math.min(1, (1 - travel) * 5);
                int glyph = LETTERS.charAt(i % LETTERS.length());
                float u = (glyph % 16) / 16f, v = (glyph / 16) / 16f;
                var matrix = pose.last().pose();
                add(vertices, matrix, -size, -size, u, v + .0625f, violet, alpha);
                add(vertices, matrix, size, -size, u + .0625f, v + .0625f, violet, alpha);
                add(vertices, matrix, size, size, u + .0625f, v, violet, alpha);
                add(vertices, matrix, -size, size, u, v, violet, alpha);
                pose.popPose();
            }
        }

        private static void add(VertexConsumer vertices, org.joml.Matrix4f matrix, float x, float y,
                                float u, float v, float violet, float alpha) {
            vertices.addVertex(matrix, x, y, 0).setColor(1 - .24f * violet, 1 - .51f * violet, 1, alpha)
                    .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
                    .setNormal(0, 0, 1);
        }
    }
}
