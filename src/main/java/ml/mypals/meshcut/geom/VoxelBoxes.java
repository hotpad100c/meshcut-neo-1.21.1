package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.List;

public final class VoxelBoxes {
    public static final int RES = 8;
    public static final int COLUMNS = 16;
    private static final int MAX_BOXES = 128;
    private static final double MIN_SPAN = 1e-3;
    private static final double SAME = 1e-6;
    private static final double TIE = 1e-9;

    public static List<double[]> cut(List<double[]> shape, List<CutPlane> planes) {
        return cut(shape, planes, List.of());
    }

    public static List<double[]> cut(List<double[]> shape, List<CutPlane> planes, List<List<CutPlane>> excluded) {
        boolean[] mask=VoxelFragments.mask(excluded);
        if(mask!=null) {
            List<double[]> retained=new ArrayList<>();
            for(double[] cell:VoxelFragments.boxes(mask)) for(double[] original:shape) {
                double[] box=new double[6];
                boolean valid=true;
                for(int axis=0;axis<3;axis++) {
                    box[axis]=Math.max(cell[axis],original[axis]);
                    box[axis+3]=Math.min(cell[axis+3],original[axis+3]);
                    valid &= box[axis+3]-box[axis]>1e-9;
                }
                if(valid) retained.add(box);
            }
            return planes.isEmpty()?retained:remaining(retained,planes,List.of());
        }
        if (planes.isEmpty() && excluded.isEmpty()) {
            return shape;
        }
        for (int columns = COLUMNS; columns >= RES; columns /= 2) {
            List<double[]> boxes = cutColumns(shape, planes, excluded, columns);
            if (!boxes.isEmpty() && boxes.size() <= MAX_BOXES) {
                return boxes;
            }
        }
        List<double[]> cells = cutCells(shape, planes, excluded);
        if (cells.isEmpty() && !shape.isEmpty()) {
            double h = 0.5 / RES;
            cells = new ArrayList<>(List.of(new double[]{0.5 - h, 0.5 - h, 0.5 - h, 0.5 + h, 0.5 + h, 0.5 + h}));
        }
        return cells;
    }

    public static List<double[]> join(List<double[]> boxes, int limit) {
        List<double[]> out = new ArrayList<>(boxes.size());
        for (double[] b : boxes) {
            out.add(b.clone());
        }
        while (out.size() > Math.max(1, limit)) {
            int bestA = 0, bestB = 1;
            double bestWaste = Double.MAX_VALUE;
            for (int a = 0; a < out.size(); a++) {
                for (int b = a + 1; b < out.size(); b++) {
                    double[] p = out.get(a), q = out.get(b);
                    double union = (Math.max(p[3], q[3]) - Math.min(p[0], q[0])) * (Math.max(p[4], q[4]) - Math.min(p[1], q[1]))
                            * (Math.max(p[5], q[5]) - Math.min(p[2], q[2]));
                    double waste = union - boxVolume(p) - boxVolume(q);
                    if (waste < bestWaste) {
                        bestWaste = waste;
                        bestA = a;
                        bestB = b;
                    }
                }
            }
            double[] p = out.get(bestA), q = out.remove(bestB);
            for (int k = 0; k < 3; k++) {
                p[k] = Math.min(p[k], q[k]);
                p[k + 3] = Math.max(p[k + 3], q[k + 3]);
            }
        }
        return out;
    }

