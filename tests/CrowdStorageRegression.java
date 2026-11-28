package dev.bustling;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

public final class CrowdStorageRegression {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        ServerPlayer editor = new ServerPlayer();
        ServerLifecycleHooks.currentServer = editor.server;
        Path config = Path.of(System.getProperty("bustling.qa.config"));
        Path legacy = config.resolve("bustling/server/qa-world/data.json");
        Files.createDirectories(legacy.getParent());
        var street = new CrowdData.Emitter("street", "region");
        var square = new CrowdData.Emitter("square", "region");
        street.density = square.density = 10;
        write(legacy, street, square);

        useWorld(config.resolve("world-a"));
        Path worldA = ServerCrowdStore.file();
        check(Files.isRegularFile(worldA) && Files.isRegularFile(legacy)
                && Files.isRegularFile(legacy.resolveSibling("data.json.migrated")), "migrates to the world and keeps the original");
        var streetEdit = density(street, 11);
        var squareEdit = density(square, 12);
        send(editor, 1, edit(street, streetEdit));
        send(new ServerPlayer(), 1, edit(square, squareEdit));
        check(BustlingNet.accepted && stored("street").density == 11 && stored("square").density == 12,
                "two players' independent edits are merged");
        send(new ServerPlayer(), 2, edit(street, density(street, 17)));
        check(!BustlingNet.accepted && stored("street").density == 11, "same-record stale edits are rejected");
        send(editor, 3, edit(squareEdit, density(squareEdit, 20)), edit(street, null));
        check(!BustlingNet.accepted && stored("square").density == 12, "rejected batch applies none of its edits");

        editor.abilities.mayBuild = false;
        send(editor, 4, edit(streetEdit, null));
        check(!BustlingNet.accepted && stored("street") != null, "build permissions also apply to deletes");
        editor.abilities.mayBuild = true;
        ServerCrowdStore.handleClientEdit("{\"emitters\":[]}", editor);
        check(stored("street") != null, "a legacy overwrite cannot erase server data");

        ClientCrowdStore.applyJson(ServerCrowdStore.networkJson(0));
        clientLayout("street").density = 13;
        ClientCrowdStore.save();
        String sentFirst = PacketDistributor.sent.getLast();
        clientLayout("street").density = 14;
        ClientCrowdStore.save();
        String sentSecond = PacketDistributor.sent.getLast();
        var operation = GSON.fromJson(sentFirst, CrowdEdits.Request.class);
        check(operation.changes().size() == 1 && operation.changes().getFirst().key().id().equals("street"),
                "only edited layout is sent");
        ServerCrowdStore.handleClientEdit(sentFirst, editor);
        ClientCrowdStore.applyJson(ServerCrowdStore.networkJson(1, true));
        check(clientLayout("street").density == 14, "first reply preserves the second pending edit");
        ServerCrowdStore.handleClientEdit(sentSecond, editor);
        ClientCrowdStore.applyJson(ServerCrowdStore.networkJson(2, true));
        check(stored("street").density == 14, "both same-client edits are committed in order");

        useWorld(config.resolve("world-b"));
        check(stored("street") == null && !worldA.equals(ServerCrowdStore.file()),
                "another world with the same old key is independently initialized");
        Path worldB = ServerCrowdStore.file();
        Files.writeString(worldB, "{");
        ServerCrowdStore.load();
        ServerCrowdStore.save();
        check(!ServerCrowdStore.writable() && Files.readString(worldB).equals("{"), "bad saves are preserved");
        useWorld(config.resolve("world-a"));
        check(ServerCrowdStore.writable() && stored("street").density == 14, "returning to a valid world restores access");

        WorldSystem.avoidBlocks = WorldSystem.avoidPlayers = false;
        WorldSystem.playerRadius = -7;
        WorldSystem.resetDefaults();
        check(WorldSystem.avoidBlocks && WorldSystem.avoidPlayers && WorldSystem.playerRadius == 6,
                "reset restores all avoidance settings");
        System.out.println("PASS per-world migration, corrupt save protection, independent edits, conflicts, acknowledgement and reset");
    }

    private static CrowdData.Emitter clientLayout(String id) {
        return CrowdData.EMITTERS.stream().filter(row -> row.id.equals(id)).findFirst().orElseThrow();
    }

    private static void useWorld(Path path) {
        System.setProperty("bustling.qa.world", path.toString());
        ServerCrowdStore.load();
    }

    private static void write(Path path, CrowdData.Emitter... emitters) throws Exception {
        var root = new CrowdData.Root();
        root.emitters = List.of(emitters);
        Files.writeString(path, GSON.toJson(root));
    }

    private static CrowdData.Emitter stored(String id) {
        return GSON.fromJson(ServerCrowdStore.toJson(), CrowdData.Root.class).emitters.stream()
                .filter(row -> id.equals(row.id)).findFirst().orElse(null);
    }

    private static CrowdData.Emitter density(CrowdData.Emitter before, int density) {
        var after = GSON.fromJson(GSON.toJsonTree(before), CrowdData.Emitter.class);
        after.density = density;
        return after;
    }

    private static CrowdEdits.Change edit(CrowdData.Emitter before, CrowdData.Emitter after) {
        return new CrowdEdits.Change(CrowdEdits.key(before), CrowdEdits.value(before), CrowdEdits.value(after));
    }

    private static void send(ServerPlayer player, long sequence, CrowdEdits.Change... changes) {
        ServerCrowdStore.handleClientEdit(GSON.toJson(new CrowdEdits.Request(sequence, List.of(changes))), player);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
