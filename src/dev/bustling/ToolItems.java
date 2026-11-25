package dev.bustling;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/** Tool identity travels in vanilla item components, so no registry entry is needed. */
public final class ToolItems {
    private static final String MARK = "bustling:tool";

    public enum Kind {
        REGION("region_editor", "Region Editor"),
        OBJECT("object_editor", "Object Editor"),
        COPY("copy_tool", "Copy Tool"),
        SETTINGS("client_settings", "Client Settings"),
        DEVICE("diffusion_device", "Diffusion Device");

        public final String id;
        private final String label;

        Kind(String id, String label) {
            this.id = id;
            this.label = label;
        }

        Item carrier() {
            return switch (this) {
                case REGION, OBJECT -> Items.STICK;
                case COPY -> Items.BOOK;
                case SETTINGS -> Items.PAPER;
                case DEVICE -> Items.SPAWNER;
            };
        }

        public ItemStack stack() {
            ItemStack stack = new ItemStack(carrier());
            CompoundTag marker = new CompoundTag();
            marker.putString(MARK, id);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
            String prefix = this == DEVICE ? "block" : "item";
            stack.set(DataComponents.ITEM_NAME, Component.translatableWithFallback(prefix + ".bustling." + id, label));
            if (this != DEVICE) stack.set(DataComponents.MAX_STACK_SIZE, 1);
            return stack;
        }
    }

    private ToolItems() {}

    public static Kind kind(ItemStack stack) {
        if (stack.isEmpty()) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(MARK)) return null;
        return switch (data.getUnsafe().getString(MARK)) {
            case "region_editor" -> stack.is(Items.STICK) ? Kind.REGION : null;
            case "object_editor" -> stack.is(Items.STICK) ? Kind.OBJECT : null;
            case "copy_tool" -> stack.is(Items.BOOK) ? Kind.COPY : null;
            case "client_settings" -> stack.is(Items.PAPER) ? Kind.SETTINGS : null;
            case "diffusion_device" -> stack.is(Items.SPAWNER) ? Kind.DEVICE : null;
            default -> null;
        };
    }

    public static boolean isRegion(ItemStack stack) { return kind(stack) == Kind.REGION; }
    public static boolean isObject(ItemStack stack) { return kind(stack) == Kind.OBJECT; }
    public static boolean isCopy(ItemStack stack) { return kind(stack) == Kind.COPY; }
    public static boolean isSettings(ItemStack stack) { return kind(stack) == Kind.SETTINGS; }
    public static boolean isDevice(ItemStack stack) { return kind(stack) == Kind.DEVICE; }

    public static boolean isDrawingTool(ItemStack stack) {
        return switch (kind(stack)) {
            case REGION, OBJECT -> true;
            case null, default -> false;
        };
    }
}
