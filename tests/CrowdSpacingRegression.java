package dev.bustling;

import java.util.ArrayList;

public final class CrowdSpacingRegression {
    public static void main(String[] args) {
        var grid = new CrowdSpacing();
        var actor = new CrowdSpacing.Body(1);
        var neighbor = new CrowdSpacing.Body(2);
        var upperFloor = new CrowdSpacing.Body(3);
        var nearby = new ArrayList<CrowdSpacing.Body>();
        grid.add(actor, 0, 0, 0, .30, 1.875, 0, 0);
        grid.add(neighbor, .9, 0, 0, .30, 1.875, .9, 0);
        grid.add(upperFloor, 0, 2, 0, .30, 1.875, 0, 0);
        grid.nearby(actor, 0, 0, 0, nearby);
        require(nearby.size() == 1 && nearby.getFirst() == neighbor, "ignore self and separated floors");
        require(CrowdSpacing.permits(actor, nearby, 0, 0, .27, 0), "comfort padding allows close contact without overlap");
        require(!CrowdSpacing.permits(actor, nearby, 0, 0, .31, 0), "reject a step into a body");
        require(!CrowdSpacing.permits(actor, nearby, 0, 0, 1.70, 0), "reject crossing even with a clear endpoint");

        var newcomer = new CrowdSpacing.Body(4);
        newcomer.radius = .30;
        newcomer.height = 1.875;
        require(!grid.canPlace(newcomer, 1.2, 0, 0, nearby),
                "do not initialize a crowd actor on a neighbor");
        grid.nearby(actor, 0, 0, 0, nearby);
        grid.reserve(neighbor, .1, .9);
        require(!CrowdSpacing.permits(actor, nearby, 0, 0, .35, .65), "respect the neighbor's planned path");

        grid.clear();
        grid.add(actor, 0, 0, 0, .30, 1.875, 0, 0);
        grid.add(neighbor, 0, 0, 0, .30, 1.875, 0, 0);
        grid.nearby(actor, 0, 0, 0, nearby);
        double[] push = new double[2], otherPush = new double[2];
        CrowdSpacing.pressure(actor, nearby, 0, 0, push);
        grid.nearby(neighbor, 0, 0, 0, nearby);
        CrowdSpacing.pressure(neighbor, nearby, 0, 0, otherPush);
        require(Math.hypot(push[0], push[1]) > .1, "identical positions receive separation");
        require(Math.hypot(push[0] + otherPush[0], push[1] + otherPush[1]) < 1e-8, "contact forces oppose");
        grid.nearby(actor, 0, 0, 0, nearby);
        require(CrowdSpacing.permits(actor, nearby, 0, 0, push[0], push[1]), "existing overlap can separate");

        grid.clear();
        grid.add(actor, 2.18, 0, 0, .30, 1.875, 2.18, 0);
        grid.add(neighbor, 1.55, 0, 0, .30, 1.875, 1.55, 0);
        grid.nearby(actor, 2.18, 0, 0, nearby);
        require(CrowdSpacing.yieldForRetreat(actor, nearby, 2.18, 0, 0, 0, push), "outer neighbor recognizes a blocked exit");
        require(push[0] > 0 && Math.abs(push[1]) > push[0], "yielding is primarily sideways and stays outside the player ring");
        require(!CrowdSpacing.yieldForRetreat(actor, nearby, 2.18, 2, 0, 0, push), "actors clear of the exit keep their own route");
        System.out.println("PASS crowd body clearance, swept reservations, vertical layers, overlap recovery and player exit priority");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
