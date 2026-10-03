package ml.mypals.meshcut.client.capture;

import java.util.ArrayList;
import java.util.List;

/**
 * Approximates a model with voxels, then joins the voxels into as few boxes as it can. What a limb of a cut-down
 * entity is made of (and collides with) is this, not the boxes of its model parts.
 * <ul>
 *   <li>Each closed piece of the model (a cube) is filled: along every column of voxels, a ray through the piece
 *       goes in and out of it, and what lies between is inside.</li>
 *   <li>Every face also fills the voxels it passes through, so a piece with no inside (a wing's membrane, a quad of a
 *       held item) is still there.</li>
 * </ul>
 */
public final class Voxelizer {
    /** At most this many voxels along a side; a bigger model gets bigger voxels. */
    private static final int MAX_CELLS_PER_SIDE = 96;
    /** Moved off the voxel centres so a ray never runs exactly along an edge of a face. */
    private static final double NUDGE_A = 1.234567e-4, NUDGE_B = 2.345678e-4;
    /** How far a face's points are moved back from it (in blocks) before they mark a voxel. */
    private static final double INWARD = 1e-4;
    /** How far (as a fraction of the way) a face's points are drawn towards its middle before they mark a voxel. */
    private static final double TOWARDS_MIDDLE = 1e-3;

    /**
     * @param groups     the model, as closed pieces, each a list of triangles {x0,y0,z0, x1,y1,z1, x2,y2,z2, nx,ny,nz}
     *                   (the normal facing out)
     * @param resolution the voxel size it would like (in blocks)
     * @return boxes {minX, minY, minZ, maxX, maxY, maxZ} covering the voxels
     */
    public static List<double[]> boxes(List<List<float[]>> groups, double resolution) {
        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (List<float[]> group : groups) {
            for (float[] t : group) {
                for (int i = 0; i < 9; i++) {
                    min[i % 3] = Math.min(min[i % 3], t[i]);
                    max[i % 3] = Math.max(max[i % 3], t[i]);
                }
            }
        }
        if (min[0] > max[0]) {
            return List.of();
        }
        double r = resolution;
        while (Math.max(max[0] - min[0], Math.max(max[1] - min[1], max[2] - min[2])) / r > MAX_CELLS_PER_SIDE) {
            r *= 2;
        }
        // the grid lies on multiples of the voxel size, so a model built on whole pixels fits it exactly
        int[] lo = new int[3], size = new int[3];
        for (int a = 0; a < 3; a++) {
            // one voxel to spare on each side: a flat piece lying on a voxel boundary may go to either
            lo[a] = (int) Math.floor(min[a] / r + 1e-6) - 1;
            size[a] = Math.max(1, (int) Math.ceil(max[a] / r - 1e-6) - lo[a]) + 1;
        }
        Grid grid = new Grid(lo, size, r);
        for (List<float[]> group : groups) {
            fill(grid, group);
            for (float[] t : group) {
                surface(grid, t);
            }
        }
        return grid.boxes();
    }

    private static final class Grid {
        final int[] lo, size;
        final double r;
        final boolean[] cells;

        Grid(int[] lo, int[] size, double r) {
            this.lo = lo;
            this.size = size;
            this.r = r;
            this.cells = new boolean[size[0] * size[1] * size[2]];
        }

        int index(int x, int y, int z) {
            return (x * size[1] + y) * size[2] + z;
        }

        void set(double x, double y, double z) {
            int i = (int) Math.floor(x / r) - lo[0], j = (int) Math.floor(y / r) - lo[1], k = (int) Math.floor(z / r) - lo[2];
            if (i >= 0 && j >= 0 && k >= 0 && i < size[0] && j < size[1] && k < size[2]) {
                cells[index(i, j, k)] = true;
            }
        }

        /** Joins the voxels greedily: as long along x as it goes, then as wide along y, then as deep along z. */
        List<double[]> boxes() {
            boolean[] used = new boolean[cells.length];
            List<double[]> out = new ArrayList<>();
            for (int x = 0; x < size[0]; x++) {
                for (int y = 0; y < size[1]; y++) {
                    for (int z = 0; z < size[2]; z++) {
                        int at = index(x, y, z);
                        if (!cells[at] || used[at]) {
                            continue;
                        }
                        int x1 = x + 1;
                        while (x1 < size[0] && free(used, x1, x1 + 1, y, y + 1, z, z + 1)) {
                            x1++;
                        }
                        int y1 = y + 1;
                        while (y1 < size[1] && free(used, x, x1, y1, y1 + 1, z, z + 1)) {
                            y1++;
                        }
                        int z1 = z + 1;
                        while (z1 < size[2] && free(used, x, x1, y, y1, z1, z1 + 1)) {
                            z1++;
                        }
                        for (int i = x; i < x1; i++) {
                            for (int j = y; j < y1; j++) {
                                for (int k = z; k < z1; k++) {
                                    used[index(i, j, k)] = true;
                                }
                            }
                        }
                        out.add(new double[]{(lo[0] + x) * r, (lo[1] + y) * r, (lo[2] + z) * r,
                                (lo[0] + x1) * r, (lo[1] + y1) * r, (lo[2] + z1) * r});
                    }
                }
            }
            return out;
        }

