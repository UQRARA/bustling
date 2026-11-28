package dev.bustling;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Server-owned metadata. Device changes follow the placed block's lifecycle. */
public final class ServerCrowdStore {
    private static final double MAX_DEVICE_REACH_SQUARED = 70 * 70;
    private static final Gson GSON = new Gson();
    private static CrowdData.Root data = new CrowdData.Root();
    private static boolean writable = true;
    private static final Map<String, CrowdData.Emitter> DEVICES = new LinkedHashMap<>();
    private static final Map<String, Boolean> PENDING_POWER = new LinkedHashMap<>();

    private record ClientEdit(long sequence, List<CrowdEdits.Change> changes, List<String> removedDiffusers) {}

    private ServerCrowdStore() {}

    private static Path legacyFile() {
        return FMLPaths.CONFIGDIR.get().resolve("bustling/server").resolve(CrowdData.worldKey()).resolve("data.json");
    }

    public static Path file() {
        var server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? legacyFile() : server.getWorldPath(LevelResource.ROOT).resolve("data/bustling.json");
    }

    public static String toJson() {
        return GSON.toJson(data);
    }

    static String networkJson(long acknowledged) {
        return networkJson(acknowledged, true);
    }

    static String networkJson(long acknowledged, boolean accepted) {
        return GSON.toJson(new CrowdEdits.Snapshot(data.emitters, CrowdEdits.PROTOCOL, acknowledged, accepted));
    }

    static boolean writable() {
        return writable;
    }

    private static CrowdData.Root decodeSave(String input) {
        CrowdData.Root next = GSON.fromJson(input, CrowdData.Root.class);
        if (next == null || next.emitters == null) throw new IllegalArgumentException("Missing crowd layouts");
        var keys = new HashSet<CrowdEdits.Key>();
        for (CrowdData.Emitter emitter : next.emitters) {
            if (emitter == null) throw new IllegalArgumentException("Null crowd layout");
            emitter.normalizeType();
            if (emitter.dim == null || emitter.dim.isBlank()) emitter.dim = "minecraft:overworld";
            if (!CrowdEdits.valid(emitter)) throw new IllegalArgumentException("Invalid crowd layout " + emitter.id);
            while (!keys.add(CrowdEdits.key(emitter))) {
                if (DiffuserData.isDiffuser(emitter)) throw new IllegalArgumentException("Duplicate diffusion device");
                emitter.id = emitter.type + "-" + UUID.randomUUID();
            }
            if (DiffuserData.isDiffuser(emitter)) {
                DiffuserData.refresh(emitter);
                if (Boolean.TRUE.equals(emitter.redstoneControlled)) emitter.enabled = false;
            }
        }
        return next;
    }

    private static void install(CrowdData.Root next) {
        data = next;
        DEVICES.clear();
        for (CrowdData.Emitter emitter : data.emitters) {
            if (DiffuserData.isDiffuser(emitter)) DEVICES.put(emitter.id, emitter);
        }
        PENDING_POWER.keySet().retainAll(DEVICES.keySet());
    }

    public static void applyJson(String input) {
        try {
            install(decodeSave(input));
        } catch (RuntimeException error) {
            System.getLogger("Bustling").log(System.Logger.Level.WARNING, "Rejected malformed crowd metadata", error);
        }
    }

    public static void applyClientJson(String input) {
        handleClientEdit(input, null);
    }

    public static void handleClientEdit(String input, ServerPlayer sender) {
        if (sender == null) return;
        long sequence = 0;
        boolean accepted = false;
        try {
            ClientEdit packet = GSON.fromJson(input, ClientEdit.class);
            if (packet == null) return;
            sequence = packet.sequence();
            if (writable && packet.removedDiffusers() != null && packet.removedDiffusers().size() == 1) {
                removeDeviceBlock(sender, packet.removedDiffusers().getFirst());
                return;
            }
            accepted = writable && sequence > 0 && packet.changes() != null && !packet.changes().isEmpty()
                    && packet.changes().size() <= CrowdEdits.MAX_CHANGES && applyChanges(sender, packet.changes());
            if (accepted) BustlingNet.broadcastExcept(sender);
        } catch (RuntimeException | IOException error) {
            System.getLogger("Bustling").log(System.Logger.Level.WARNING, "Rejected malformed crowd edit", error);
        }
        BustlingNet.acknowledge(sender, sequence, accepted);
    }

