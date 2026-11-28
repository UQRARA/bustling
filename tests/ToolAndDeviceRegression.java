import java.util.List;
import java.util.Map;
import com.google.gson.*;
import dev.bustling.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ToolAndDeviceRegression {
    private static final String DIM = "minecraft:overworld";
    private static final String DEVICE = "diffuser:" + DIM + "/4/64/7";
    private static final String REQUEST = "{\"removedDiffusers\":[\"" + DEVICE + "\"]}";
    private static String initial;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void audio() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        var sounds = mc.getSoundManager().sounds;
        ToolSounds.step(true);
        ToolSounds.step(false);
        check(sounds.size() == 1 && sounds.getFirst().pitch > 1.2f, "step rate limit");
        Thread.sleep(100);
        ToolSounds.step(false);
        check(sounds.size() == 2 && sounds.getLast().pitch < 1.15f, "step resumes with lower pitch");
        ToolSounds.pick();
        ToolSounds.gear();
        ToolSounds.place(4);
        ToolSounds.commit();
        ToolSounds.copied();
        ToolSounds.pasted();
        ToolSounds.remove();
        check(sounds.size() == 9, "exactly one sample per distinct operation");
        for (SimpleSoundInstance sound : sounds) {
            check(sound.category == SoundSource.PLAYERS && sound.relative && sound.volume <= .85f,
                    "local, scaled cue assigned to player volume");
        }
        check(sounds.get(6).volume >= .45f && sounds.get(7).volume >= .75f,
                "copy and paste cues are audible");
        mc.options.players = 0;
        ToolSounds.deleted();
        check(sounds.size() == 9, "muted Players slider");
        mc.options.players = 1;
        mc.options.master = 0;
        ToolSounds.deleted();
        check(sounds.size() == 9, "muted master");
        mc.options.master = 1;
        ToolSounds.changeMode(ToolConfig.Mode.PATH);
        ToolSounds.changeMode(ToolConfig.Mode.PATH);
        check(ToolConfig.mode == ToolConfig.Mode.PATH && sounds.size() == 10, "cue only on a mode change");
        Thread.sleep(75);
        ToolSounds.changeMode(ToolConfig.Mode.DELETE);
        check(sounds.size() == 11 && sounds.get(10).pitch < sounds.get(9).pitch,
                "delete uses a distinct lower cue");
    }

    private static void noMutation(ServerPlayer player, String json, String reason) {
        String before = ServerCrowdStore.toJson();
        int reads = player.level.reads, syncs = BustlingNet.syncs;
        ServerCrowdStore.handleClientEdit(json, player);
        check(ServerCrowdStore.toJson().equals(before), "metadata changed: " + reason);
        check(player.level.writes == 0 && BustlingNet.syncs == syncs, "mutation or save: " + reason);
        if (!player.level.loaded || !player.level.allowed) check(player.level.reads == reads, "queried forbidden chunk");
    }

    private static void server() throws Exception {
        var root = new CrowdData.Root();
        root.emitters.add(DiffuserData.create(DIM, 4, 64, 7));
        root.emitters.add(DiffuserData.create("minecraft:the_nether", 4, 64, 7));
        initial = new Gson().toJson(root);
        ServerCrowdStore.applyJson(initial);
        ServerPlayer player = new ServerPlayer();
        player.level.loaded = false;
        noMutation(player, REQUEST, "unloaded");
        player.level.loaded = true;
        player.level.allowed = false;
        noMutation(player, REQUEST, "spawn protected");
        player.level.allowed = true;
        player.distance = 4900.01;
        noMutation(player, REQUEST, "out of reach");
        player.distance = 3;
        player.spectator = true;
        noMutation(player, REQUEST, "spectator");
        player.spectator = false;
        player.abilities.mayBuild = false;
        noMutation(player, REQUEST, "no permission to build");
        player.abilities.mayBuild = true;
        player.held = new ItemStack(new Item());
        noMutation(player, REQUEST, "wrong tool");
        player.held = new ItemStack(new WandItem());
        noMutation(player, REQUEST.replace(DIM, "minecraft:the_nether"), "different dimension");
        noMutation(player, REQUEST.replace("/4/64/7", "/5/64/7"), "unknown device");
        noMutation(player, "{\"removedDiffusers\":[\"" + DEVICE + "\",\"" + DEVICE + "\"]}", "bulk request");
        NeoForge.cancel = true;
        noMutation(player, REQUEST, "protection mod veto");
        NeoForge.cancel = false;
        NeoForge.replace = true;
        noMutation(player, REQUEST, "block replaced by listener");
        NeoForge.replace = false;
        player.level.state = net.minecraft.world.level.block.Blocks.SPAWNER.defaultBlockState();
        ServerCrowdStore.applyClientJson(REQUEST);
        check(player.level.writes == 0 && ServerCrowdStore.toJson().contains(DEVICE), "no player context may not remove");
        ServerCrowdStore.handleClientEdit(REQUEST, player);
        check(player.level.writes == 1 && BustlingNet.syncs == 1, "single lifecycle write and sync");
        check(!ServerCrowdStore.toJson().contains(DEVICE) && ServerCrowdStore.toJson().contains("the_nether"),
              "only the authorized block metadata was removed");
        var region = new CrowdData.Emitter("region-qa", "region");
        var change = Map.of("key", Map.of("dimension", DIM, "id", region.id), "after", region);
        ServerCrowdStore.handleClientEdit(new Gson().toJson(Map.of("sequence", 1, "changes", List.of(change))), player);
        check(BustlingNet.syncs == 2 && ServerCrowdStore.toJson().contains("region-qa")
                && ServerCrowdStore.toJson().contains("the_nether"), "ordinary saves preserve diffuser metadata");
    }

    private static void client() {
        var mc = Minecraft.getInstance();
        int sounds = mc.getSoundManager().sounds.size();
        ClientCrowdStore.applyJson(initial);
        ClientCrowdStore.removeDevice(DEVICE);
        ClientCrowdStore.removeDevice(DEVICE);
        check(PacketDistributor.sent.size() == 1, "one outstanding deletion");
        check(JsonParser.parseString(PacketDistributor.sent.getFirst()).getAsJsonObject().keySet()
                .equals(java.util.Set.of("removedDiffusers")), "request contains only the deletion identifier");
        check(CrowdData.EMITTERS.size() == 2 && mc.getSoundManager().sounds.size() == sounds, "no optimistic deletion");
        ClientCrowdStore.applyJson(initial);
        check(mc.getSoundManager().sounds.size() == sounds, "unchanged sync is silent");
        ClientCrowdStore.applyJson("{\"emitters\":[]}");
        check(mc.getSoundManager().sounds.size() == sounds + 1, "authoritative removal plays cue");
        ClientCrowdStore.applyJson("{\"emitters\":[]}");
        check(mc.getSoundManager().sounds.size() == sounds + 1, "duplicate sync cannot replay cue");
        ClientCrowdStore.applyJson(initial);
        PacketDistributor.connected = false;
        ClientCrowdStore.removeDevice(DEVICE);
        PacketDistributor.connected = true;
        ClientCrowdStore.removeDevice(DEVICE);
        check(PacketDistributor.sent.size() == 2, "failed send clears in-flight state");
        mc.level = new ClientLevel();
        ClientCrowdStore.applyJson("{\"emitters\":[]}");
        check(mc.getSoundManager().sounds.size() == sounds + 1, "old world's request made no sound");
    }

    public static void main(String[] args) throws Exception {
        audio();
        server();
        client();
        System.out.println("PASS cue rate, levels and mute, protected and unloaded devices, one save, client confirmation");
    }
}