        private boolean free(boolean[] used, int x0, int x1, int y0, int y1, int z0, int z1) {
            for (int i = x0; i < x1; i++) {
                for (int j = y0; j < y1; j++) {
                    for (int k = z0; k < z1; k++) {
                        int at = index(i, j, k);
                        if (!cells[at] || used[at]) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }
    }

    /** Fills the inside of a closed piece: along x, every other crossing of a ray with its faces goes in. */
    private static void fill(Grid g, List<float[]> group) {
        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (float[] t : group) {
            for (int i = 0; i < 9; i++) {
                min[i % 3] = Math.min(min[i % 3], t[i]);
                max[i % 3] = Math.max(max[i % 3], t[i]);
            }
        }
        int j0 = Math.max(0, (int) Math.floor(min[1] / g.r) - g.lo[1]), j1 = Math.min(g.size[1] - 1, (int) Math.floor(max[1] / g.r) - g.lo[1]);
        int k0 = Math.max(0, (int) Math.floor(min[2] / g.r) - g.lo[2]), k1 = Math.min(g.size[2] - 1, (int) Math.floor(max[2] / g.r) - g.lo[2]);
        List<Double> hits = new ArrayList<>();
        for (int j = j0; j <= j1; j++) {
            double y = (g.lo[1] + j + 0.5) * g.r + NUDGE_A;
            for (int k = k0; k <= k1; k++) {
                double z = (g.lo[2] + k + 0.5) * g.r + NUDGE_B;
                hits.clear();
                for (float[] t : group) {
                    double x = crossing(t, y, z);
                    if (!Double.isNaN(x)) {
                        hits.add(x);
                    }
                }
                if (hits.size() < 2) {
                    continue;
                }
                hits.sort(null);
                // a ray through the edge two triangles share (a quad's diagonal) crosses both: once
                for (int h = hits.size() - 1; h > 0; h--) {
                    if (hits.get(h) - hits.get(h - 1) < 1e-9) {
                        hits.remove(h);
                    }
                }
                for (int h = 0; h + 1 < hits.size(); h += 2) {
                    // the voxels whose centres lie between going in and coming out
                    int i0 = (int) Math.ceil(hits.get(h) / g.r - 0.5) - g.lo[0];
                    int i1 = (int) Math.floor(hits.get(h + 1) / g.r - 0.5) - g.lo[0];
                    for (int i = Math.max(0, i0); i <= Math.min(g.size[0] - 1, i1); i++) {
                        g.cells[g.index(i, j, k)] = true;
                    }
                }
            }
        }
    }

    /** Where the line {y, z} = const crosses the triangle, as x; NaN if it misses. */
    private static double crossing(float[] t, double y, double z) {
        double ay = t[1], az = t[2], by = t[4], bz = t[5], cy = t[7], cz = t[8];
        double d = (by - ay) * (cz - az) - (cy - ay) * (bz - az);
        if (Math.abs(d) < 1e-12) {
            return Double.NaN; // seen edge on
        }
        double u = ((y - ay) * (cz - az) - (cy - ay) * (z - az)) / d;
        double v = ((by - ay) * (z - az) - (y - ay) * (bz - az)) / d;
        if (u < 0 || v < 0 || u + v > 1) {
            return Double.NaN;
        }
        return t[0] + u * (t[3] - t[0]) + v * (t[6] - t[0]);
    }

    /** Marks the voxels the triangle passes through, by points spread over it closer than half a voxel. */
    private static void surface(Grid g, float[] t) {
        double e1 = dist(t, 0, 3), e2 = dist(t, 0, 6), e3 = dist(t, 3, 6);
        int n = Math.max(1, (int) Math.ceil(Math.max(e1, Math.max(e2, e3)) / (g.r * 0.5)));
        n = Math.min(n, 512);
        // a face lying on a voxel boundary belongs to the voxel behind it, not the one in front; and the points on
        // its edges (which lie on the boundaries of the faces around it) are drawn a little into it
        double bx = -t[9] * INWARD, by = -t[10] * INWARD, bz = -t[11] * INWARD;
        double cx = (t[0] + t[3] + t[6]) / 3, cy = (t[1] + t[4] + t[7]) / 3, cz = (t[2] + t[5] + t[8]) / 3;
        for (int a = 0; a <= n; a++) {
            for (int b = 0; b <= n - a; b++) {
                double u = (double) a / n, v = (double) b / n;
                double x = t[0] + u * (t[3] - t[0]) + v * (t[6] - t[0]);
                double y = t[1] + u * (t[4] - t[1]) + v * (t[7] - t[1]);
                double z = t[2] + u * (t[5] - t[2]) + v * (t[8] - t[2]);
                g.set(x + (cx - x) * TOWARDS_MIDDLE + bx, y + (cy - y) * TOWARDS_MIDDLE + by, z + (cz - z) * TOWARDS_MIDDLE + bz);
            }
        }
    }

    private static double dist(float[] t, int i, int j) {
        double dx = t[i] - t[j], dy = t[i + 1] - t[j + 1], dz = t[i + 2] - t[j + 2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private Voxelizer() {}
}
