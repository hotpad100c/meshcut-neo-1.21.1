package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.List;

/**
 * Approximates "collision boxes ∩ half-spaces" with axis-aligned boxes, which is all Sable's voxel colliders
 * (and vanilla collision shapes) can represent. A slanted cut becomes a staircase of {@value #RES} steps per block.
 * Boxes are {@code {minX, minY, minZ, maxX, maxY, maxZ}} in block-local coordinates.
 */
public final class VoxelBoxes {
    public static final int RES = 8;
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
