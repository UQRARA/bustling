"""Minimal external APIs for testing the compiled audio and device handling without a mod archive."""
from pathlib import Path
import sys

ROOT = Path(sys.argv[1])
SOURCES = {
'net.minecraft.resources.ResourceLocation': '''
public record ResourceLocation(String id) {
    public static ResourceLocation tryParse(String id) { return new ResourceLocation(id); }
    public String toString() { return id; }
}
''',
'net.minecraft.resources.ResourceKey': '''
public record ResourceKey<T>(ResourceLocation location) {
    public static <T> ResourceKey<T> create(ResourceKey<?> registry, ResourceLocation location) {
        return new ResourceKey<>(location);
    }
}
''',
'net.minecraft.core.registries.Registries': '''
import net.minecraft.resources.*;
public class Registries { public static final ResourceKey<?> DIMENSION = new ResourceKey<>(new ResourceLocation("dimension")); }
''',
'net.minecraft.core.BlockPos': '''
public record BlockPos(int x, int y, int z) {
    public static BlockPos containing(double x, double y, double z) {
        return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }
}
''',
'net.minecraft.core.Holder': '''
public interface Holder<T> {
    T value();
    record Reference<T>(T value) implements Holder<T> {}
}
''',
'net.minecraft.util.RandomSource': 'public class RandomSource {}',
'net.minecraft.sounds.SoundEvent': '''
import net.minecraft.resources.ResourceLocation;
public record SoundEvent(ResourceLocation location) {
    public ResourceLocation getLocation() { return location; }
}
''',
'net.minecraft.sounds.SoundSource': 'public enum SoundSource { MASTER, PLAYERS }',
'net.minecraft.sounds.SoundEvents': '''
import net.minecraft.core.Holder.Reference;
import net.minecraft.resources.ResourceLocation;
public class SoundEvents {
    private static SoundEvent event(String name) { return new SoundEvent(new ResourceLocation(name)); }
    public static final SoundEvent BOOK_PAGE_TURN = event("item.book.page_turn"), BOOK_PUT = event("item.book.put"),
        LEVER_CLICK = event("block.lever.click"), GLASS_BREAK = event("block.glass.break");
    public static final Reference<SoundEvent> NOTE_BLOCK_BELL = new Reference<>(event("block.note_block.bell")),
        NOTE_BLOCK_HAT = new Reference<>(event("block.note_block.hat")),
        NOTE_BLOCK_BIT = new Reference<>(event("block.note_block.bit")),
        NOTE_BLOCK_XYLOPHONE = new Reference<>(event("block.note_block.xylophone")),
        NOTE_BLOCK_SNARE = new Reference<>(event("block.note_block.snare"));
}
''',
'net.minecraft.client.resources.sounds.SoundInstance': '''
import net.minecraft.util.RandomSource;
public interface SoundInstance {
    enum Attenuation { NONE }
    static RandomSource createUnseededRandom() { return new RandomSource(); }
}
''',
'net.minecraft.client.resources.sounds.SimpleSoundInstance': '''
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
public class SimpleSoundInstance implements SoundInstance {
    public final ResourceLocation id;
    public final SoundSource category;
    public final float volume, pitch;
    public final boolean relative;
    public SimpleSoundInstance(ResourceLocation id, SoundSource category, float volume, float pitch,
                               RandomSource rng, boolean looping, int delay, Attenuation attenuation,
                               double x, double y, double z, boolean relative) {
        this.id = id; this.category = category; this.volume = volume; this.pitch = pitch; this.relative = relative;
    }
}
''',
'net.minecraft.client.sounds.SoundManager': '''
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
public class SoundManager {
    public final List<SimpleSoundInstance> sounds = new ArrayList<>();
    public void play(SoundInstance sound) { sounds.add((SimpleSoundInstance) sound); }
}
''',
'net.minecraft.client.Options': '''
import net.minecraft.sounds.SoundSource;
public class Options {
    public float master = 1, players = 1;
    public float getSoundSourceVolume(SoundSource source) { return source == SoundSource.MASTER ? master : players; }
}
''',
'net.minecraft.world.level.biome.Biome': '''
import net.minecraft.core.BlockPos;
public record Biome(Precipitation precipitation) {
    public enum Precipitation { NONE, RAIN, SNOW }
    public Precipitation getPrecipitationAt(BlockPos pos) { return precipitation; }
}
''',
'net.minecraft.world.level.levelgen.Heightmap': '''
public class Heightmap { public enum Types { MOTION_BLOCKING } }
''',
'net.minecraft.world.level.dimension.DimensionType': '''
public record DimensionType(boolean hasSkyLight, boolean hasFixedTime) {}
''',
'net.minecraft.client.multiplayer.ClientLevel': '''
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
public class ClientLevel extends Level {
    public ResourceKey<Level> key = new ResourceKey<>(new ResourceLocation("minecraft:overworld"));
    public DimensionType dimensionType = new DimensionType(true, false);
    public Biome.Precipitation precipitation = Biome.Precipitation.RAIN;
    public long gameTime, dayTime = 6000;
    public float rainLevel;
    public int biomeReads;
    public ResourceKey<Level> dimension() { return key; }
    public DimensionType dimensionType() { return dimensionType; }
    public long getGameTime() { return gameTime; }
    public long getDayTime() { return dayTime; }
    public float getRainLevel(float tickDelta) { return rainLevel; }
    public boolean hasChunkAt(BlockPos pos) { return true; }
    public BlockPos getHeightmapPos(Heightmap.Types type, BlockPos pos) { return pos; }
    public Holder<Biome> getBiome(BlockPos pos) { biomeReads++; return new Holder.Reference<>(new Biome(precipitation)); }
}
''',
'net.minecraft.client.player.LocalPlayer': '''
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
public class LocalPlayer extends Player {
    public BlockPos blockPosition() { return new BlockPos(0, 64, 0); }
    public double getX() { return 0; }
    public double getY() { return 64; }
    public double getZ() { return 0; }
}
''',
'net.minecraft.client.Minecraft': '''
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.sounds.SoundManager;
public class Minecraft {
    private static final Minecraft INSTANCE = new Minecraft();
    public ClientLevel level = new ClientLevel();
    public LocalPlayer player = new LocalPlayer();
    public final Options options = new Options();
    private final SoundManager sound = new SoundManager();
    public net.minecraft.client.multiplayer.ClientPacketListener getConnection() { return new net.minecraft.client.multiplayer.ClientPacketListener(); }
    public static Minecraft getInstance() { return INSTANCE; }
    public SoundManager getSoundManager() { return sound; }
}
''',
'net.minecraft.client.multiplayer.ClientPacketListener': '''
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
public class ClientPacketListener { public boolean hasChannel(CustomPacketPayload.Type<?> kind) { return true; } }
''',
'net.minecraft.world.item.Item': 'public class Item {}',
'net.minecraft.world.item.ItemStack': '''
public record ItemStack(Item item) {
    public Item getItem() { return item; }
}
''',
'net.minecraft.world.level.Level': 'public class Level {}',
'net.minecraft.world.level.block.state.BlockState': '''
import net.minecraft.world.level.block.Block;
public record BlockState(Block block) { public boolean is(Block value) { return block == value; } }
''',
'net.minecraft.world.level.block.Block': '''
import net.minecraft.world.level.block.state.BlockState;
public class Block {
    public static final int UPDATE_ALL = 3;
    public BlockState defaultBlockState() { return new BlockState(this); }
}
''',
'net.minecraft.world.level.block.Blocks': 'public class Blocks { public static final Block AIR = new Block(), SPAWNER = new Block(); }',
'net.minecraft.world.entity.player.Player': 'public class Player {}',
'net.minecraft.world.entity.player.Abilities': 'public class Abilities { public boolean mayBuild = true; }',
'net.minecraft.server.level.ServerPlayer': '''
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.item.*;
public class ServerPlayer extends Player {
    public final Abilities abilities = new Abilities();
    public ItemStack held = new ItemStack(new dev.bustling.WandItem());
    public final ServerLevel level = new ServerLevel();
    public final MinecraftServer server = new MinecraftServer(level);
    public boolean spectator;
    public double distance;
    public ItemStack getMainHandItem() { return held; }
    public ServerLevel serverLevel() { return level; }
    public MinecraftServer getServer() { return server; }
    public boolean isSpectator() { return spectator; }
    public Abilities getAbilities() { return abilities; }
    public double distanceToSqr(double x, double y, double z) { return distance; }
}
''',
'net.minecraft.server.MinecraftServer': '''
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
public record MinecraftServer(ServerLevel level) {
    public ServerLevel getLevel(ResourceKey<?> key) {
        return key.location().toString().equals(level.dimensionId) ? level : null;
    }
}
''',
'net.minecraft.server.level.ServerLevel': '''
import dev.bustling.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
public class ServerLevel extends Level {
    public boolean loaded = true, allowed = true, powered;
    public String dimensionId = "minecraft:overworld";
    public int reads, writes;
    public BlockState state = net.minecraft.world.level.block.Blocks.SPAWNER.defaultBlockState();
    public boolean hasChunkAt(BlockPos pos) { return loaded; }
    public boolean hasNeighborSignal(BlockPos pos) { return powered; }
    public boolean mayInteract(Player player, BlockPos pos) { return allowed; }
    public BlockState getBlockState(BlockPos pos) { reads++; return state; }
    public ResourceKey dimension() { return new ResourceKey(new ResourceLocation(dimensionId)); }
    public boolean setBlock(BlockPos pos, BlockState value, int flags) {
        writes++;
        state = value;
        ServerCrowdStore.removeDiffuser(DiffuserData.key(dimensionId, pos.x(), pos.y(), pos.z()));
        return true;
    }
}
''',
'net.neoforged.neoforge.event.tick.ServerTickEvent': '''
import net.minecraft.server.MinecraftServer;
public class ServerTickEvent {
    public record Post(MinecraftServer server) {
        public MinecraftServer getServer() { return server; }
    }
}
''',
'net.neoforged.bus.api.Event': 'public class Event {}',
'net.neoforged.bus.api.IEventBus': 'public interface IEventBus { <E extends Event> E post(E event); }',
'net.neoforged.neoforge.event.level.BlockEvent': '''
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.Event;
public class BlockEvent {
    public static class BreakEvent extends Event {
        public final Level level;
        public boolean canceled;
        public BreakEvent(Level level, BlockPos pos, BlockState state, Player player) { this.level = level; }
        public boolean isCanceled() { return canceled; }
    }
}
''',
'net.neoforged.neoforge.common.NeoForge': '''
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.*;
import net.neoforged.neoforge.event.level.BlockEvent.BreakEvent;
public class NeoForge {
    public static boolean cancel, replace;
    public static int events;
    public static final IEventBus EVENT_BUS = new IEventBus() {
        public <E extends Event> E post(E event) {
            if (event instanceof BreakEvent e) {
                events++;
                e.canceled = cancel;
                if (replace) ((ServerLevel) e.level).state = new Block().defaultBlockState();
            }
            return event;
        }
    };
}
''',
'net.neoforged.neoforge.registries.DeferredBlock': 'public record DeferredBlock(Object get) {}',
'net.neoforged.fml.loading.FMLPaths': '''
import java.nio.file.Path;
public enum FMLPaths {
    CONFIGDIR;
    public Path get() { return Path.of(System.getProperty("bustling.qa.config")); }
}
''',
'net.minecraft.network.protocol.common.custom.CustomPacketPayload': 'public interface CustomPacketPayload { record Type<T>(String id) {} }',
'net.neoforged.neoforge.network.PacketDistributor': '''
import java.util.ArrayList;
import java.util.List;
import dev.bustling.EditPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
public class PacketDistributor {
    public static final List<String> sent = new ArrayList<>();
    public static boolean connected = true;
    public static void sendToServer(CustomPacketPayload first, CustomPacketPayload... rest) {
        if (!connected) throw new IllegalStateException();
        sent.add(((EditPayload)first).json());
    }
}
''',
'dev.bustling.EditPayload': '''
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
public record EditPayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<EditPayload> TYPE = new CustomPacketPayload.Type<>("edit");
}
''',
'dev.bustling.ToolItems': '''
import net.minecraft.world.item.ItemStack;
public class ToolItems { public static boolean isRegion(ItemStack stack) { return stack.getItem() instanceof WandItem; } }
''',
'dev.bustling.ToolModels': 'public class ToolModels { static void refreshDevices() {} }',
'dev.bustling.WandItem': 'public class WandItem extends net.minecraft.world.item.Item {}',
'dev.bustling.WorldItems': '''
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredBlock;
public class WorldItems { public static final DeferredBlock DIFFUSER = new DeferredBlock(new Block()); }
''',
'dev.bustling.ToolConfig': '''
public class ToolConfig {
    public enum Mode { AREA, PATH, DELETE }
    public static Mode mode = Mode.AREA;
    public static boolean pushEnabled;
    public static int slowLevel;
    public static double pushRadius, size, groundMin, groundMax;
}
''',
'dev.bustling.CrowdData': '''
import java.util.ArrayList;
import java.util.List;
import com.google.gson.Gson;
public class CrowdData {
    public static List<Emitter> EMITTERS = new ArrayList<>();
    public static void applyJson(String json) { EMITTERS = new Gson().fromJson(json, Root.class).emitters; }
    public static String toJson() { Root root = new Root(); root.emitters = EMITTERS; return new Gson().toJson(root); }
    public static String worldKey() { return "qa-world"; }
    public static class Root { public List<Emitter> emitters = new ArrayList<>(); }
    public static class Emitter {
        public String id, type, dim, appearance;
        public double[] a,b;
        public double diffuserRadius, diffuserDecay, stepHeight;
        public int diffuserLimit, density, count, groundMode;
        public boolean enabled = true;
        public Boolean redstoneControlled;
        public List<double[]> path = new ArrayList<>();
        public List<MobWeight> mobs = new ArrayList<>();
        public List<?> groundBlocks;
        public Emitter(String id, String type) { this.id=id; this.type=type; }
        public boolean isLine() { return "line".equals(type); }
        public void normalizeType() {}
        public static class MobWeight {}
    }
}
''',
'dev.bustling.BustlingNet': '''
import java.util.Map;
import net.minecraft.server.MinecraftServer;
public class BustlingNet {
    public static int syncs;
    public static int powerSyncs;
    public static Map<String, Boolean> lastPower;
    public static void broadcast() { syncs++; }
    public static void broadcastDevicePower(MinecraftServer server, Map<String, Boolean> changes) {
        powerSyncs++;
        lastPower = Map.copyOf(changes);
    }
}
''',
'dev.bustling.DeviceBlocks': '''
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
public class DeviceBlocks {
    static void prepareSpawner(ServerLevel level, BlockPos pos) {}
    static String key(ServerLevel level, BlockPos pos) {
        return DiffuserData.key(level.dimensionId, pos.x(), pos.y(), pos.z());
    }
}
''',
}
for name, source in SOURCES.items():
    path = ROOT.joinpath(*name.split(".")).with_suffix(".java")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + source.strip() + "\n")
