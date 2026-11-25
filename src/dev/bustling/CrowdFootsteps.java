package dev.bustling;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;

final class CrowdFootsteps {
    private static final int MAX_SOUNDS_PER_TICK = 3;
    private static ClientLevel lastWorld;
    private static long lastTick = Long.MIN_VALUE;
    private static int played;

    private CrowdFootsteps() {}

    static void clear() {
        lastWorld = null;
        lastTick = Long.MIN_VALUE;
        played = 0;
    }

    static void play(ClientLevel level, double x, double y, double z) {
        var listener = Minecraft.getInstance().player;
        if (listener == null || listener.distanceToSqr(x, y, z) > 16 * 16) return;
        long tick = level.getGameTime();
        if (lastWorld != level || lastTick != tick) {
            lastWorld = level;
            lastTick = tick;
            played = 0;
        }
        if (played >= MAX_SOUNDS_PER_TICK) return;
        BlockPos pos = BlockPos.containing(x, y - .04, z);
        var material = level.getBlockState(pos);
        if (material.isAir()) return;
        var sound = material.getSoundType(level, pos, null);
        level.playLocalSound(x, y, z, sound.getStepSound(), SoundSource.NEUTRAL,
                Math.min(.25f, .16f * sound.getVolume()), sound.getPitch(), false);
        played++;
    }
}
