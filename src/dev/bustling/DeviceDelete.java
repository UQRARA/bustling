package dev.bustling;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public final class DeviceDelete {
    private DeviceDelete() {}

    public static boolean tryDelete(Vec3 hit) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) return false;
        var player = mc.player;
        var level = mc.level;
        CrowdData.Emitter target = DiffuserClient.target(level.dimension().location().toString(),
                player.getEyePosition(), player.getViewVector(1), hit);
        if (target == null) return false;
        ClientCrowdStore.removeDevice(target.id);
        return true;
    }
}
