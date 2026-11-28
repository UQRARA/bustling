package dev.bustling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

/** Local metadata cache. Device deletion waits for the server's authoritative snapshot. */
public final class ClientCrowdStore {
    private static final Gson GSON = new Gson();
    private static final Map<String, JsonElement> DIFFUSER_SYNC = new LinkedHashMap<>();
    private static final ArrayDeque<CrowdEdits.Request> PENDING_EDITS = new ArrayDeque<>();
    private static Map<CrowdEdits.Key, JsonObject> known = Map.of();
    private static boolean synchronizedEdits;
    private static long editSequence;
    private static final long REMOVAL_TIMEOUT = 2_000_000_000L;
    private static PendingRemoval pendingRemoval;
    private record PendingRemoval(String id, ClientLevel level, long sentAt) {}

    private ClientCrowdStore() {}

    public static void removeDevice(String id) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || id == null || !DIFFUSER_SYNC.containsKey(id) || !canSend(mc)) return;
        long now = System.nanoTime();
        if (pendingRemoval != null && pendingRemoval.level() == level
                && now - pendingRemoval.sentAt() < REMOVAL_TIMEOUT) return;

        JsonArray removals = new JsonArray();
        removals.add(id);
        JsonObject packet = new JsonObject();
        packet.add("removedDiffusers", removals);
        pendingRemoval = new PendingRemoval(id, level, now);
        try {
            PacketDistributor.sendToServer(new EditPayload(GSON.toJson(packet)));
        } catch (RuntimeException unavailable) {
            pendingRemoval = null;
        }
    }

    public static void applyJson(String json) {
        try {
            CrowdEdits.Snapshot snapshot = GSON.fromJson(json, CrowdEdits.Snapshot.class);
            if (snapshot == null || snapshot.emitters() == null) return;
            var effective = new LinkedHashMap<CrowdEdits.Key, CrowdData.Emitter>();
            var devices = new LinkedHashMap<String, JsonElement>();
            for (CrowdData.Emitter emitter : snapshot.emitters()) {
                if (emitter == null) return;
                emitter.normalizeType();
                if (emitter.dim == null || emitter.dim.isBlank()) emitter.dim = "minecraft:overworld";
                if (!CrowdEdits.valid(emitter) || effective.putIfAbsent(CrowdEdits.key(emitter), emitter) != null) return;
                if (DiffuserData.isDiffuser(emitter)) devices.put(emitter.id, GSON.toJsonTree(emitter));
            }

            synchronizedEdits = snapshot.editProtocol() == CrowdEdits.PROTOCOL;
            if (!synchronizedEdits) PENDING_EDITS.clear();
            boolean rejected = false;
            while (!PENDING_EDITS.isEmpty() && PENDING_EDITS.getFirst().sequence() <= snapshot.editAck()) {
                CrowdEdits.Request request = PENDING_EDITS.removeFirst();
                if (request.sequence() == snapshot.editAck()) rejected = !snapshot.editAccepted();
            }
            for (CrowdEdits.Request request : PENDING_EDITS) {
                for (CrowdEdits.Change change : request.changes()) {
                    CrowdData.Emitter previous = effective.get(change.key());
                    if (!Objects.equals(change.before(), CrowdEdits.value(previous))) continue;
                    if (change.after() == null) {
                        effective.remove(change.key());
                    } else {
                        var next = GSON.fromJson(change.after(), CrowdData.Emitter.class);
                        if (DiffuserData.isDiffuser(previous)) {
                            next.enabled = previous.enabled;
                        }
                        effective.put(change.key(), next);
                    }
                }
            }

            var existing = new LinkedHashMap<CrowdEdits.Key, CrowdData.Emitter>();
            for (CrowdData.Emitter emitter : CrowdData.EMITTERS) existing.put(CrowdEdits.key(emitter), emitter);
            effective.replaceAll((key, incoming) -> {
                CrowdData.Emitter before = existing.get(key);
                return before != null && GSON.toJsonTree(before).equals(GSON.toJsonTree(incoming)) ? before : incoming;
            });
            var rows = new ArrayList<>(effective.values());
            if (!CrowdData.EMITTERS.equals(rows)) {
                CrowdData.EMITTERS.clear();
                CrowdData.EMITTERS.addAll(rows);
                if (CrowdData.reloadListener != null) CrowdData.reloadListener.run();
                WorldSystem.invalidate();
            }
            known = CrowdEdits.values(rows);
            DIFFUSER_SYNC.clear();
            DIFFUSER_SYNC.putAll(devices);
            ToolModels.refreshDevices();
            if (rejected) System.getLogger("Bustling").log(System.Logger.Level.WARNING,
                    "A layout edit conflicted with server state or was rejected. The current server layout was restored.");
        } catch (RuntimeException malformed) {
            System.getLogger("Bustling").log(System.Logger.Level.WARNING, "Could not read crowd synchronization", malformed);
            return;
        }
        if (pendingRemoval != null) {
            PendingRemoval attempt = pendingRemoval;
            boolean current = attempt.level() == Minecraft.getInstance().level
                    && System.nanoTime() - attempt.sentAt() < REMOVAL_TIMEOUT;
            if (!current || !DIFFUSER_SYNC.containsKey(attempt.id())) {
                pendingRemoval = null;
                if (current) ToolSounds.deleted();
            }
        }
    }

    static void applyDevicePower(String id, boolean powered) {
        JsonElement snapshot = DIFFUSER_SYNC.get(id);
        if (snapshot == null) return;
        for (CrowdData.Emitter emitter : CrowdData.EMITTERS) {
            if (!id.equals(emitter.id) || !DiffuserData.isDiffuser(emitter) || !Boolean.TRUE.equals(emitter.redstoneControlled)) continue;
            snapshot.getAsJsonObject().addProperty("enabled", powered);
            if (emitter.enabled != powered) {
                emitter.enabled = powered;
                WorldSystem.invalidate();
            }
            return;
        }
    }

    static boolean supportsDevicePower() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(DiffuserPowerPayload.TYPE) && connection.hasChannel(EditPayload.TYPE);
    }

    public static void resetSession() {
        pendingRemoval = null;
        DIFFUSER_SYNC.clear();
        PENDING_EDITS.clear();
        known = Map.of();
        synchronizedEdits = false;
        editSequence = 0;
        CrowdData.EMITTERS.clear();
        SelectionTool.areaPoints().clear();
        SelectionTool.activePath = null;
        EditTool.clear();
        ToolModels.clearDevices();
        ClientDevicePlacements.clear();
        WorldSystem.invalidate();
        WorldSystem.resetEnvironment();
        WorldClient.clear();
    }

    private static boolean canSend(Minecraft mc) {
        var connection = mc.getConnection();
        return connection != null && connection.hasChannel(EditPayload.TYPE);
    }

    private static void sendChanges() {
        Map<CrowdEdits.Key, JsonObject> current = CrowdEdits.values(CrowdData.EMITTERS);
        var changes = new ArrayList<CrowdEdits.Change>();
        current.forEach((key, after) -> {
            JsonObject before = known.get(key);
            if (key.id().startsWith("diffuser:") && before == null) return;
            if (!after.equals(before)) changes.add(new CrowdEdits.Change(key, before, after));
        });
        known.forEach((key, before) -> {
            if (!key.id().startsWith("diffuser:") && !current.containsKey(key)) {
                changes.add(new CrowdEdits.Change(key, before, null));
            }
        });
        if (changes.isEmpty()) return;
        for (int from = 0; from < changes.size(); from += CrowdEdits.MAX_CHANGES) {
            var request = new CrowdEdits.Request(++editSequence,
                    new ArrayList<>(changes.subList(from, Math.min(changes.size(), from + CrowdEdits.MAX_CHANGES))));
            PacketDistributor.sendToServer(new EditPayload(GSON.toJson(request)));
            PENDING_EDITS.addLast(request);
            var sent = new LinkedHashMap<>(known);
            for (CrowdEdits.Change change : request.changes()) {
                if (change.after() == null) sent.remove(change.key());
                else sent.put(change.key(), change.after());
            }
            known = sent;
        }
    }

    public static void selectSettings(CrowdData.Emitter e) {
        ToolConfig.pushEnabled = e.pushEnabled;
        ToolConfig.pushRadius = e.pushRadius;
        ToolConfig.slowLevel = e.slowLevel;
        ToolConfig.size = e.size;
        ToolConfig.groundMin = e.groundMin;
        ToolConfig.groundMax = e.groundMax;
    }

    public static Path file() { return FMLPaths.CONFIGDIR.get().resolve("bustling/client/data.json"); }

    public static void save() {
        WorldSystem.invalidate();
        String json = CrowdData.toJson();
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling("data.json.tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            System.getLogger("Bustling").log(System.Logger.Level.ERROR, "Could not save local crowd metadata", error);
        }
        if (!synchronizedEdits || !canSend(Minecraft.getInstance())) return;
        try {
            sendChanges();
        } catch (RuntimeException unavailable) {
            // The optional server channel may be unavailable.
        }
    }
}
