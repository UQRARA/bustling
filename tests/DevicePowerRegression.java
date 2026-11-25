package dev.bustling;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class DevicePowerRegression {
    private static final BlockPos POS = new BlockPos(4, 64, 7);
    private static final String ID = DiffuserData.key("minecraft:overworld", 4, 64, 7);

    public static void main(String[] args) throws Exception {
        ServerPlayer player = new ServerPlayer();
        var tick = new ServerTickEvent.Post(player.server);
        ServerCrowdStore.placeDiffuser("minecraft:overworld", 4, 64, 7);
        check(!device().has("redstoneControlled") && active(), "old/default device runs without a signal");

        var edit = device();
        edit.addProperty("redstoneControlled", true);
        edit.addProperty("enabled", true);
        sendEdit(edit, player);
        check(!active() && device().get("redstoneControlled").getAsBoolean(), "server checks actual signal when mode is enabled");
        String stored = Files.readString(ServerCrowdStore.file());
        int saves = BustlingNet.syncs;

        player.level.powered = true;
        ServerCrowdStore.refreshDevicePower(player.level, POS);
        ServerCrowdStore.refreshDevicePower(player.level, POS);
        ServerCrowdStore.flushDevicePower(tick);
        ServerCrowdStore.flushDevicePower(tick);
        check(active() && BustlingNet.powerSyncs == 1 && BustlingNet.lastPower.equals(Map.of(ID, true)),
                "identical power is coalesced into a single update");
        check(BustlingNet.syncs == saves && Files.readString(ServerCrowdStore.file()).equals(stored),
                "power transitions do not save metadata");

        ClientCrowdStore.applyJson(ServerCrowdStore.toJson());
        CrowdData.Emitter sameDevice = CrowdData.EMITTERS.getFirst();
        ClientCrowdStore.applyDevicePower(ID, false);
        check(CrowdData.EMITTERS.getFirst() == sameDevice && !sameDevice.enabled,
                "redstone delta updates the live device without replacing editors' models");

        var legacyEdit = device();
        legacyEdit.remove("redstoneControlled");
        legacyEdit.addProperty("density", 23);
        sendEdit(legacyEdit, player);
        check(active() && device().get("redstoneControlled").getAsBoolean()
                && device().get("density").getAsInt() == 23, "older edits leave custom power mode intact");

        player.level.powered = false;
        ServerCrowdStore.refreshDevicePower(player.level, POS);
        ServerCrowdStore.flushDevicePower(tick);
        check(!active() && BustlingNet.lastPower.equals(Map.of(ID, false)), "unpowered mode goes to standby");

        player.level.powered = true;
        ServerCrowdStore.load();
        check(!active() && device().get("redstoneControlled").getAsBoolean(), "restart restores the mode and waits for live power");
        ServerCrowdStore.refreshDevicePower(player.level, POS);
        check(active(), "first live power read resumes the device after restart");

        var disable = device();
        disable.addProperty("redstoneControlled", false);
        sendEdit(disable, player);
        check(active(), "disabling redstone control restores always on");
        System.out.println("PASS optional redstone mode, authoritative power, legacy edits, coalescing and client delta");
    }

    private static void sendEdit(JsonObject device, ServerPlayer sender) {
        var packet = new JsonObject();
        packet.add("emitters", new JsonArray());
        var edits = new JsonArray();
        edits.add(device);
        packet.add("diffuserEdits", edits);
        ServerCrowdStore.handleClientEdit(packet.toString(), sender);
    }

    private static JsonObject device() {
        return JsonParser.parseString(ServerCrowdStore.toJson()).getAsJsonObject()
                .getAsJsonArray("emitters").get(0).getAsJsonObject();
    }

    private static boolean active() {
        return device().get("enabled").getAsBoolean();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