    private static double boxVolume(double[] b) {
        return (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]);
    }

    public static double remainingVolume(List<double[]> shape, List<CutPlane> planes, List<List<CutPlane>> excluded) {
        return volume(remaining(shape, planes, excluded));
    }

    public static List<double[]> remaining(List<double[]> shape, List<CutPlane> planes, List<List<CutPlane>> excluded) {
        if (planes.isEmpty() && excluded.isEmpty()) {
            return shape;
        }
        List<double[]> boxes = cutColumns(shape, planes, excluded, COLUMNS);
        if (boxes.isEmpty()) {
            boxes = cutCells(shape, planes, excluded);
        }
        return boxes;
    }

    private static List<double[]> cutColumns(List<double[]> shape, List<CutPlane> planes, List<List<CutPlane>> excluded,
                                             int columns) {
        int axis = columnAxis(planes, excluded);
        int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
        double[][][] spans = new double[columns * columns][][];
        for (int i = 0; i < columns; i++) {
            for (int j = 0; j < columns; j++) {
                double[] at = new double[3];
                at[u] = (i + 0.5) / columns;
                at[v] = (j + 0.5) / columns;
                spans[i * columns + j] = column(shape, planes, excluded, axis, at);
            }
        }
        boolean[][] used = new boolean[columns * columns][];
        for (int c = 0; c < used.length; c++) {
            used[c] = new boolean[spans[c].length];
        }
        List<double[]> boxes = new ArrayList<>();
        for (int i = 0; i < columns; i++) {
            for (int j = 0; j < columns; j++) {
                double[][] here = spans[i * columns + j];
                for (int k = 0; k < here.length; k++) {
                    if (used[i * columns + j][k]) {
                        continue;
                    }
                    double[] span = here[k];
                    used[i * columns + j][k] = true;
                    int i2 = i;
                    while (i2 + 1 < columns && matching(spans, used, (i2 + 1) * columns + j, span) >= 0) {
                        i2++;
                        used[i2 * columns + j][matching(spans, used, i2 * columns + j, span)] = true;
                    }
                    int j2 = j;
                    while (j2 + 1 < columns && rowMatches(spans, used, i, i2, j2 + 1, columns, span)) {
                        j2++;
                        for (int a = i; a <= i2; a++) {
                            used[a * columns + j2][matching(spans, used, a * columns + j2, span)] = true;
                        }
                    }
                    double[] box = new double[6];
                    box[u] = (double) i / columns;
                    box[u + 3] = (double) (i2 + 1) / columns;
                    box[v] = (double) j / columns;
                    box[v + 3] = (double) (j2 + 1) / columns;
                    box[axis] = span[0];
                    box[axis + 3] = span[1];
                    boxes.add(box);
                }
            }
        }
        return boxes;
    }

    private static int columnAxis(List<CutPlane> planes, List<List<CutPlane>> excluded) {
        double[] weight = new double[3];
        for (CutPlane p : planes) {
            weight[0] += Math.abs(p.nx());
            weight[1] += Math.abs(p.ny());
            weight[2] += Math.abs(p.nz());
        }
        for (List<CutPlane> region : excluded) {
            for (CutPlane p : region) {
                weight[0] += Math.abs(p.nx());
                weight[1] += Math.abs(p.ny());
                weight[2] += Math.abs(p.nz());
            }
        }
        int best = 1;
        for (int a = 0; a < 3; a++) {
            if (weight[a] > weight[best] + 1e-9) {
                best = a;
            }
        }
        return best;
    }

    private static double[][] column(List<double[]> shape, List<CutPlane> planes, List<List<CutPlane>> excluded, int axis,
                                     double[] at) {
        int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
        List<double[]> spans = new ArrayList<>();
        for (double[] b : shape) {
            if (at[u] >= b[u] && at[u] <= b[u + 3] && at[v] >= b[v] && at[v] <= b[v + 3]) {
                spans.add(new double[]{Math.max(0, b[axis]), Math.min(1, b[axis + 3])});
            }
        }
        double[] limit = {0, 1};
        for (CutPlane p : planes) {
            if (!limitBy(p, axis, at, limit, false)) {
                return new double[0][];
            }
        }
        List<double[]> kept = new ArrayList<>();
        for (double[] s : spans) {
            double from = Math.max(s[0], limit[0]), to = Math.min(s[1], limit[1]);
            if (to - from > MIN_SPAN) {
                kept.add(new double[]{from, to});
            }
        }
        for (List<CutPlane> region : excluded) {
            double[] inside = {0, 1};
            boolean any = true;
            for (CutPlane p : region) {
                if (!limitBy(p, axis, at, inside, true)) {
                    any = false;
                    break;
                }
            }
            if (!any) {
                continue;
            }
            List<double[]> rest = new ArrayList<>();
            for (double[] s : kept) {
                if (s[0] < inside[0] - TIE) {
                    rest.add(new double[]{s[0], Math.min(s[1], inside[0])});
                }
                if (s[1] > inside[1] + TIE) {
                    rest.add(new double[]{Math.max(s[0], inside[1]), s[1]});
                }
            }
            kept = new ArrayList<>();
            for (double[] s : rest) {
                if (s[1] - s[0] > MIN_SPAN) {
                    kept.add(s);
                }
            }
        }
        return kept.toArray(new double[0][]);
    }

    private static boolean limitBy(CutPlane p, int axis, double[] at, double[] limit, boolean strict) {
        int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
        double n = axis == 0 ? p.nx() : axis == 1 ? p.ny() : p.nz();
        double nu = u == 0 ? p.nx() : p.ny(), nv = v == 1 ? p.ny() : p.nz();
        double rest = p.d() - nu * at[u] - nv * at[v];
        if (Math.abs(n) < 1e-9) {
            return strict ? rest > TIE : rest >= -TIE;
        }
        double bound = rest / n;
        if (n > 0) {
            limit[1] = Math.min(limit[1], bound);
        } else {
            limit[0] = Math.max(limit[0], bound);
        }
        return limit[1] - limit[0] > (strict ? 0 : -TIE);
    }

    private static int matching(double[][][] spans, boolean[][] used, int column, double[] span) {
        for (int k = 0; k < spans[column].length; k++) {
            if (!used[column][k] && Math.abs(spans[column][k][0] - span[0]) < SAME && Math.abs(spans[column][k][1] - span[1]) < SAME) {
                return k;
            }
        }
        return -1;
    }

    private static boolean rowMatches(double[][][] spans, boolean[][] used, int i1, int i2, int j, int columns, double[] span) {
        for (int i = i1; i <= i2; i++) {
            if (matching(spans, used, i * columns + j, span) < 0) {
                return false;
            }
        }
        return true;
    }

    private static List<double[]> cutCells(List<double[]> shape, List<CutPlane> planes, List<List<CutPlane>> excluded) {
        final int n = RES;
        boolean[] filled = new boolean[n * n * n];
        boolean any = false;
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                for (int z = 0; z < n; z++) {
                    double cx = (x + 0.5) / n, cy = (y + 0.5) / n, cz = (z + 0.5) / n;
                    boolean in = insideAny(shape, cx, cy, cz) && insideAll(planes, cx, cy, cz)
                            && !insideAnyRegion(excluded, cx, cy, cz);
                    filled[index(x, y, z)] = in;
                    any |= in;
                }
            }
        }
        if (!any) {
            // A piece thinner than one cell has no cell centre inside it. It still needs a collider, or it would
            // fall through the floor: take every cell that has any corner inside instead.
            for (int x = 0; x < n; x++) {
                for (int y = 0; y < n; y++) {
                    for (int z = 0; z < n; z++) {
                        filled[index(x, y, z)] = anyCornerInside(shape, planes, x, y, z, n) && !insideAnyRegion(excluded,
                                (x + 0.5) / n, (y + 0.5) / n, (z + 0.5) / n);
                    }
                }
            }
        }
        return merge(filled, n);
    }

    private static boolean insideAnyRegion(List<List<CutPlane>> regions, double x, double y, double z) {
        for (List<CutPlane> region : regions) {
            boolean inside = true;
            for (CutPlane p : region) {
                if (p.signedDistance(x, y, z) <= TIE) {
                    inside = false;
                    break;
                }
            }
            if (inside) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyCornerInside(List<double[]> shape, List<CutPlane> planes, int x, int y, int z, int n) {
        for (int i = 0; i < 8; i++) {
            double px = (x + (i & 1)) / (double) n, py = (y + (i >> 1 & 1)) / (double) n, pz = (z + (i >> 2 & 1)) / (double) n;
            boolean inside = true;
            for (CutPlane p : planes) {
                if (p.signedDistance(px, py, pz) < 0) {
                    inside = false;
                    break;
                }
            }
            if (inside && insideAny(shape, px, py, pz)) {
                return true;
            }
        }
        return false;
    }

    private static boolean insideAny(List<double[]> boxes, double x, double y, double z) {
        for (double[] b : boxes) {
            if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) {
                return true;
            }
        }
        return false;
    }

    private static boolean insideAll(List<CutPlane> planes, double x, double y, double z) {
        for (CutPlane p : planes) {
            double dist = p.signedDistance(x, y, z);
            if (dist < -TIE || (dist <= TIE && !keepsTies(p))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Cells whose centre lies exactly on a plane would otherwise belong to both halves of a cut.
     * A plane and its {@link CutPlane#flipped() flip} have opposite normals, so exactly one of them keeps ties.
     */
    private static boolean keepsTies(CutPlane p) {
        double first = Math.abs(p.nx()) > TIE ? p.nx() : Math.abs(p.ny()) > TIE ? p.ny() : p.nz();
        return first < 0;
    }

    private static int index(int x, int y, int z) {
        return (x * RES + y) * RES + z;
    }

    private static List<double[]> merge(boolean[] filled, int n) {
        boolean[] used = new boolean[filled.length];
        List<double[]> boxes = new ArrayList<>();
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                for (int z = 0; z < n; z++) {
                    if (!filled[index(x, y, z)] || used[index(x, y, z)]) {
                        continue;
                    }
                    int x2 = x;
                    while (x2 + 1 < n && free(filled, used, x2 + 1, y, z)) {
                        x2++;
                    }
                    int z2 = z;
                    while (z2 + 1 < n && rowFree(filled, used, x, x2, y, z2 + 1)) {
                        z2++;
                    }
                    int y2 = y;
                    while (y2 + 1 < n && slabFree(filled, used, x, x2, y2 + 1, z, z2)) {
                        y2++;
                    }
                    for (int i = x; i <= x2; i++) {
                        for (int j = y; j <= y2; j++) {
                            for (int k = z; k <= z2; k++) {
                                used[index(i, j, k)] = true;
                            }
                        }
                    }
                    boxes.add(new double[]{(double) x / n, (double) y / n, (double) z / n,
                            (double) (x2 + 1) / n, (double) (y2 + 1) / n, (double) (z2 + 1) / n});
                }
            }
        }
        return boxes;
    }

    private static boolean free(boolean[] filled, boolean[] used, int x, int y, int z) {
        return filled[index(x, y, z)] && !used[index(x, y, z)];
    }

    private static boolean rowFree(boolean[] filled, boolean[] used, int x1, int x2, int y, int z) {
        for (int x = x1; x <= x2; x++) {
            if (!free(filled, used, x, y, z)) {
                return false;
            }
        }
        return true;
    }

    private static boolean slabFree(boolean[] filled, boolean[] used, int x1, int x2, int y, int z1, int z2) {
        for (int z = z1; z <= z2; z++) {
            if (!rowFree(filled, used, x1, x2, y, z)) {
                return false;
            }
        }
        return true;
    }

    public static double volume(List<double[]> boxes) {
        double v = 0;
        for (double[] b : boxes) {
            v += (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]);
        }
        return v;
    }

    private VoxelBoxes() {}
}
