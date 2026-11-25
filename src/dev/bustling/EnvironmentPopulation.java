package dev.bustling;

/** Local population curve with a damped transition when conditions change abruptly. */
final class EnvironmentPopulation {
    private static final double NIGHT_RATIO = 0.4;
    private static final double RETENTION_PER_TICK = 0.92;
    private double first = 1.0;
    private double ratio = 1.0;

    static double daylight(long dayTime) {
        long time = Math.floorMod(dayTime, 24_000);
        if (time < 2_000) return dawn(time + 24_000);
        if (time < 12_000) return 1.0;
        if (time < 22_000) return 1.0 - (1.0 - NIGHT_RATIO) * ease((time - 12_000) / 4_000.0);
        return dawn(time);
    }

    private static double dawn(long time) {
        return NIGHT_RATIO + (1.0 - NIGHT_RATIO) * ease((time - 22_000) / 4_000.0);
    }

    static double precipitation(double strength, double wetRatio) {
        return 1.0 - (1.0 - wetRatio) * ease(strength);
    }

    private static double ease(double progress) {
        double t = Math.clamp(progress, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    void reset(double target) {
        first = ratio = target;
    }

    void advance(double target, long ticks) {
        double retention = Math.pow(RETENTION_PER_TICK, ticks);
        // Two successive filters ease into and out of sudden time, weather, or setting changes.
        double firstOffset = first - target;
        first = target + retention * firstOffset;
        ratio = target + retention * (ratio - target + ticks * (1.0 - RETENTION_PER_TICK) * firstOffset);
        if (Math.abs(ratio - target) < 0.0001 && Math.abs(first - target) < 0.0001) reset(target);
    }

    double ratio() {
        return ratio;
    }
}
