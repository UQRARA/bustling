package dev.bustling;

/** Horizontal movement constraints for a crowd that faces and approaches a player. */
final class PlayerApproach {
    private static final double HOLD_DISTANCE = 2.0;

    private PlayerApproach() {}

    static double displacement(double distance, double maxStep) {
        return Math.clamp(HOLD_DISTANCE - distance, -maxStep, maxStep);
    }

    static boolean allowsStep(double x, double z, double dx, double dz) {
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared == 0) return true;
        double towardClosest = Math.clamp(-(x * dx + z * dz) / lengthSquared, 0, 1);
        double nearestX = x + towardClosest * dx;
        double nearestZ = z + towardClosest * dz;
        double minimumSquared = Math.min(HOLD_DISTANCE * HOLD_DISTANCE, x * x + z * z);
        return nearestX * nearestX + nearestZ * nearestZ >= minimumSquared - 1e-8;
    }
}
