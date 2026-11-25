package dev.bustling;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Keeps crowd arrivals and departures outside the current camera view. */
final class CrowdPresence {
    private static final Map<String, Group> GROUPS = new HashMap<>();
    private static final Map<CrowdData.Emitter, Group> LIVE = new IdentityHashMap<>();
    private static final Map<CrowdData.Emitter, Group> RETIRING = new IdentityHashMap<>();
    private static final List<CrowdData.Emitter> RENDER_ORDER = new ArrayList<>();
    private static final double[] POSITION = new double[3];
    private static final double SPAWN_MARGIN = 0.35;
    private static ClientLevel level;
    private static Frustum frustum;
    private static long frame;

    private static final class Group {
        final Map<Integer, Pose> poses = new HashMap<>();
        final CrowdData.Emitter replay = new CrowdData.Emitter();
        BitSet ready = new BitSet();
        BitSet previous = new BitSet();
        long appearanceFrame;

        void beginFrame() {
            BitSet older = previous;
            previous = ready;
            ready = older;
            ready.clear();
        }
    }

    private static final class Pose {
        CrowdSampler.Ghost ghost;
        AABB box;
        long submitted;
        double restingSince = Double.NaN;
    }

    private CrowdPresence() {}

    static void clear() {
        RENDER_ORDER.clear();
        GROUPS.clear();
        LIVE.clear();
        RETIRING.clear();
        frustum = null;
        level = null;
    }

    static List<CrowdData.Emitter> begin(RenderLevelStageEvent event) {
        ClientLevel current = Minecraft.getInstance().level;
        if (level != current) {
            clear();
            level = current;
        }
        frustum = event.getFrustum();
        frame++;
        LIVE.clear();
        RETIRING.clear();
        RENDER_ORDER.clear();
        RENDER_ORDER.addAll(CrowdData.EMITTERS);
        // Draw the current groups first. The replay pass only fills in actors no longer submitted.
        GROUPS.values().removeIf(group -> group.poses.isEmpty() && group.ready.isEmpty());
        for (Group group : GROUPS.values()) {
            group.beginFrame();
            if (!group.poses.isEmpty()) {
                RETIRING.put(group.replay, group);
                RENDER_ORDER.add(group.replay);
            }
        }
        return RENDER_ORDER;
    }

    static List<CrowdData.Emitter> renderOrder() {
        return RENDER_ORDER;
    }

    private static Group group(CrowdData.Emitter emitter) {
        return LIVE.computeIfAbsent(emitter, source -> GROUPS.computeIfAbsent(source.id, id -> new Group()));
    }

    static boolean visible(Frustum camera, AABB bounds, CrowdData.Emitter emitter, CrowdSampler.Ghost actor) {
        boolean visible = camera.isVisible(bounds);
        if (RETIRING.containsKey(emitter)) return visible;
        Group group = group(emitter);
        int index = actor.idx();
        if (!visible) {
            group.poses.remove(index);
            group.ready.set(index);
            return false;
        }
        // An actor returning after a render gap must first pass the normal render
        // filters off-screen unless its previously displayed pose is still held.
        if (!group.previous.get(index) && !group.poses.containsKey(index)) return false;
        group.ready.set(index);
        if (group.appearanceFrame != frame) {
            group.appearanceFrame = frame;
            group.replay.id = emitter.id;
            group.replay.dim = level.dimension().location().toString();
            group.replay.count = 1;
            group.replay.ground = false;
            group.replay.appearance = emitter.appearance;
            group.replay.skins = emitter.skins;
            group.replay.mobs = emitter.mobs;
        }
        Pose pose = group.poses.computeIfAbsent(index, id -> new Pose());
        pose.ghost = actor;
        pose.box = bounds;
        pose.submitted = frame;
        pose.restingSince = Double.NaN;
        return true;
    }

    static boolean outsideSpawnView(double x, double y, double z, float size) {
        if (frustum == null) return false;
        double halfWidth = .45 * (size / 1.875) + SPAWN_MARGIN;
        return !frustum.isVisible(new AABB(x - halfWidth, y - SPAWN_MARGIN, z - halfWidth,
                x + halfWidth, y + size + SPAWN_MARGIN, z + halfWidth));
    }

    static boolean keep(CrowdData.Emitter emitter, int index) {
        Group group = GROUPS.get(emitter.id);
        Pose pose = group == null ? null : group.poses.get(index);
        return pose != null && onScreen(pose);
    }

    static boolean hasTrackedActors(CrowdData.Emitter emitter) {
        Group group = GROUPS.get(emitter.id);
        return group != null && !group.poses.isEmpty();
    }

    private static boolean onScreen(Pose pose) {
        return frustum.isVisible(pose.box)
                && level.hasChunkAt(BlockPos.containing(pose.ghost.x(), pose.ghost.y(), pose.ghost.z()));
    }

    static void sample(CrowdData.Emitter emitter, double time, List<CrowdSampler.Ghost> out) {
        Group retiring = RETIRING.get(emitter);
        if (retiring == null) {
            CrowdNavigation.sample(emitter, time, out);
            return;
        }
        var poses = retiring.poses.values().iterator();
        while (poses.hasNext()) {
            Pose pose = poses.next();
            if (pose.submitted == frame) continue;
            if (!onScreen(pose)) {
                poses.remove();
                continue;
            }
            // Disabled or deleted groups have no simulation. Settle the last pose while in view.
            if (Double.isNaN(pose.restingSince)) pose.restingSince = time;
            CrowdSampler.Ghost actor = pose.ghost;
            float move = actor.move() * (float)Math.clamp(1.0 - (time - pose.restingSince) / .25, 0.0, 1.0);
            out.add(new CrowdSampler.Ghost(actor.x(), actor.y(), actor.z(), actor.yaw(), actor.alpha(),
                    actor.size(), actor.color(), actor.idx(), actor.gait(), move));
        }
    }

    static void adjust(CrowdData.Emitter emitter, double time, List<CrowdSampler.Ghost> out) {
        if (!RETIRING.containsKey(emitter)) CrowdNavigation.adjust(emitter, time, out);
    }

    static double[] resolve(Minecraft mc, CrowdData.Emitter emitter, CrowdSampler.Ghost ghost,
                            double x, double y, double z) {
        if (!RETIRING.containsKey(emitter)) return CrowdNavigation.resolve(mc, emitter, ghost, x, y, z);
        POSITION[0] = ghost.x();
        POSITION[1] = ghost.y();
        POSITION[2] = ghost.z();
        return POSITION;
    }

    static CrowdSampler.Ghost animated(CrowdData.Emitter emitter, CrowdSampler.Ghost ghost) {
        return RETIRING.containsKey(emitter) ? ghost : CrowdNavigation.animated(emitter, ghost);
    }

    static boolean hasActive(CrowdData.Emitter emitter) {
        return !RETIRING.containsKey(emitter) && CrowdNavigation.hasActive(emitter);
    }

    static double distance(double limit, CrowdData.Emitter emitter) {
        return RETIRING.containsKey(emitter) ? Double.POSITIVE_INFINITY : limit;
    }
}
