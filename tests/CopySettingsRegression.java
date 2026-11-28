package dev.bustling;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;

public final class CopySettingsRegression {
    private static final String DIM = "minecraft:overworld";

    public static void main(String[] args) {
        var strip = new CrowdData.Emitter("strip", "line");
        strip.dim = DIM;
        strip.path = List.of(new double[] {0, 64, 0}, new double[] {16, 64, 0});
        strip.width = 7;
        strip.density = 39;
        strip.stepHeight = 1.5;
        strip.groundMode = 1;
        strip.groundBlocks = new ArrayList<>(List.of("minecraft:stone"));
        strip.appearance = "entity";
        strip.mobs.add(new CrowdData.Emitter.MobWeight("minecraft:villager", .65, "{Age:0}", "Resident"));
        strip.speed = 2;
        strip.size = 2.25;
        strip.spacing = 1.2;

        var template = CrowdSettings.snapshot(strip);
        strip.mobs.getFirst().weight = .1;
        strip.groundBlocks.clear();

        ServerCrowdStore.load();
        ServerCrowdStore.placeDiffuser(DIM, 3, 63, 7);
        var device = DiffuserData.create(DIM, 3, 63, 7);
        double[] center = device.a;
        device.diffuserRadius = 11;
        device.diffuserLimit = 32;
        device.diffuserDecay = 2;
        device.enabled = false;
        device.redstoneControlled = true;
        CrowdSettings.paste(device, template);
        checkShared(device);
        check(device.width == 3 && device.diffuserRadius == 11 && device.diffuserLimit == 32
                && device.diffuserDecay == 2, "extra strip settings do not replace diffuser controls");
        check(device.a == center && !device.enabled && device.redstoneControlled, "device placement and power remain intact");
        check(device.count == 32, "actual population uses the target's radius and cap");

        var gson = new Gson();
        var previous = gson.fromJson(ServerCrowdStore.toJson(), CrowdData.Root.class).emitters.getFirst();
        var change = new CrowdEdits.Change(CrowdEdits.key(device), CrowdEdits.value(previous), CrowdEdits.value(device));
        ServerCrowdStore.handleClientEdit(gson.toJson(new CrowdEdits.Request(1, List.of(change))),
                new net.minecraft.server.level.ServerPlayer());
        var saved = gson.fromJson(ServerCrowdStore.toJson(), CrowdData.Root.class).emitters.getFirst();
        checkShared(saved);
        check(DiffuserData.valid(saved) && saved.diffuserRadius == 11 && saved.diffuserDecay == 2,
                "server preserves device geometry and stores pasted settings");

        var other = DiffuserData.create(DIM, 9, 63, 7);
        CrowdSettings.paste(other, CrowdSettings.snapshot(device));
        checkShared(other);
        check(other.diffuserRadius == 11 && other.diffuserLimit == 32 && other.diffuserDecay == 2
                && DiffuserData.valid(other) && !other.id.equals(device.id), "diffuser profiles copy between different blocks");

        var area = new CrowdData.Emitter("area", "region");
        double[] corner = area.b;
        area.width = 5;
        CrowdSettings.paste(area, CrowdSettings.snapshot(device));
        checkShared(area);
        check(area.b == corner && area.width == 5 && area.enabled, "device data does not overwrite area geometry or activation");
        other.mobs.getFirst().weight = 1;
        other.groundBlocks.clear();
        checkShared(device);
        System.out.println("PASS compatible crowd clipboard fields, separate geometry, detached models and server sync");
    }

    private static void checkShared(CrowdData.Emitter emitter) {
        check(emitter.density == 39 && emitter.stepHeight == 1.5 && emitter.groundMode == 1
                && emitter.groundBlocks.equals(List.of("minecraft:stone")), "density, climbing and ground filter transfer");
        var row = emitter.mobs.getFirst();
        check(emitter.appearance.equals("entity") && row.id.equals("minecraft:villager")
                && row.weight == .65 && row.nbt.equals("{Age:0}") && row.name.equals("Resident"), "models transfer with metadata");
        check(emitter.speed == 2 && emitter.size == 2.25 && emitter.spacing == 1.2, "shared movement and model size transfer");
    }

    private static void check(boolean condition, String expected) {
        if (!condition) throw new AssertionError(expected);
    }
}
