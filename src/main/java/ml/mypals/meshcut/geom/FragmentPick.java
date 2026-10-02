package ml.mypals.meshcut.geom;

/** Which fragment of a block a line of sight meets first. */
public final class FragmentPick {
    private static final double STEP = 1.0 / 32;

    /**
     * @param origin the eye, in the block's own frame (the block is the unit cube)
     * @param dir    the direction of sight in the same frame
     * @return the fragment first met inside the block, or the one closest to the line if it passes through gaps only;
     *         -1 if the block has none
     */
    public static int pick(CellGrid grid, double[] origin, double[] dir) {
        double enter = 0, exit = 16;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(dir[axis]) < 1e-9) {
                if (origin[axis] < 0 || origin[axis] > 1) {
                    return nearest(grid, origin, dir, 0, 16);
                }
                continue;
            }
            double t0 = (0 - origin[axis]) / dir[axis], t1 = (1 - origin[axis]) / dir[axis];
            enter = Math.max(enter, Math.min(t0, t1));
            exit = Math.min(exit, Math.max(t0, t1));
        }
        if (enter > exit) {
            return nearest(grid, origin, dir, 0, 16);
        }
        for (double t = enter + 1e-4; t <= exit; t += STEP) {
            int x = (int) Math.floor((origin[0] + dir[0] * t) * CellGrid.N);
            int y = (int) Math.floor((origin[1] + dir[1] * t) * CellGrid.N);
            int z = (int) Math.floor((origin[2] + dir[2] * t) * CellGrid.N);
            if (x < 0 || y < 0 || z < 0 || x >= CellGrid.N || y >= CellGrid.N || z >= CellGrid.N) {
                continue;
            }
            int comp = grid.component((x * CellGrid.N + y) * CellGrid.N + z);
            if (comp >= 0) {
                return comp;
            }
        }
        return nearest(grid, origin, dir, enter, exit);
    }

    private static int nearest(CellGrid grid, double[] o, double[] d, double from, double to) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < CellGrid.CELLS; i++) {
            int comp = grid.component(i);
            if (comp < 0) {
                continue;
            }
            double[] c = CellGrid.centerOf(i);
            double t = Math.max(from, Math.min(to, (c[0] - o[0]) * d[0] + (c[1] - o[1]) * d[1] + (c[2] - o[2]) * d[2]));
            double dx = o[0] + d[0] * t - c[0], dy = o[1] + d[1] * t - c[1], dz = o[2] + d[2] * t - c[2];
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDistance) {
                bestDistance = dist;
                best = comp;
            }
        }
        return best;
    }

    private FragmentPick() {}
}
