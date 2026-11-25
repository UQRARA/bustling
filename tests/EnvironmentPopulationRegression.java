package dev.bustling;

import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.biome.Biome.Precipitation;
import net.minecraft.world.level.dimension.DimensionType;

public final class EnvironmentPopulationRegression {
    private static final Minecraft CLIENT = Minecraft.getInstance();

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            WorldSystem.load();
            require(!WorldSystem.environmentEnabled, "disabled setting survived restart");
            require(WorldSystem.navigationBudget == 4 && WorldSystem.footsteps, "budget and audio survive restart");
            return;
        }
        curves();
        allocation();
        System.out.println("PASS daylight and weather curves, easing, local caps and settings");
    }

    private static void curves() {
        near(EnvironmentPopulation.daylight(0), 0.7);
        near(EnvironmentPopulation.daylight(6000), 1);
        near(EnvironmentPopulation.daylight(18000), 0.4);
        near(EnvironmentPopulation.daylight(24000), EnvironmentPopulation.daylight(0));
        require(Math.abs(EnvironmentPopulation.daylight(-1) - EnvironmentPopulation.daylight(0)) < .001,
                "dawn is continuous at midnight tick wrap");
        near(EnvironmentPopulation.precipitation(0, .5), 1);
        near(EnvironmentPopulation.precipitation(.5, .5), .75);
        near(EnvironmentPopulation.precipitation(1, .7), .7);
        near(EnvironmentPopulation.precipitation(1, .5), .5);

        EnvironmentPopulation filtered = new EnvironmentPopulation();
        filtered.advance(.2, 1);
        double firstDrop = 1 - filtered.ratio();
        require(firstDrop > 0 && firstDrop < .01, "gradual departure from full population");
        double previous = filtered.ratio();
        filtered.advance(.2, 1);
        require(previous - filtered.ratio() > firstDrop, "eases into the decrease");
        for (int i = 0; i < 180; i++) {
            previous = filtered.ratio();
            filtered.advance(.2, 1);
            require(filtered.ratio() <= previous + 1e-10, "monotonic decrease for a fixed target");
        }
        near(filtered.ratio(), .2);
        for (int i = 0; i < 180; i++) {
            previous = filtered.ratio();
            filtered.advance(1, 1);
            require(filtered.ratio() >= previous - 1e-10, "monotonic recovery for a fixed target");
        }
        near(filtered.ratio(), 1);

        EnvironmentPopulation everyTick = new EnvironmentPopulation();
        EnvironmentPopulation skipped = new EnvironmentPopulation();
        for (int i = 0; i < 37; i++) everyTick.advance(.4, 1);
        skipped.advance(.4, 37);
        near(everyTick.ratio(), skipped.ratio());
    }

    private static void allocation() throws Exception {
        Files.createDirectories(WorldSystem.file().getParent());
        Files.writeString(WorldSystem.file(), "worldSystem.activeRadius=24\n");
        WorldSystem.load();
        require(WorldSystem.activeRadius == 24 && WorldSystem.environmentEnabled, "default on for an old config");
        require(WorldSystem.navigationBudget == 1 && !WorldSystem.footsteps, "old settings get budget and audio defaults");

        CrowdData.Emitter area = emitter("a", "region", 59, "minecraft:overworld");
        CrowdData.Emitter diffuser = emitter("b", "diffuser", 41, "minecraft:overworld");
        emitter("outside", "region", 500, "minecraft:the_nether");
        emitter("draft", "line", 50, "minecraft:overworld");
        emitter("disabled", "region", 50, "minecraft:overworld").enabled = false;

        ClientLevel level = CLIENT.level;
        require(WorldSystem.totalPlanned() == 100, "sunny daytime uses the configured total");
        level.dayTime = 18000;
        level.gameTime++;
        require(WorldSystem.totalPlanned() > 40, "time jump eases toward night");
        require(settle(level) == 40, "night budget");
        require(WorldSystem.count(area) == 24 && WorldSystem.count(diffuser) == 16, "proportional allocation");

        level.rainLevel = 1;
        require(settle(level) == 28, "rain compounds with time of day");
        int reads = level.biomeReads;
        WorldSystem.environmentPercent();
        WorldSystem.count(area);
        require(level.biomeReads == reads, "one weather sample per game tick");
        level.precipitation = Precipitation.SNOW;
        require(settle(level) == 20, "snowy midnight has the twenty percent minimum");
        level.precipitation = Precipitation.NONE;
        require(settle(level) == 40, "dry biomes do not get the rain reduction");

        WorldSystem.useEmitterCounts = false;
        WorldSystem.setMaxTotal(50);
        require(WorldSystem.totalPlanned() == 20, "environment applies to the custom base limit");
        require(WorldSystem.maxTotal == 50 && area.count == 59 && diffuser.count == 41, "base counts unchanged");
        WorldSystem.setMaxTotal(0);
        require(WorldSystem.totalPlanned() == 0, "explicit zero cap stays zero");
        WorldSystem.setMaxTotal(50);

        CLIENT.level = new ClientLevel();
        CLIENT.level.dimensionType = new DimensionType(false, true);
        CLIENT.level.dayTime = 18000;
        require(WorldSystem.totalPlanned() == 50, "skyless dimensions start at full budget without retained weather");
        CLIENT.level = level;
        require(WorldSystem.totalPlanned() == 20, "return to Overworld reapplies its conditions");

        WorldSystem.environmentEnabled = false;
        WorldSystem.save();
        level.gameTime++;
        require(WorldSystem.totalPlanned() < 50, "disabling eases toward the base budget");
        require(settle(level) == 50, "disabled restores the base budget");
        WorldSystem.navigationBudget = 4;
        WorldSystem.footsteps = true;
        WorldSystem.resetDefaults();
        require(WorldSystem.environmentEnabled, "reset restores the default switch state");
        require(WorldSystem.navigationBudget == 1 && !WorldSystem.footsteps, "reset restores budget and audio defaults");
        WorldSystem.environmentEnabled = false;
        WorldSystem.navigationBudget = 9;
        WorldSystem.footsteps = true;
        WorldSystem.save();
        require(WorldSystem.navigationBudget == 4, "safe upper limit for collision budget");
    }

    private static long settle(ClientLevel level) {
        for (int i = 0; i < 180; i++) {
            level.gameTime++;
            WorldSystem.totalPlanned();
        }
        return WorldSystem.totalPlanned();
    }

    private static CrowdData.Emitter emitter(String id, String type, int count, String dimension) {
        CrowdData.Emitter emitter = new CrowdData.Emitter(id, type);
        emitter.dim = dimension;
        emitter.count = count;
        CrowdData.EMITTERS.add(emitter);
        return emitter;
    }

    private static void near(double actual, double expected) {
        require(Math.abs(actual - expected) < 1e-10, "expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
