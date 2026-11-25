package dev.bustling;

import java.io.IOException;
import java.io.Reader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.fml.loading.FMLPaths;

/** Local world-panel preferences. No setting here is included in server metadata. */
public final class WorldSystem {
    public static boolean enabled = true;
    public static boolean useEmitterCounts = true;
    public static boolean shadows = false;
    public static boolean activeEnabled = true;
    public static boolean environmentEnabled = true;
    public static boolean avoidBlocks = true, avoidPlayers = true;
    public static int playerRadius = 6;
    public static int maxTotal = 512;
    public static int activeRadius = 12;
    public static double standingFraction = .35;
    public static final double DEFAULT_STANDING_FRACTION = .35;
    public static final int MAX_ACTIVE_RADIUS = 64;
    public static final int MAX_TOTAL = 1_000_000;

    /** Installed by client init; item/registry classes stay safe on dedicated servers. */
    public static Runnable onRuntimeReset;
    private static boolean loaded;
    private static boolean appliedEnabled = enabled, appliedOriginal = useEmitterCounts;
    private static int appliedMax = maxTotal;
    private static double appliedStanding = DEFAULT_STANDING_FRACTION;
    private static final EnvironmentPopulation ENVIRONMENT = new EnvironmentPopulation();
    private static ClientLevel environmentWorld;
    private static long environmentTick;
    private static ClientLevel allocationWorld;
    private static long allocationTick = Long.MIN_VALUE;
    private static final List<CrowdData.Emitter> GROUPS = new ArrayList<>();
    private static final Map<CrowdData.Emitter, Integer> ALLOCATION = new IdentityHashMap<>();
    private static long requestedTotal;
    private static long plannedTotal = -1;

