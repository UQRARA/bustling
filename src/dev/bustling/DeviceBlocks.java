package dev.bustling;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

/** Marked devices share the vanilla spawner block but have spawning disabled. */
public final class DeviceBlocks {
    private DeviceBlocks() {}

    public static InteractionResult place(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level) || !ToolItems.isDevice(context.getItemInHand())) {
            return CommonHooks.onPlaceItemIntoWorld(context);
        }
        BlockPos pos = new BlockPlaceContext(context).getClickedPos();
        InteractionResult result = CommonHooks.onPlaceItemIntoWorld(context);
        if (result.consumesAction() && level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner) {
            disableSpawner(level, pos, spawner);
            ServerCrowdStore.placeDiffuser(level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
        }
        return result;
    }

    static void prepareSpawner(ServerLevel level, BlockPos pos) {
        // Older custom devices may lack a block entity until first accessed after migration.
        if (level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner) disableSpawner(level, pos, spawner);
    }

    public static void disableSpawner(ServerLevel level, BlockPos pos, SpawnerBlockEntity spawner) {
        CompoundTag options = new CompoundTag();
        options.putShort("MinSpawnDelay", (short) 200);
        options.putShort("MaxSpawnDelay", (short) 800);
        options.putShort("SpawnCount", (short) 0);
        options.putShort("MaxNearbyEntities", (short) 0);
        options.putShort("RequiredPlayerRange", (short) 0);
        options.put("SpawnPotentials", new ListTag());
        spawner.getSpawner().load(level, pos, options);
        spawner.setChanged();
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
    }

    public static void replaced(ServerLevel level, BlockPos pos) {
        if (!ServerCrowdStore.hasDevice(level, pos)) return;
        var server = level.getServer();
        BlockPos target = pos.immutable();
        // Placement events can roll back snapshots before the world edit is approved.
        server.tell(new TickTask(server.getTickCount(), () -> {
            if (level.hasChunkAt(target) && !level.getBlockState(target).is(Blocks.SPAWNER)) {
                ServerCrowdStore.removeDiffuser(key(level, target));
            }
        }));
    }

    static void drop(BlockDropsEvent event) {
        if (!event.getState().is(Blocks.SPAWNER) || !ServerCrowdStore.hasDevice(event.getLevel(), event.getPos())) return;
        event.setDroppedExperience(0);
        if (event.getBreaker() != null) {
            BlockPos pos = event.getPos();
            event.getDrops().add(new ItemEntity(event.getLevel(), pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5,
                    ToolItems.Kind.DEVICE.stack()));
        }
    }

    static String key(ServerLevel level, BlockPos pos) {
        return DiffuserData.key(level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
    }
}