    private static boolean applyChanges(ServerPlayer sender, List<CrowdEdits.Change> changes) throws IOException {
        if (sender.isSpectator() || !sender.getAbilities().mayBuild) return false;
        var current = new LinkedHashMap<CrowdEdits.Key, CrowdData.Emitter>();
        for (CrowdData.Emitter emitter : data.emitters) current.put(CrowdEdits.key(emitter), emitter);
        var seen = new HashSet<CrowdEdits.Key>();
        var deviceChanges = new ArrayList<CrowdData.Emitter>();
        for (CrowdEdits.Change change : changes) {
            if (change == null || !CrowdEdits.validKey(change.key()) || !seen.add(change.key())) return false;
            CrowdData.Emitter previous = current.get(change.key());
            if (!Objects.equals(change.before(), CrowdEdits.value(previous))) return false;
            CrowdData.Emitter next = change.after() == null ? null : GSON.fromJson(change.after(), CrowdData.Emitter.class);
            if (next != null) {
                next.normalizeType();
                if (!CrowdEdits.valid(next) || !change.key().equals(CrowdEdits.key(next))) return false;
            }
            if (previous == null && next == null) return false;
            if (!canEdit(sender, previous == null ? next : previous) || next != null && !canEdit(sender, next)) return false;
            if (DiffuserData.isDiffuser(previous) || DiffuserData.isDiffuser(next)) {
                if (!DiffuserData.isDiffuser(previous) || !DiffuserData.isDiffuser(next)) return false;
                var device = GSON.fromJson(GSON.toJsonTree(previous), CrowdData.Emitter.class);
                CrowdSettings.applyDeviceSettings(device, next);
                if (next.redstoneControlled != null) device.redstoneControlled = next.redstoneControlled;
                deviceChanges.add(device);
                current.put(change.key(), device);
            } else if (next == null) {
                current.remove(change.key());
            } else {
                current.put(change.key(), next);
            }
        }
        var changedPowerModes = new ArrayList<String>();
        for (CrowdData.Emitter device : deviceChanges) {
            CrowdData.Emitter previous = DEVICES.get(device.id);
            if (Boolean.TRUE.equals(device.redstoneControlled) != Boolean.TRUE.equals(previous.redstoneControlled)) {
                device.enabled = !Boolean.TRUE.equals(device.redstoneControlled) || readDevicePower(device, sender);
                changedPowerModes.add(device.id);
            }
        }
        CrowdData.Root next = new CrowdData.Root();
        next.emitters = new ArrayList<>(current.values());
        persist(next);
        install(next);
        for (String id : changedPowerModes) PENDING_POWER.remove(id);
        return true;
    }

    private static boolean canEdit(ServerPlayer sender, CrowdData.Emitter emitter) {
        ServerLevel level = sender.serverLevel();
        if (!level.dimension().location().toString().equals(emitter.dim)) return false;
        if (DiffuserData.isDiffuser(emitter)) {
            BlockPos pos = BlockPos.containing(emitter.a[0], emitter.a[1] - .5, emitter.a[2]);
            return sender.distanceToSqr(emitter.a[0], emitter.a[1] - .5, emitter.a[2]) <= MAX_DEVICE_REACH_SQUARED
                    && level.hasChunkAt(pos) && level.mayInteract(sender, pos) && level.getBlockState(pos).is(Blocks.SPAWNER);
        }
        List<double[]> points = emitter.isLine() && !emitter.path.isEmpty() ? emitter.path
                : emitter.polygon != null && !emitter.polygon.isEmpty() ? emitter.polygon : List.of(emitter.a, emitter.b);
        return points.stream().allMatch(point -> level.mayInteract(sender, BlockPos.containing(point[0], point[1], point[2])));
    }

    private static boolean readDevicePower(CrowdData.Emitter device, ServerPlayer sender) {
        if (sender == null || sender.getServer() == null) return false;
        ResourceLocation dimension = ResourceLocation.tryParse(device.dim);
        if (dimension == null) return false;
        ServerLevel level = sender.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        BlockPos pos = BlockPos.containing(device.a[0], device.a[1] - .5, device.a[2]);
        if (level == null || !level.hasChunkAt(pos) || !level.getBlockState(pos).is(Blocks.SPAWNER)) return false;
        DeviceBlocks.prepareSpawner(level, pos);
        return level.hasNeighborSignal(pos);
    }

