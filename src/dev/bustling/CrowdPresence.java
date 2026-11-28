package dev.bustling;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Tracks the view of live actors for population changes caused by weather and time of day. */
final class CrowdPresence {
    private static final Map<CrowdData.Emitter, Map<Integer, Seen>> VISIBLE = new IdentityHashMap<>();
    private static final double SPAWN_MARGIN = .35;
    private static ClientLevel level;
    private static Frustum frustum;
    private static long frame;

    private static final class Seen {
        AABB box;
        long frame;
    }

    private CrowdPresence() {}

    static void clear() {
        VISIBLE.clear();
        frustum = null;
        level = null;
    }

    static void begin(RenderLevelStageEvent event) {
        ClientLevel current = Minecraft.getInstance().level;
        if (level != current) {
            clear();
            level = current;
        }
        frustum = event.getFrustum();
        if (current != null) CrowdNavigation.beginFrame(current.getGameTime(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        frame++;
        if (!WorldSystem.environmentEnabled) {
            VISIBLE.clear();
            return;
        }
        VISIBLE.values().removeIf(actors -> {
            actors.values().removeIf(actor -> actor.frame < frame - 1);
            return actors.isEmpty();
        });
    }

    static boolean visible(Frustum camera, AABB bounds, CrowdData.Emitter emitter, CrowdSampler.Ghost actor) {
        float swim = CrowdWater.swimBlend(actor.move());
        if (swim > .01f) {
            double reach = .4 * actor.size() * swim;
            bounds = bounds.inflate(reach, 0, reach);
        }
        boolean visible = camera.isVisible(bounds);
        if (visible && WorldSystem.environmentEnabled) {
            Map<Integer, Seen> actors = VISIBLE.computeIfAbsent(emitter, ignored -> new HashMap<>());
            Seen seen = actors.computeIfAbsent(actor.idx(), ignored -> new Seen());
            seen.box = bounds;
            seen.frame = frame;
        } else if (!visible) {
            Map<Integer, Seen> actors = VISIBLE.get(emitter);
            if (actors != null) actors.remove(actor.idx());
        }
        return visible;
    }

    static boolean outsideSpawnView(double x, double y, double z, float size) {
        if (frustum == null) return false;
        double halfWidth = .45 * (size / 1.875) + SPAWN_MARGIN;
        return !frustum.isVisible(new AABB(x - halfWidth, y - SPAWN_MARGIN, z - halfWidth,
                x + halfWidth, y + size + SPAWN_MARGIN, z + halfWidth));
    }

    static boolean keep(CrowdData.Emitter emitter, int index) {
        Map<Integer, Seen> actors = VISIBLE.get(emitter);
        Seen seen = actors == null ? null : actors.get(index);
        return seen != null && frustum != null && level != null && frustum.isVisible(seen.box) && level.hasChunkAt(BlockPos.containing(
                (seen.box.minX + seen.box.maxX) / 2, seen.box.minY, (seen.box.minZ + seen.box.maxZ) / 2));
    }
}
