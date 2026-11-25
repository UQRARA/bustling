package dev.bustling;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Display locally placed devices if the server only reports their vanilla carrier. */
public final class ClientDevicePlacements {
    private static final long CONFIRMATION_NANOS = 5_000_000_000L;
    private static final Map<Long, Long> pending = new ConcurrentHashMap<>();
    private static final Set<Long> placed = ConcurrentHashMap.newKeySet();
    private static volatile ClientLevel world;

    private ClientDevicePlacements() {}

    static void expect(ClientLevel level, BlockPlaceContext context) {
        if (!context.canPlace()) return;
        BlockPos pos = context.getClickedPos();
        if (level.getBlockState(pos).is(Blocks.SPAWNER)) return;
        if (world != level) {
            clear();
            world = level;
        }
        long now = System.nanoTime();
        pending.values().removeIf(deadline -> now - deadline >= 0);
        pending.put(pos.asLong(), now + CONFIRMATION_NANOS);
    }

    public static void confirmed(ClientLevel level, BlockPos pos, BlockState state) {
        if (world != level) return;
        long key = pos.asLong();
        Long deadline = pending.get(key);
        boolean changed;
        if (state.is(Blocks.SPAWNER)) {
            changed = deadline != null && System.nanoTime() - deadline < 0 && placed.add(key);
        } else {
            changed = placed.remove(key) || deadline != null;
        }
        pending.remove(key);
        if (changed) Minecraft.getInstance().levelRenderer.setSectionDirtyWithNeighbors(
                pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    static boolean contains(ClientLevel level, BlockPos pos) {
        if (world != level) return false;
        long key = pos.asLong();
        if (placed.contains(key)) return true;
        Long deadline = pending.get(key);
        return deadline != null && System.nanoTime() - deadline < 0;
    }

    static void clear() {
        world = null;
        pending.clear();
        placed.clear();
    }
}
