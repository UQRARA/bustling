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
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-owned metadata. Device changes follow the placed block's lifecycle. */
public final class ServerCrowdStore {
    private static final double MAX_DEVICE_REACH_SQUARED = 70 * 70;
    private static final Gson GSON = new Gson();
    private static CrowdData.Root data = new CrowdData.Root();
    private static final Map<String, CrowdData.Emitter> DEVICES = new LinkedHashMap<>();
    private static final Map<String, Boolean> PENDING_POWER = new LinkedHashMap<>();

    private static final class ClientEdit {
        List<CrowdData.Emitter> emitters;
        List<CrowdData.Emitter> diffuserEdits;
        List<String> removedDiffusers;
    }

    private ServerCrowdStore() {}
    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("bustling/server").resolve(CrowdData.worldKey()).resolve("data.json");
    }
    public static String toJson() {
        return GSON.toJson(data);
    }

    public static void applyJson(String input) {
        try {
            CrowdData.Root next = GSON.fromJson(input, CrowdData.Root.class);
            if (next == null || next.emitters == null || next.emitters.stream().anyMatch(Objects::isNull)) return;
            next.emitters = new ArrayList<>(next.emitters);
            next.emitters.removeIf(e -> DiffuserData.isDiffuser(e) && !DiffuserData.valid(e));
            for (CrowdData.Emitter e : next.emitters) {
                e.normalizeType();
                if (DiffuserData.isDiffuser(e)) {
                    DiffuserData.refresh(e);
                    if (Boolean.TRUE.equals(e.redstoneControlled)) e.enabled = false;
                }
            }
            data = next;
            DEVICES.clear();
            PENDING_POWER.clear();
            for (CrowdData.Emitter e : data.emitters) if (DiffuserData.isDiffuser(e)) DEVICES.put(e.id, e);
        } catch (RuntimeException error) {
            System.getLogger("Bustling").log(System.Logger.Level.WARNING, "Rejected malformed crowd metadata", error);
        }
    }

    public static void applyClientJson(String input) {
        handleClientEdit(input, null);
    }

    public static void handleClientEdit(String input, ServerPlayer sender) {
        try {
            ClientEdit packet = GSON.fromJson(input, ClientEdit.class);
            if (packet == null) return;
            if (packet.removedDiffusers != null && !packet.removedDiffusers.isEmpty()) {
                if (sender != null && packet.removedDiffusers.size() == 1) {
                    removeDeviceBlock(sender, packet.removedDiffusers.getFirst());
                }
                return;
            }
            if (packet.emitters == null || packet.emitters.stream().anyMatch(Objects::isNull)) return;
            if (packet.diffuserEdits != null) {
                for (CrowdData.Emitter edit : packet.diffuserEdits) {
                    if (!DiffuserData.isDiffuser(edit)) continue;
                    CrowdData.Emitter e = DEVICES.get(edit.id);
                    if (e == null || !e.dim.equals(edit.dim)) continue;
                    CrowdSettings.applyDeviceSettings(e, edit);
                    if (edit.redstoneControlled != null && !edit.redstoneControlled.equals(e.redstoneControlled)) {
                        e.redstoneControlled = edit.redstoneControlled;
                        e.enabled = !edit.redstoneControlled || readDevicePower(e, sender);
                        PENDING_POWER.remove(e.id);
                    }
                }
            }
            CrowdData.Root next = new CrowdData.Root();
            next.emitters = new ArrayList<>();
            for (CrowdData.Emitter e : packet.emitters) {
                if (DiffuserData.isDiffuser(e) || (e.id != null && e.id.startsWith("diffuser:"))) continue;
                e.normalizeType();
                next.emitters.add(e);
            }
            next.emitters.addAll(DEVICES.values());
            data = next;
            if (sender != null) save();
        } catch (RuntimeException error) {
            System.getLogger("Bustling").log(System.Logger.Level.WARNING, "Rejected malformed crowd edit", error);
        }
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
        if (DEVICES.containsKey(id)) return;
        CrowdData.Emitter device = DiffuserData.create(dimension, x, y, z);
        DEVICES.put(id, device);
        data.emitters.add(device);
        save();
    }

    public static void removeDiffuser(String id) {
        if (DEVICES.remove(id) == null) return;
        PENDING_POWER.remove(id);
        if (data.emitters.removeIf(e -> DiffuserData.isDiffuser(e) && id.equals(e.id))) save();
    }

    public static boolean hasDevice(ServerLevel level, BlockPos pos) {
        return DEVICES.containsKey(DeviceBlocks.key(level, pos));
    }

    public static void load() {
        data = new CrowdData.Root();
        DEVICES.clear();
        PENDING_POWER.clear();
        Path path = file();
        try {
            if (Files.exists(path)) applyJson(Files.readString(path, StandardCharsets.UTF_8));
            else {
                for (String legacy : new String[]{"bustling.json", "crowdflow.json"}) {
                    Path old = FMLPaths.CONFIGDIR.get().resolve(legacy);
                    if (Files.exists(old)) {
                        applyJson(Files.readString(old, StandardCharsets.UTF_8));
                        break;
                    }
                }
                persist();
            }
        } catch (IOException error) {
            System.getLogger("Bustling").log(System.Logger.Level.ERROR, "Could not load crowd metadata", error);
        }
    }

    private static void persist() throws IOException {
        Path path = file();
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling("data.json.tmp");
        Files.writeString(temp, toJson(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void save() {
        try {
            persist();
            BustlingNet.broadcast();
        } catch (IOException error) {
            System.getLogger("Bustling").log(System.Logger.Level.ERROR, "Could not save crowd metadata", error);
        }
    }
}
