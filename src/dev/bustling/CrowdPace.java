package dev.bustling;

/** Stable interaction styles and the locomotion strength carried by a ghost. */
final class CrowdPace {
    enum Style { WALK, BACKPEDAL, RUN }

    private CrowdPace() {}

    static Style style(int actorIndex) {
        return switch (Math.floorMod(actorIndex, 8)) {
            case 1 -> Style.RUN;
            case 2, 5 -> Style.BACKPEDAL;
            default -> Style.WALK;
        };
    }

    static double repulsionSpeed(double baseSpeed, double distance, int range) {
        if (range <= 0) return 0;
        double influence = Math.clamp(1 - distance / range, 0, 1);
        return Math.max(.2, baseSpeed) * 2.5 * Math.pow(influence, 1.2);
    }

    static double runningEffort(boolean approaching, double distance, int range) {
        double progress = approaching
                ? (distance - PlayerApproach.HOLD_DISTANCE - .3) / 1.3
                : (range - distance) / Math.max(.5, range * .65);
        double t = Math.clamp(progress, 0, 1);
        return t * t * (3 - 2 * t);
    }

    static double speed(double walkingSpeed, double baseSpeed, double effort) {
        double runningSpeed = Math.clamp(baseSpeed * 1.9, 3.8, 7);
        return walkingSpeed + Math.max(0, runningSpeed - walkingSpeed) * effort;
    }

    static boolean movesOutward(double x, double z, double dx, double dz) {
        return x * dx + z * dz >= -1e-8;
    }

    static boolean holdsRetreat(double distance, int range) {
        // Hold the direction at the edge of the interaction radius while the player catches up.
        return distance <= range + 1;
    }

    static double pursuit(double previous, double playerAdvance, double seconds) {
        // Ignore player teleports between samples.
        if (Math.abs(playerAdvance) > 8 * seconds) return 0;
        return Math.clamp(previous + playerAdvance - .5 * seconds, 0, .7);
    }

    static boolean escapeRunning(boolean alreadyRunning, double pursuit, double distance, int range, double reaction) {
        if (alreadyRunning) return pursuit >= .08;
        return distance < Math.min(3.5, range) && pursuit >= reaction;
    }

    // The renderer's historical 0..1 range is walking. Extra strength carries the run blend.
    static float movement(double walking, double running) {
        return (float) (walking * (1 + running));
    }

    static float runBlend(float movement) {
        return Math.clamp(movement - 1, 0, 1);
    }
}
