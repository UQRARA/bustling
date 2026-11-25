package dev.bustling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

/** Local metadata cache. Device deletion waits for the server's authoritative snapshot. */
public final class ClientCrowdStore {
    private static final Gson GSON = new Gson();
    private static final Map<String, JsonElement> DIFFUSER_SYNC = new HashMap<>();
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
        CrowdData.applyJson(json);
        ToolModels.refreshDevices();
        DIFFUSER_SYNC.clear();
        for (CrowdData.Emitter e : CrowdData.EMITTERS) {
            if (DiffuserData.isDiffuser(e)) DIFFUSER_SYNC.put(e.id, GSON.toJsonTree(e));
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
        WorldSystem.invalidate();
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

    private static String editPacket(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray changed = new JsonArray();
        for (CrowdData.Emitter e : CrowdData.EMITTERS) {
            if (!DiffuserData.isDiffuser(e) || !DIFFUSER_SYNC.containsKey(e.id)) continue;
            JsonElement snapshot = GSON.toJsonTree(e);
            if (!snapshot.equals(DIFFUSER_SYNC.get(e.id))) changed.add(snapshot);
        }
        root.add("diffuserEdits", changed);
        return GSON.toJson(root);
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
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException error) {
            System.getLogger("Bustling").log(System.Logger.Level.ERROR, "Could not save local crowd metadata", error);
        }
        if (!canSend(Minecraft.getInstance())) return;
        try {
            PacketDistributor.sendToServer(new EditPayload(editPacket(json)));
        } catch (RuntimeException unavailable) {
            // The optional server channel may be unavailable.
        }
    }
}
