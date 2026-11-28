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

        check(CrowdPace.style(0) == CrowdPace.Style.WALK, "ordinary actors retain their walking pace");
        check(CrowdPace.style(1) == CrowdPace.Style.RUN && CrowdPace.style(9) == CrowdPace.Style.RUN,
                "a stable minority can run");
        check(CrowdPace.style(2) == CrowdPace.Style.BACKPEDAL, "avoidance has a backward walking style");
        check(CrowdPace.movesOutward(0, 2, 0, .25), "backward retreat increases distance from the player");
        check(!CrowdPace.movesOutward(0, 2, .25, -.1), "a retreat cannot start toward the player");
        double pursuit = CrowdPace.pursuit(0, .25, .1);
        check(!CrowdPace.escapeRunning(false, pursuit, 2.5, 6, .35), "one short advance keeps backward retreat");
        pursuit = CrowdPace.pursuit(pursuit, .25, .1);
        check(CrowdPace.escapeRunning(false, pursuit, 2.5, 6, .35), "continued close pursuit triggers a forward run");
        check(!CrowdPace.escapeRunning(false, pursuit, 2.5, 6, .55), "a slower reaction needs a longer advance");
        near(CrowdPace.pursuit(pursuit, 4, .1), 0, "teleports do not count as continued pursuit");
        check(!CrowdPace.escapeRunning(false, pursuit, 4, 6, .35), "the player must be nearby to trigger a run");
        check(!CrowdPace.escapeRunning(false, pursuit, 3, 2, .35), "the configured radius still limits activation");
        for (int i = 0; i < 3; i++) pursuit = CrowdPace.pursuit(pursuit, 0, .1);
        check(CrowdPace.escapeRunning(true, pursuit, 4, 6, .35), "a brief pause does not flip the actor back around");
        for (int i = 0; i < 6; i++) pursuit = CrowdPace.pursuit(pursuit, 0, .1);
        check(!CrowdPace.escapeRunning(true, pursuit, 2.5, 6, .35), "a stationary player no longer triggers a run");
        near(CrowdPace.repulsionSpeed(1, 6, 6), 0, "avoidance falls away at the configured radius");
        near(CrowdPace.runningEffort(true, 2, 8), 0, "an approaching runner eases out at two blocks");
        check(CrowdPace.runningEffort(true, 4, 8) > .9, "an approaching runner can reach full pace");
        near(CrowdPace.runningEffort(false, 8, 8), 0, "escape pace fades at the outer radius");
        check(CrowdPace.speed(1, 1, 1) > 3, "a runner travels faster at the same base speed");
        near(CrowdPace.runBlend(CrowdPace.movement(1, .5)), .5, "the render data carries a separate run blend");
        near(CrowdPace.runBlend(CrowdPace.movement(0, 1)), 0, "a stationary actor does not pose as a runner");
        System.out.println("PASS signed player interaction, two-block approach, retreat direction and running pace");
    }

    private static void near(double value, double expected, String message) {
        check(Math.abs(value - expected) < 1e-8, message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
