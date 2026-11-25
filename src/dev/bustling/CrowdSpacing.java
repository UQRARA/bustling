package dev.bustling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A tick-local grid of simulated bodies and their reserved destinations. */
final class CrowdSpacing {
    private static final double CELL = 2;
    private static final double PADDING = .06;
    private static final double EPSILON = 1e-8;
    private final Map<Long, List<Body>> cells = new HashMap<>();

    static final class Body {
        final long id;
        double x, y, z, radius, height, targetX, targetZ;

        Body(long id) {
            this.id = id;
        }
    }

    void clear() {
        cells.clear();
    }

    void add(Body body, double x, double y, double z, double radius, double height, double targetX, double targetZ) {
        body.x = x;
        body.y = y;
        body.z = z;
        body.radius = radius;
        body.height = height;
        reserve(body, targetX, targetZ);
        cells.computeIfAbsent(key(cell(x), cell(z)), ignored -> new ArrayList<>()).add(body);
    }

    void reserve(Body body, double x, double z) {
        body.targetX = x;
        body.targetZ = z;
    }

    void nearby(Body actor, double x, double y, double z, List<Body> out) {
        out.clear();
        // Two actor radii and both pending steps fit inside this range.
        double reach = actor.radius + 3.9;
        int minX = cell(x - reach), maxX = cell(x + reach);
        int minZ = cell(z - reach), maxZ = cell(z + reach);
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                List<Body> bucket = cells.get(key(cx, cz));
                if (bucket == null) continue;
                for (Body other : bucket) {
                    if (other == actor || other.y + other.height <= y + .1 || y + actor.height <= other.y + .1) continue;
                    double dx = x - other.x, dz = z - other.z;
                    double range = clearance(actor, other) + 2.5;
                    if (dx * dx + dz * dz < range * range) out.add(other);
                }
            }
        }
    }

    boolean canPlace(Body actor, double x, double y, double z, List<Body> scratch) {
        nearby(actor, x, y, z, scratch);
        for (Body other : scratch) {
            double gap = clearance(actor, other);
            double rx = x - other.x, rz = z - other.z;
            if (rx * rx + rz * rz < gap * gap - EPSILON
                    || entersClearance(rx, rz, other.x - other.targetX, other.z - other.targetZ, gap * gap)) return false;
        }
        return true;
    }

    static double pressure(Body actor, List<Body> neighbors, double x, double z, double[] out) {
        double sx = 0, sz = 0, overlap = 0;
        for (Body other : neighbors) {
            double dx = x - other.x, dz = z - other.z;
            double gap = clearance(actor, other);
            double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared >= gap * gap) continue;
            overlap += gap * gap - distanceSquared;
            if (distanceSquared < EPSILON) {
                long first = Math.min(actor.id, other.id), second = Math.max(actor.id, other.id);
                double angle = Math.floorMod(first * 73856093L + second * 19349663L, 6283) / 1000.0;
                double sign = actor.id < other.id ? 1 : -1;
                sx += Math.cos(angle) * sign * gap;
                sz += Math.sin(angle) * sign * gap;
            } else {
                double distance = Math.sqrt(distanceSquared), scale = (gap - distance) / distance;
                sx += dx * scale;
                sz += dz * scale;
            }
        }
        double length = Math.hypot(sx, sz);
        double scale = length > .25 ? .25 / length : 1;
        out[0] = sx * scale;
        out[1] = sz * scale;
        return overlap;
    }

    static boolean permits(Body actor, List<Body> neighbors, double x, double z, double dx, double dz) {
        return permits(actor, neighbors, x, z, dx, dz, 0);
    }

    static boolean permits(Body actor, List<Body> neighbors, double x, double z, double dx, double dz,
                           double recoveryOverlap) {
        double oldOverlap = 0, newOverlap = 0;
        for (Body other : neighbors) {
            double rx = x - other.x, rz = z - other.z;
            double tx = x - other.targetX, tz = z - other.targetZ;
            double gap = clearance(actor, other), limit = gap * gap;
            // During recovery allow slight contact with a third body if the total penetration drops.
            double sweepLimit = Math.max(limit * .49, limit - recoveryOverlap * .6);
            if (entersClearance(rx, rz, dx, dz, sweepLimit)
                    || entersClearance(tx, tz, dx, dz, sweepLimit)
                    || entersClearance(rx, rz, dx + tx - rx, dz + tz - rz, sweepLimit)) return false;
            oldOverlap += Math.max(0, limit - (rx * rx + rz * rz));
            newOverlap += Math.max(0, limit - ((rx + dx) * (rx + dx) + (rz + dz) * (rz + dz)));
        }
        return recoveryOverlap > 0 ? newOverlap < recoveryOverlap - EPSILON : newOverlap <= oldOverlap + EPSILON;
    }

    private static boolean entersClearance(double x, double z, double dx, double dz, double limit) {
        if (x * x + z * z < limit - EPSILON) return false;
        double length = dx * dx + dz * dz;
        if (length < EPSILON) return false;
        double closest = Math.clamp(-(x * dx + z * dz) / length, 0, 1);
        double nearX = x + dx * closest, nearZ = z + dz * closest;
        return nearX * nearX + nearZ * nearZ < limit - EPSILON;
    }

    private static double clearance(Body a, Body b) {
        return a.radius + b.radius + PADDING;
    }

    private static int cell(double value) {
        return (int)Math.floor(value / CELL);
    }

    private static long key(int x, int z) {
        return ((long)x << 32) ^ (z & 0xffffffffL);
    }
}
