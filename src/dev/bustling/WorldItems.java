package dev.bustling;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Only aliases are added to vanilla registries. Tools and devices use existing vanilla entries. */
public final class WorldItems {
    private WorldItems() {}

    public static void register(IEventBus bus) {
        var items = DeferredRegister.create(Registries.ITEM, "bustling");
        alias(items, "wand", "stick");
        alias(items, "region_editor", "stick");
        alias(items, "edit_tool", "stick");
        alias(items, "object_editor", "stick");
        alias(items, "copy_tool", "book");
        alias(items, "world_panel", "paper");
        alias(items, "client_settings", "paper");
        alias(items, "crowd_diffuser", "spawner");
        alias(items, "diffusion_device", "spawner");
        items.register(bus);

        var blocks = DeferredRegister.create(Registries.BLOCK, "bustling");
        alias(blocks, "crowd_diffuser", "spawner");
        alias(blocks, "diffusion_device", "spawner");
        blocks.register(bus);

        if (FMLEnvironment.dist.isClient()) registerToolsTab(bus);
        bus.addListener(DiffuserPowerPayload::register);
        NeoForge.EVENT_BUS.addListener(WorldItems::registerCommands);
        NeoForge.EVENT_BUS.addListener(DeviceBlocks::drop);
        NeoForge.EVENT_BUS.addListener(ServerCrowdStore::flushDevicePower);
    }

    private static void alias(DeferredRegister<?> registry, String old, String vanilla) {
        registry.addAlias(ResourceLocation.fromNamespaceAndPath("bustling", old),
                ResourceLocation.withDefaultNamespace(vanilla));
    }

    private static void registerToolsTab(IEventBus bus) {
        var tabs = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, "bustling");
        tabs.register("tools", () -> CreativeModeTab.builder()
                .title(Component.translatableWithFallback("itemGroup.bustling", "Bustling"))
                .icon(ToolItems.Kind.REGION::stack)
                .displayItems((parameters, output) -> addTools(output))
                .build());
        tabs.register(bus);
    }

    private static void addTools(CreativeModeTab.Output output) {
        for (ToolItems.Kind kind : ToolItems.Kind.values()) {
            output.accept(kind.stack(), CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
        }
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> give = Commands.literal("give")
                .requires(source -> source.hasPermission(2));
        for (ToolItems.Kind kind : ToolItems.Kind.values()) {
            give.then(Commands.literal(kind.id).executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                ItemStack stack = kind.stack();
                if (!player.getInventory().add(stack)) player.drop(stack, false);
                return 1;
            }));
        }
        event.getDispatcher().register(Commands.literal("bustling").then(give));
    }
}
