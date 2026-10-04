package ml.mypals.meshcut.geom;

import java.util.Arrays;

/** A capsule rasterized against voxel boxes, in block-local coordinates. No world or entity dependencies. */
public final class VoxelCapsule {
    public static int radius(int value) { return Math.clamp(value, 1, 4); }

    public static boolean[] drill(double[] impact, double[] direction, int radius) {
        boolean[] removed = new boolean[512];
        for (int i = 0; i < 3; i++)
            if (!Double.isFinite(impact[i]) || !Double.isFinite(direction[i])) return removed;
        int axis = 0;
        for (int i = 1; i < 3; i++) if (Math.abs(direction[i]) > Math.abs(direction[axis])) axis = i;
        if (Math.abs(direction[axis]) < 1e-10) return removed;
        double[] a = impact.clone();
        int transverse = 0;
        for (int i = 0; i < 3; i++) {
            a[i] = Math.clamp(a[i], 0, 1);
            if (i == axis) continue;
            // Half-cell phase gives the requested radius-one 2 / 4 / 2 cross-section.
            a[i] = transverse++ == 0 ? Math.rint(a[i] * 8) / 8
                    : (Math.clamp((int) Math.floor(a[i] * 8), 0, 7) + 0.5) / 8;
        }
        double exit = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 3; i++) if (Math.abs(direction[i]) > 1e-10)
            exit = Math.min(exit, ((direction[i] > 0 ? 1 : 0) - a[i]) / direction[i]);
        double[] b = new double[3];
        for (int i = 0; i < 3; i++) b[i] = a[i] + direction[i] * Math.max(0, exit);
        return mask(a, b, radius(radius) / 8.0);
    }

    public static boolean[] mask(double[] a, double[] b, double radius) {
        boolean[] out = new boolean[512];
        if (!Double.isFinite(radius) || radius <= 0) return out;
        for (int i = 0; i < 3; i++) if (!Double.isFinite(a[i]) || !Double.isFinite(b[i])) return out;
        for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++) for (int z = 0; z < 8; z++) {
            double[] low = {x / 8.0, y / 8.0, z / 8.0};
            out[(x * 8 + y) * 8 + z] = distanceSquared(a, b, low) <= radius * radius + 1e-12;
        }
        return out;
    }

    /** Distance to a box is piecewise quadratic along a segment; minimize each interval exactly. */
    private static double distanceSquared(double[] a, double[] b, double[] low) {
        double[] cuts = new double[8];
        cuts[0] = 0; cuts[1] = 1;
        int count = 2;
        for (int i = 0; i < 3; i++) {
            double d = b[i] - a[i];
            if (Math.abs(d) < 1e-12) continue;
            for (double edge : new double[]{low[i], low[i] + 0.125}) {
                double t = (edge - a[i]) / d;
                if (t > 0 && t < 1) cuts[count++] = t;
            }
        }
        Arrays.sort(cuts, 0, count);
        double best = Double.POSITIVE_INFINITY;
        for (int j = 1; j < count; j++) {
            double mid = (cuts[j - 1] + cuts[j]) * 0.5, quadratic = 0, linear = 0;
            for (int i = 0; i < 3; i++) {
                double d = b[i] - a[i], p = a[i] + d * mid;
                if (p >= low[i] && p <= low[i] + 0.125) continue;
                double offset = a[i] - (p < low[i] ? low[i] : low[i] + 0.125);
                quadratic += d * d;
                linear += d * offset;
            }
            double t = quadratic > 0 ? Math.clamp(-linear / quadratic, cuts[j - 1], cuts[j]) : mid;
            double sum = 0;
            for (int i = 0; i < 3; i++) {
                double p = a[i] + (b[i] - a[i]) * t;
                double d = p - Math.clamp(p, low[i], low[i] + 0.125);
                sum += d * d;
            }
            best = Math.min(best, sum);
        }
        return best;
    }

    private VoxelCapsule() {}
}