    private WorldSystem() {}

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("bustling-world.properties");
    }

    public static void normalize() {
        playerRadius = Math.max(1, Math.min(30, playerRadius));
        activeRadius = Math.max(1, Math.min(MAX_ACTIVE_RADIUS, activeRadius));
        maxTotal = Math.max(0, Math.min(MAX_TOTAL, maxTotal));
        standingFraction = Double.isFinite(standingFraction)
                ? Math.max(0, Math.min(1, standingFraction)) : .35;
    }

    private static boolean bool(Properties p, String name, boolean fallback) {
        String value = p.getProperty("worldSystem." + name);
        if (value == null) return fallback;
        if (value.trim().equalsIgnoreCase("true")) return true;
        if (value.trim().equalsIgnoreCase("false")) return false;
        return fallback;
    }

    private static int integer(Properties p, String name, int fallback) {
        try { return Integer.parseInt(p.getProperty("worldSystem." + name, "").trim()); }
        catch (NumberFormatException bad) { return fallback; }
    }

    public static void load() {
        if (loaded) return;
        loaded = true;
        Path path = file();
        if (Files.exists(path)) {
            Properties p = new Properties();
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                p.load(reader);
                enabled = bool(p, "enabled", enabled);
                useEmitterCounts = bool(p, "useEmitterCounts", useEmitterCounts);
                shadows = bool(p, "shadows", shadows);
                activeEnabled = bool(p, "activeEnabled", activeEnabled);
                environmentEnabled = bool(p, "environmentEnabled", true);
                avoidBlocks = bool(p, "avoidBlocks", avoidBlocks);
                avoidPlayers = bool(p, "avoidPlayers", avoidPlayers);
                playerRadius = integer(p, "playerRadius", playerRadius);
                maxTotal = integer(p, "maxTotal", maxTotal);
                activeRadius = integer(p, "activeRadius", activeRadius);
                try { standingFraction = Double.parseDouble(p.getProperty("worldSystem.standingFraction", Double.toString(standingFraction))); }
                catch (NumberFormatException bad) { standingFraction = .35; }
            } catch (IOException | IllegalArgumentException bad) {
                System.getLogger("Bustling").log(System.Logger.Level.WARNING,
                        "Could not read local world-panel settings: " + bad);
            }
        }
        normalize();
        rememberApplied();
        invalidate();
        if (!Files.exists(path)) save();
    }

    private static void rememberApplied() {
        appliedEnabled = enabled;
        appliedOriginal = useEmitterCounts;
        appliedMax = maxTotal;
        appliedStanding = effectiveStandingFraction();
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        save();
    }

    /** Global overrides are locked while the original region/path counts are selected. */
    public static void setMaxTotal(int value) {
        if (useEmitterCounts) return;
        maxTotal = value;
        save();
    }

    public static void setStandingFraction(double value) {
        if (useEmitterCounts) return;
        standingFraction = value;
        save();
    }

    /** Original mode has no per-emitter standing setting, so it uses the neutral default. */
    public static double effectiveStandingFraction() {
        if (useEmitterCounts) return DEFAULT_STANDING_FRACTION;
        return Double.isFinite(standingFraction)
                ? Math.max(0, Math.min(1, standingFraction)) : DEFAULT_STANDING_FRACTION;
    }

    /** One transaction: restore all local defaults, persist once, clear runtime once. */
    public static void resetDefaults() {
        enabled = true;
        useEmitterCounts = true;
        shadows = false;
        activeEnabled = true;
        environmentEnabled = true;
        maxTotal = 512;
        activeRadius = 12;
        standingFraction = DEFAULT_STANDING_FRACTION;
        save(true);
    }

    public static void save() {
        save(false);
    }

    private static void save(boolean forceReset) {
        normalize();
        boolean reset = forceReset || appliedEnabled != enabled || appliedOriginal != useEmitterCounts
                || (!useEmitterCounts && appliedMax != maxTotal)
                || Double.compare(appliedStanding, effectiveStandingFraction()) != 0;
        rememberApplied();
        invalidate();
        if (reset && onRuntimeReset != null) onRuntimeReset.run();
        String text = "# Bustling client-local world panel; restart the client after editing this file.\n"
                + "# These preferences are never sent to the server.\n"
                + "worldSystem.enabled=" + enabled + "\n"
                + "# Original-count mode locks global maxTotal and standingFraction overrides.\n"
                + "worldSystem.useEmitterCounts=" + useEmitterCounts + "\n"
                + "# maxTotal applies only when useEmitterCounts=false; 0 hides all crowds.\n"
                + "worldSystem.maxTotal=" + maxTotal + "\n"
                + "# Active simulation radius: 1..64 blocks, measured from the local player.\n"
                + "worldSystem.activeEnabled=" + activeEnabled + "\n"
                + "worldSystem.activeRadius=" + activeRadius + "\n"
                + "# Scale the planned population with daylight and local precipitation.\n"
                + "worldSystem.environmentEnabled=" + environmentEnabled + "\n"
                + "# Shadows are off by default. Shader packs control their final appearance.\n"
                + "worldSystem.shadows=" + shadows + "\n"
                + "worldSystem.avoidBlocks=" + avoidBlocks + "\n"
                + "worldSystem.avoidPlayers=" + avoidPlayers + "\n"
                + "worldSystem.playerRadius=" + playerRadius + "\n"
                + "# Custom-mode standing share: 0..1; preserved but inactive in original-count mode.\n"
                + "# Original-count mode uses the fixed neutral standing share 0.35.\n"
                + "worldSystem.standingFraction=" + standingFraction + "\n";
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException bad) {
            System.getLogger("Bustling").log(System.Logger.Level.WARNING,
                    "Could not save local world-panel settings: " + bad);
        }
    }

    public static void invalidate() {
        allocationTick = Long.MIN_VALUE;
        allocationWorld = null;
        GROUPS.clear();
        ALLOCATION.clear();
    }

    public static void resetEnvironment() {
        environmentWorld = null;
    }

    public static boolean active(double x, double y, double z) {
        if (!enabled || !activeEnabled) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        double dx = x - mc.player.getX(), dy = y - mc.player.getY(), dz = z - mc.player.getZ();
        int radius = Math.max(1, Math.min(MAX_ACTIVE_RADIUS, activeRadius));
        return dx * dx + dy * dy + dz * dz <= (double) radius * radius;
    }

    private static long mix(long n) {
        n = (n ^ (n >>> 30)) * -4658895280553007687L;
        n = (n ^ (n >>> 27)) * -7723592293110705685L;
        return n ^ (n >>> 31);
    }

    public static boolean standing(CrowdData.Emitter emitter, int index) {
        double fraction = effectiveStandingFraction();
        if (fraction <= 0) return false;
        if (fraction >= 1) return true;
        long seed = (long) (emitter.id == null ? 0 : emitter.id.hashCode()) * 7919L
                + (long) index * 104729L + 0x62DB43A19L;
        return (mix(seed) >>> 11) * 0x1.0p-53 < fraction;
    }

    private record Share(CrowdData.Emitter emitter, int count, long remainder) {
        static Share of(CrowdData.Emitter emitter, long limit, long total) {
            if (limit <= Long.MAX_VALUE / emitter.count) {
                long scaled = limit * emitter.count;
                return new Share(emitter, (int) (scaled / total), scaled % total);
            }
            BigInteger scaled = BigInteger.valueOf(limit).multiply(BigInteger.valueOf(emitter.count));
            BigInteger[] share = scaled.divideAndRemainder(BigInteger.valueOf(total));
            return new Share(emitter, share[0].intValueExact(), share[1].longValueExact());
        }
    }

    private static double environmentTarget(ClientLevel level) {
        var dimension = level.dimensionType();
        if (!environmentEnabled || !dimension.hasSkyLight()) return 1.0;
        double daylight = dimension.hasFixedTime() ? 1.0 : EnvironmentPopulation.daylight(level.getDayTime());
        float rain = level.getRainLevel(1.0f);
        var player = Minecraft.getInstance().player;
        if (rain <= 0 || player == null || !level.hasChunkAt(player.blockPosition())) return daylight;
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, player.blockPosition());
        double wetRatio = switch (level.getBiome(surface).value().getPrecipitationAt(surface)) {
            case RAIN -> 0.7;
            case SNOW -> 0.5;
            case NONE -> 1.0;
        };
        return daylight * EnvironmentPopulation.precipitation(rain, wetRatio);
    }

    private static double environmentRatio(ClientLevel level, long tick) {
        if (environmentWorld != level) {
            environmentWorld = level;
            environmentTick = tick;
            ENVIRONMENT.reset(environmentTarget(level));
        } else if (environmentTick != tick) {
            ENVIRONMENT.advance(environmentTarget(level), Math.max(1, tick - environmentTick));
            environmentTick = tick;
        }
        return ENVIRONMENT.ratio();
    }

    public static int environmentPercent() {
        ClientLevel level = Minecraft.getInstance().level;
        return !enabled || level == null ? 100 : (int) Math.round(100 * environmentRatio(level, level.getGameTime()));
    }

    /** Total planned display population for the panel, excluding other dimensions. */
    public static long totalPlanned() {
        ClientLevel level = Minecraft.getInstance().level;
        if (!enabled || level == null) return 0;
        updateAllocation(level);
        return plannedTotal;
    }

    /** Deterministic proportional cap across enabled groups in the current dimension. */
    public static int count(CrowdData.Emitter emitter) {
        if (!enabled || emitter == null || !emitter.enabled || emitter.count <= 0) return 0;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return 0;
        updateAllocation(level);
        return ALLOCATION.getOrDefault(emitter, 0);
    }

    private static void updateAllocation(ClientLevel level) {
        long tick = level.getGameTime();
        if (allocationWorld != level) {
            allocationWorld = level;
            allocationTick = Long.MIN_VALUE;
            plannedTotal = -1;
            requestedTotal = 0;
            GROUPS.clear();
            String dimension = level.dimension().location().toString();
            for (CrowdData.Emitter e : CrowdData.EMITTERS) {
                if (e == null || !e.enabled || e.count <= 0) continue;
                boolean legacyDimension = useEmitterCounts && (e.dim == null || e.dim.isBlank());
                if (!legacyDimension && !dimension.equals(e.dim)) continue;
                if (e.isLine() && (e.path == null || e.path.size() < 2)) continue;
                GROUPS.add(e);
                requestedTotal += e.count;
            }
        }
        if (allocationTick == tick) return;
        allocationTick = tick;
        long baseline = useEmitterCounts ? requestedTotal : Math.min(requestedTotal, Math.clamp(maxTotal, 0, MAX_TOTAL));
        long limit = Math.round(baseline * environmentRatio(level, tick));
        if (limit == plannedTotal) return;
        plannedTotal = limit;
        ALLOCATION.clear();
        if (limit == 0) return;
        if (limit == requestedTotal) {
            for (CrowdData.Emitter emitter : GROUPS) ALLOCATION.put(emitter, emitter.count);
            return;
        }

        List<Share> shares = new ArrayList<>(GROUPS.size());
        long used = 0;
        for (CrowdData.Emitter emitter : GROUPS) {
            Share share = Share.of(emitter, limit, requestedTotal);
            shares.add(share);
            ALLOCATION.put(emitter, share.count());
            used += share.count();
        }
        shares.sort(Comparator.comparingLong(Share::remainder).reversed()
                .thenComparing(share -> share.emitter().id == null ? "" : share.emitter().id));
        for (int i = 0; i < limit - used; i++) {
            Share share = shares.get(i);
            ALLOCATION.put(share.emitter(), share.count() + 1);
        }
    }
}
