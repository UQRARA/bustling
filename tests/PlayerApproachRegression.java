package dev.bustling;

public final class PlayerApproachRegression {
    public static void main(String[] args) {
        near(PlayerApproach.displacement(6, .25), -.25, "approaches inside attraction range");
        near(PlayerApproach.displacement(2.1, .25), -.1, "stops at two blocks");
        near(PlayerApproach.displacement(2, .25), 0, "holds position at two blocks");
        near(PlayerApproach.displacement(1.95, .25), .05, "backs away without overshooting");
        near(PlayerApproach.displacement(0, .25), .25, "backs away after player entered overlap");
        check(PlayerApproach.allowsStep(0, 2.2, 0, -.2), "step to the ring is permitted");
        check(!PlayerApproach.allowsStep(0, 2.2, 0, -.3), "step inside the ring is denied");
        check(PlayerApproach.allowsStep(2, 1, 0, -4.1),
                "tangent remains a valid trace");
        check(!PlayerApproach.allowsStep(2, 0, -1, 2), "diagonal trace may not cut through the ring");
        check(PlayerApproach.allowsStep(1.5, 0, .25, 0), "actor already inside can retreat");
        check(!PlayerApproach.allowsStep(1.5, 0, -.1, 0), "actor inside cannot move farther inward");

        WorldSystem.avoidPlayers = true;
        WorldSystem.playerRadius = -45;
        WorldSystem.normalize();
        check(WorldSystem.playerRadius == -30 && WorldSystem.playerInfluenceRadius() == 30, "signed clamp");
        WorldSystem.playerRadius = -1;
        check(WorldSystem.playerInfluenceRadius() == 2, "minimum distance remains enforceable");
        WorldSystem.playerRadius = 7;
        check(WorldSystem.playerInfluenceRadius() == 7, "positive radius retains avoidance");
        WorldSystem.playerRadius = 0;
        check(WorldSystem.playerInfluenceRadius() == 0, "zero disables the radius");
        WorldSystem.avoidPlayers = false;
        WorldSystem.playerRadius = -7;
        check(WorldSystem.playerInfluenceRadius() == 0, "disabled toggle suppresses attraction");
        System.out.println("PASS signed interaction radius and swept two-block approach boundary");
    }

    private static void near(double value, double expected, String message) {
        check(Math.abs(value - expected) < 1e-8, message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
