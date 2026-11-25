package dev.bustling;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class CopyTool {
    private static CrowdData.Emitter template;
    private static long actionUntil;
    private static boolean pasted;

    private CopyTool() {}

    public static boolean hasClipboard() {
        return template != null;
    }

    public static void copy(Player player) {
        Vec3 hit = ClientOnly.raycast(player, 70);
        CrowdData.Emitter target = target(player, hit);
        if (target == null) return;
        template = CrowdSettings.snapshot(target);
        pasted = false;
        actionUntil = System.currentTimeMillis() + 2000;
        ToolUi.flash();
        ToolSounds.copied();
    }

    public static void paste(Player player) {
        if (template == null) return;
        Vec3 hit = ClientOnly.raycast(player, 70);
        CrowdData.Emitter target = target(player, hit);
        if (target == null) return;
        CrowdSettings.paste(target, template);
        if (DiffuserData.isDiffuser(target) && DiffuserData.isDiffuser(template) && ClientCrowdStore.supportsDevicePower()) {
            target.redstoneControlled = Boolean.TRUE.equals(template.redstoneControlled);
        }
        CrowdData.save();
        ToolSounds.pasted();
        PasteEffects.committed(player, hit, target);
        pasted = true;
        actionUntil = System.currentTimeMillis() + 2000;
        ToolUi.flash();
    }

    private static CrowdData.Emitter target(Player player, Vec3 hit) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || player == null) return null;
        String dimension = mc.level.dimension().location().toString();
        CrowdData.Emitter device = DiffuserClient.target(dimension, player.getEyePosition(), player.getViewVector(1), hit);
        return device == null ? DeleteTool.findAt(hit) : device;
    }

    public static String stateText() {
        if (System.currentTimeMillis() <= actionUntil) {
            return Texts.tr(pasted ? "notice.pasted" : "notice.copied");
        }
        return Texts.tr(hasClipboard() ? "notice.clipboard_ready" : "notice.clipboard_empty");
    }

    public static int stateColor() {
        long now = System.currentTimeMillis();
        if (now <= actionUntil) return pasted ? -11141274 : -11172;
        return hasClipboard() && now / 400 % 2 == 0 ? -10700545 : -6645094;
    }
}
