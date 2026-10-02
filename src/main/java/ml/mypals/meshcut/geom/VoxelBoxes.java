package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.List;

/**
 * Approximates "collision boxes ∩ half-spaces" with axis-aligned boxes, which is all Sable's voxel colliders
 * (and vanilla collision shapes) can represent. A slanted cut becomes a staircase of {@value #COLUMNS} steps per block.
 * Boxes are {@code {minX, minY, minZ, maxX, maxY, maxZ}} in block-local coordinates.
 */
public final class VoxelBoxes {
    /** Cells per side of the fallback grid (a thin piece that no column reaches). */
    public static final int RES = 8;
    /** Columns per side for the column approximation. */
    public static final int COLUMNS = 16;
    /** More boxes than this and a coarser division is used (the physics engine keeps every box). */
    private static final int MAX_BOXES = 128;
    /** Stretches shorter than this (in blocks) are left out. */
    private static final double MIN_SPAN = 1e-3;
    /** Two stretches this close count as the same, so columns can be joined. */
    private static final double SAME = 1e-6;
    private static final double TIE = 1e-9;

    public static List<double[]> cut(List<double[]> shape, List<CutPlane> planes) {
        return cut(shape, planes, List.of());
    }

    /**
     * @param excluded convex regions (each a list of half-spaces) that were cut out of the block: whatever lies
     *                 strictly inside one of them is gone
     */
    public static List<double[]> cut(List<double[]> shape, List<CutPlane> planes, List<List<CutPlane>> excluded) {
        if (planes.isEmpty() && excluded.isEmpty()) {
            return shape;
        }
        for (int columns = COLUMNS; columns >= RES; columns /= 2) {
            List<double[]> boxes = cutColumns(shape, planes, excluded, columns);
            if (!boxes.isEmpty() && boxes.size() <= MAX_BOXES) {
                return boxes;
            }
        }
        return cutCells(shape, planes, excluded);
    }

    // ---------------------------------------------------------------- columns

    /**
     * Boxes made of columns: along the axis the cuts are most nearly perpendicular to, the block is divided into
     * {@code columns} x {@code columns} columns, and in each the solid part is found exactly (a column is a line, so
     * every cut is just a limit on how far along it the block reaches). A slanted face becomes steps as wide as a
     * column, and only as high as the slope makes them, instead of cells of fixed height.
     */
    private static List<double[]> cutColumns(List<double[]> shape, List<CutPlane> planes, List<List<CutPlane>> excluded,
                                             int columns) {
        int axis = columnAxis(planes, excluded);
        int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2; // the two axes the columns are laid out over
        double[][][] spans = new double[columns * columns][][];
        for (int i = 0; i < columns; i++) {
            for (int j = 0; j < columns; j++) {
                double[] at = new double[3];
                at[u] = (i + 0.5) / columns;
                at[v] = (j + 0.5) / columns;
                spans[i * columns + j] = column(shape, planes, excluded, axis, at);
            }
        }
        // join neighbouring columns that are solid over the same stretch into one box
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

    /** The axis the cuts' normals point along the most, taken all together. */
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
        int best = 1; // ties go to up: most blocks lie on the ground
        for (int a = 0; a < 3; a++) {
            if (weight[a] > weight[best] + 1e-9) {
                best = a;
            }
        }
        return best;
    }

    /** The stretches {from, to} of the line through {@code at} along {@code axis} that are solid. */
    private static double[][] column(List<double[]> shape, List<CutPlane> planes, List<List<CutPlane>> excluded, int axis,
                                     double[] at) {
        int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
        // where the collision boxes of the imitated block are
        List<double[]> spans = new ArrayList<>();
        for (double[] b : shape) {
            if (at[u] >= b[u] && at[u] <= b[u + 3] && at[v] >= b[v] && at[v] <= b[v + 3]) {
                spans.add(new double[]{Math.max(0, b[axis]), Math.min(1, b[axis + 3])});
            }
        }
        // ... within every half-space
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
        // ... outside every part that was cut out
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

    /**
     * Narrows {@code limit} (a stretch along the axis) to what the half-space allows at the point. With
     * {@code strict} the stretch is the open inside of the half-space, as for a part that was cut out.
     *
     * @return {@code false} if nothing of the line is left
     */
    private static boolean limitBy(CutPlane p, int axis, double[] at, double[] limit, boolean strict) {
        int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
        double n = axis == 0 ? p.nx() : axis == 1 ? p.ny() : p.nz();
        double nu = u == 0 ? p.nx() : p.ny(), nv = v == 1 ? p.ny() : p.nz();
        double rest = p.d() - nu * at[u] - nv * at[v]; // keep: n * w <= rest
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

    /** Index of the unused stretch of the column that is the same as {@code span}, or -1. */
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

    // ---------------------------------------------------------------- cells

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

    /** Strictly inside: a cell on the boundary of a cut-out region still belongs to what is left around it. */
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

    /** Greedy meshing: grow each unvisited cell along x, then z, then y while the whole row/slab is filled. */
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

    /** Total volume of the boxes (boxes produced here never overlap). */
    public static double volume(List<double[]> boxes) {
        double v = 0;
        for (double[] b : boxes) {
            v += (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]);
        }
        return v;
    }

    private VoxelBoxes() {}
}