    public static void refreshDevicePower(ServerLevel level, BlockPos pos) {
        CrowdData.Emitter device = DEVICES.get(DeviceBlocks.key(level, pos));
        if (device == null || !Boolean.TRUE.equals(device.redstoneControlled)) return;
        boolean powered = level.hasNeighborSignal(pos);
        if (device.enabled == powered) return;
        device.enabled = powered;
        PENDING_POWER.put(device.id, powered);
    }

    static void flushDevicePower(ServerTickEvent.Post event) {
        if (PENDING_POWER.isEmpty()) return;
        BustlingNet.broadcastDevicePower(event.getServer(), PENDING_POWER);
        PENDING_POWER.clear();
    }

    private static void removeDeviceBlock(ServerPlayer sender, String id) {
        if (id == null || sender.isSpectator() || !sender.getAbilities().mayBuild
                || !ToolItems.isRegion(sender.getMainHandItem())) return;
        ServerLevel level = sender.serverLevel();
        String dimension = level.dimension().location().toString();
        for (CrowdData.Emitter e : data.emitters) {
            if (!id.equals(e.id) || !DiffuserData.isDiffuser(e) || !DiffuserData.valid(e)
                    || !dimension.equals(e.dim)) continue;
            double centerY = e.a[1] - .5;
            if (sender.distanceToSqr(e.a[0], centerY, e.a[2]) > MAX_DEVICE_REACH_SQUARED) return;
            BlockPos pos = BlockPos.containing(e.a[0], centerY, e.a[2]);
            if (!level.hasChunkAt(pos) || !level.mayInteract(sender, pos)) return;
            BlockState state = level.getBlockState(pos);
            if (!state.is(Blocks.SPAWNER)) return;
            if (NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, sender)).isCanceled()) return;
            if (level.getBlockState(pos).is(Blocks.SPAWNER)
                    && level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)) {
                removeDiffuser(id);
            }
            return;
        }
    }

    public static void placeDiffuser(String dimension, int x, int y, int z) {
        String id = DiffuserData.key(dimension, x, y, z);
        if (!writable || DEVICES.containsKey(id)) return;
        CrowdData.Emitter device = DiffuserData.create(dimension, x, y, z);
        DEVICES.put(id, device);
        data.emitters.add(device);
        save();
    }

    public static void removeDiffuser(String id) {
        if (!writable || DEVICES.remove(id) == null) return;
        PENDING_POWER.remove(id);
        if (data.emitters.removeIf(e -> DiffuserData.isDiffuser(e) && id.equals(e.id))) save();
    }

    public static boolean hasDevice(ServerLevel level, BlockPos pos) {
        return DEVICES.containsKey(DeviceBlocks.key(level, pos));
    }

    public static void load() {
        writable = false;
        install(new CrowdData.Root());
        PENDING_POWER.clear();
        Path path = file();
        try {
            if (!Files.notExists(path)) {
                install(decodeSave(Files.readString(path, StandardCharsets.UTF_8)));
            } else {
                Path legacy = legacySource(path);
                if (legacy != null) install(decodeSave(Files.readString(legacy, StandardCharsets.UTF_8)));
                persist(data);
                if (legacy != null) {
                    try {
                        Files.writeString(migrationMarker(legacy), path.toString(), StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE_NEW);
                    } catch (IOException error) {
                        System.getLogger("Bustling").log(System.Logger.Level.WARNING,
                                "Could not mark migrated crowd metadata " + legacy, error);
                    }
                }
            }
            writable = true;
        } catch (IOException | RuntimeException error) {
            System.getLogger("Bustling").log(System.Logger.Level.ERROR,
                    "Could not load crowd metadata. Writes are disabled to protect " + path, error);
        }
    }

    private static Path migrationMarker(Path source) {
        return source.resolveSibling(source.getFileName() + ".migrated");
    }

    private static Path legacySource(Path target) {
        Path config = FMLPaths.CONFIGDIR.get();
        for (Path candidate : List.of(legacyFile(), config.resolve("bustling.json"), config.resolve("crowdflow.json"))) {
            if (!candidate.equals(target) && Files.isRegularFile(candidate) && Files.notExists(migrationMarker(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private static void persist(CrowdData.Root snapshot) throws IOException {
        Path path = file();
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling("data.json.tmp");
        Files.writeString(temp, GSON.toJson(snapshot), StandardCharsets.UTF_8);
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void save() {
        if (!writable) return;
        try {
            persist(data);
            BustlingNet.broadcast();
        } catch (IOException error) {
            System.getLogger("Bustling").log(System.Logger.Level.ERROR, "Could not save crowd metadata", error);
        }
    }
}
