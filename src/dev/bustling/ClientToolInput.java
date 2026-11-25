package dev.bustling;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.InputEvent;

final class ClientToolInput {
    private ClientToolInput() {}

    static void use(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (!event.isUseItem() || mc.player == null || mc.level == null || mc.screen != null) return;
        var hand = event.getHand();
        var stack = mc.player.getItemInHand(hand);
        var kind = ToolItems.kind(stack);
        if (kind == null) {
            if (hand == InteractionHand.MAIN_HAND && stack.isEmpty()
                    && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                    && mc.level.getBlockState(hit.getBlockPos()).is(Blocks.SPAWNER)
                    && ToolModels.isDevice(mc.level.dimension().location(), hit.getBlockPos())) {
                cancel(event);
                WorldHooks.openDiffuser.accept(DiffuserData.key(mc.level.dimension().location().toString(),
                        hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ()));
            }
            return;
        }
        if (kind == ToolItems.Kind.DEVICE) {
            if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                ClientDevicePlacements.expect(mc.level, new BlockPlaceContext(mc.player, hand, stack, hit));
            }
            return;
        }
        cancel(event);
        switch (kind) {
            case REGION -> ClientOnly.openToolPanel(mc.player);
            case OBJECT -> ClientOnly.openEditPanel(mc.player);
            case COPY -> CopyTool.paste(mc.player);
            case SETTINGS -> WorldHooks.openPanel.run();
            case DEVICE -> {}
        }
    }

    private static void cancel(InputEvent.InteractionKeyMappingTriggered event) {
        event.setCanceled(true);
        event.setSwingHand(false);
    }
}
