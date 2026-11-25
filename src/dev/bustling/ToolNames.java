package dev.bustling;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

public final class ToolNames {
    private ToolNames() {}

    public static void clearLegacyName(Minecraft mc) {
        if (mc.player == null) return;
        ItemStack stack = mc.player.getMainHandItem();
        ItemStack item = stack;
        if (!(ToolItems.isRegion(item) || ToolItems.isObject(item) || ToolItems.isCopy(item))) return;
        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        if (custom == null) return;
        String name = custom.getString();
        if ((ToolItems.isRegion(item) && ("路径工具".equals(name) || "路径设置器".equals(name)))
                || (ToolItems.isObject(item) && ("人群编辑器".equals(name) || "散步编辑器".equals(name)))
                || (ToolItems.isCopy(item) && ("复制工具".equals(name) || "复制魔杖".equals(name)))) {
            stack.remove(DataComponents.CUSTOM_NAME);
        }
    }


    private static final int SUMMARY_COLOR = 0xC9974C;
    private static final int HIGHLIGHT_COLOR = 0xF1DD79;

    public static void addInstructions(ItemTooltipEvent event) {
        ItemStack item = event.getItemStack();
        ToolItems.Kind kind = ToolItems.kind(item);
        if (kind == null) return;

        var lines = event.getToolTip();
        if (kind == ToolItems.Kind.DEVICE) {
            for (int i = lines.size() - 1; i > 0; i--) {
                if (!spawnerHint(lines.get(i))) continue;
                lines.remove(i);
                if (i > 1 && lines.get(i - 1).getString().isBlank()) lines.remove(--i);
            }
        }
        String carrierId = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
        for (int i = 1; i < lines.size(); i++) {
            if (!lines.get(i).getString().equals(carrierId)) continue;
            lines.set(i, Component.literal("bustling:" + kind.id).withStyle(ChatFormatting.DARK_GRAY));
            break;
        }
        if (!lines.isEmpty()) lines.set(0, item.getHoverName().copy().withStyle(style ->
                style.withColor(titleColor(kind)).withItalic(false)));
        int index = Math.min(1, lines.size());
        boolean expanded = Screen.hasShiftDown();
        lines.add(index++, Component.literal("Bustling").withStyle(ChatFormatting.BLUE));
        lines.add(index++, Component.translatable("tooltip.bustling.controls_hint",
                Component.literal("Shift").withStyle(expanded ? ChatFormatting.WHITE : ChatFormatting.GRAY))
                .withStyle(ChatFormatting.DARK_GRAY));
        if (!expanded) return;

        String key = "tooltip.bustling." + kind.id;
        lines.add(index++, Component.empty());
        lines.add(index++, styledDescription(key + ".summary"));
        String[] actions = switch (kind) {
            case REGION -> new String[]{"switch", "left", "right", "undo"};
            case OBJECT -> new String[]{"right", "pick"};
            case COPY -> new String[]{"left", "right"};
            case SETTINGS -> new String[]{"right"};
            case DEVICE -> new String[]{"place", "right"};
        };
        for (String action : actions) {
            lines.add(index++, Component.translatable("tooltip.bustling.action." + action).withStyle(ChatFormatting.GRAY));
            lines.add(index++, Component.literal("  ").append(styledDescription(key + "." + action)));
        }
    }

    private static Component styledDescription(String key) {
        String localized = Component.translatable(key).getString();
        String[] segments = localized.split("_", -1);
        if (segments.length % 2 == 0) {
            return Component.literal(localized).withColor(SUMMARY_COLOR);
        }
        MutableComponent description = Component.empty();
        for (int i = 0; i < segments.length; i++) {
            if (!segments[i].isEmpty()) {
                description.append(Component.literal(segments[i]).withColor(i % 2 == 0 ? SUMMARY_COLOR : HIGHLIGHT_COLOR));
            }
        }
        return description;
    }

    private static boolean spawnerHint(Component component) {
        return component.getContents() instanceof TranslatableContents translation
                && translation.getKey().startsWith("block.minecraft.spawner.desc")
                || component.getSiblings().stream().anyMatch(ToolNames::spawnerHint);
    }

    private static int titleColor(ToolItems.Kind kind) {
        return switch (kind) {
            case REGION -> switch (ToolConfig.mode) {
                case AREA -> 0x83CDE2;
                case PATH -> 0xEAC477;
                case DELETE -> 0xED897F;
            };
            case OBJECT -> 0x8ED2AC;
            case COPY -> 0xE3B681;
            case SETTINGS -> 0x98BBE5;
            case DEVICE -> 0xD49CF1;
        };
    }
}
