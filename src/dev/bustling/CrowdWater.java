package dev.bustling;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;

/** Water-column queries and water locomotion for the local crowd simulation. */
final class CrowdWater {
    static final double SWIM_SPEED = 2.6;
    static final double RISE_SPEED = 2.4;
    private static final double CONTACT_HEIGHT = .35;
    private static final Map<Long, Double> SURFACES = new HashMap<>();
    private static ClientLevel cacheLevel;
    private static long cacheTick;

    private CrowdWater() {}

    static void clear() {
        SURFACES.clear();
        cacheLevel = null;
    }

    static double surface(ClientLevel level, double x, double y, double z) {
        long tick = level.getGameTime();
        if (cacheLevel != level || cacheTick != tick) {
            SURFACES.clear();
            cacheLevel = level;
            cacheTick = tick;
        }
        var pos = new BlockPos.MutableBlockPos(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        if (!level.hasChunkAt(pos)) return Double.NaN;
        if (!level.getFluidState(pos).is(FluidTags.WATER)) {
            int below = Mth.floor(y - CONTACT_HEIGHT);
            if (below == pos.getY() || !level.getFluidState(pos.setY(below)).is(FluidTags.WATER)) return Double.NaN;
        }
        long key = pos.asLong();
        Double cached = SURFACES.get(key);
        double top;
        if (cached != null) {
            top = cached;
        } else {
            int levelY = pos.getY();
            int topY = levelY;
            while (topY < level.getMaxBuildHeight() - 1
                    && level.getFluidState(pos.setY(topY + 1)).is(FluidTags.WATER)) {
                topY++;
            }
            top = topY + level.getFluidState(pos.setY(topY)).getHeight(level, pos);
            for (int yy = levelY; yy <= topY && SURFACES.size() < 4096; yy++) {
                SURFACES.put(pos.setY(yy).asLong(), top);
            }
        }
        return y <= top + CONTACT_HEIGHT ? top : Double.NaN;
    }

    static double floatingFeet(ClientLevel level, double x, double z, double surface, double height) {
        double feet = surface - Math.min(1.4, height * .66);
        // Shallow water supports the actor at the bottom of the filled column.
        var pos = new BlockPos.MutableBlockPos(Mth.floor(x), Mth.floor(feet), Mth.floor(z));
        while (!level.getFluidState(pos).is(FluidTags.WATER) && pos.getY() + 1 < surface) {
            feet = pos.getY() + 1;
            pos.setY(pos.getY() + 1);
        }
        return feet;
    }

    // The signed value preserves an idle water pose in the existing ghost render data.
    static float movement(double intensity) {
        return -1 - (float)Math.clamp(intensity, 0, 1);
    }

    static boolean floating(float movement) {
        return movement < 0;
    }

    static float swimBlend(float movement) {
        return Math.clamp(-movement - 1, 0, 1);
    }
}
