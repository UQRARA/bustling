package dev.bustling;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Record-level optimistic edits. Device power is live state outside the edit comparison. */
final class CrowdEdits {
    static final int PROTOCOL = 2;
    static final int MAX_CHANGES = 256;
    private static final Gson GSON = new Gson();

    record Key(String dimension, String id) {}
    record Change(Key key, JsonObject before, JsonObject after) {}
    record Request(long sequence, List<Change> changes) {}
    record Snapshot(List<CrowdData.Emitter> emitters, int editProtocol, long editAck, boolean editAccepted) {}

    private CrowdEdits() {}

    static Key key(CrowdData.Emitter emitter) {
        return new Key(emitter.dim, emitter.id);
    }

    static JsonObject value(CrowdData.Emitter emitter) {
        if (emitter == null) return null;
        JsonObject value = GSON.toJsonTree(emitter).getAsJsonObject();
        if (DiffuserData.isDiffuser(emitter)) value.remove("enabled");
        return value;
    }

    static Map<Key, JsonObject> values(List<CrowdData.Emitter> emitters) {
        Map<Key, JsonObject> values = new LinkedHashMap<>();
        for (CrowdData.Emitter emitter : emitters) {
            if (emitter != null) values.put(key(emitter), value(emitter));
        }
        return values;
    }

    static boolean validKey(Key key) {
        return key != null && key.id != null && !key.id.isBlank() && key.id.length() <= 160
                && key.dimension != null && key.dimension.length() <= 128
                && ResourceLocation.tryParse(key.dimension) != null;
    }

    static boolean valid(CrowdData.Emitter emitter) {
        if (emitter == null || !validKey(key(emitter)) || !point(emitter.a) || !point(emitter.b)) return false;
        if (DiffuserData.isDiffuser(emitter)) {
            if (!DiffuserData.valid(emitter)) return false;
        } else {
            if (emitter.id.startsWith("diffuser:") || !(emitter.isLine() || "region".equals(emitter.type))) return false;
            if (emitter.isLine() && (emitter.path == null || emitter.path.size() > 1024
                    || emitter.path.stream().anyMatch(p -> !point(p)))) return false;
            if (emitter.polygon != null && !emitter.polygon.isEmpty()
                    && (emitter.polygon.size() < 3 || emitter.polygon.size() > 64
                    || emitter.polygon.stream().anyMatch(p -> !point(p)) || !CrowdBounds.validPolygon(emitter.polygon))) return false;
            if (emitter.count < 0 || emitter.count > 20_000) return false;
        }
        return Double.isFinite(emitter.width) && Double.isFinite(emitter.size)
                && Double.isFinite(emitter.speed) && Double.isFinite(emitter.spread)
                && Double.isFinite(emitter.spacing) && Double.isFinite(emitter.alpha)
                && Double.isFinite(emitter.maxDist) && Double.isFinite(emitter.stepHeight)
                && (emitter.groundBlocks == null || emitter.groundBlocks.size() <= 512
                    && emitter.groundBlocks.stream().allMatch(id -> id != null && id.length() <= 160))
                && (emitter.skins == null || emitter.skins.size() <= 512
                    && emitter.skins.stream().allMatch(skin -> skin != null && skin.id != null && Double.isFinite(skin.weight)))
                && (emitter.mobs == null || emitter.mobs.size() <= 512
                    && emitter.mobs.stream().allMatch(mob -> mob != null && mob.id != null && Double.isFinite(mob.weight)));
    }

    private static boolean point(double[] point) {
        if (point == null || point.length != 3) return false;
        for (double coordinate : point) if (!Double.isFinite(coordinate) || Math.abs(coordinate) > 30_000_000) return false;
        return true;
    }
}
