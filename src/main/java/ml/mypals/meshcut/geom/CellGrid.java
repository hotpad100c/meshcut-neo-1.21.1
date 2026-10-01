package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayDeque;
import java.util.List;

/**
 * A block cut into {@value #N}³ small cells, to find which parts of it are still joined: two neighbouring cells are
 * joined unless the line between their centres runs through a cut. The connected groups are the pieces the block
 * has fallen into, whatever their shape; cells outside the block's real cuts are empty.
 * <p>
 * Also records, for each face of the block, which group each face cell belongs to, so neighbouring blocks can be
 * joined cell to cell.
 */
public final class CellGrid {
    public static final int N = 8;
    public static final int CELLS = N * N * N;
    /** Groups smaller than this many cells (a thousandth of a block) are slivers and are dropped. */
    private static final int MIN_COMPONENT_CELLS = 4;
    /** How far past its edge a patch still blocks (the polygon is clipped exactly at the block's boundary). */
    private static final double EDGE_TOLERANCE = 0.03;

    /** Every cell is part of one piece: the block has no cuts. */
    public static final CellGrid FULL = new CellGrid();

    private final int[] comp = new int[CELLS];
    private final int[] size;
    /** Face {@code f} = axis*2 + (positive side ? 0 : 1); cell index along it: the two other axes, in order. */
    private final int[][] faces = new int[6][N * N];

    private CellGrid() {
        java.util.Arrays.fill(comp, 0);
        size = new int[]{CELLS};
        for (int[] face : faces) {
            java.util.Arrays.fill(face, 0);
        }
    }

    private CellGrid(int[] components, int count, int[] sizes) {
        System.arraycopy(components, 0, comp, 0, CELLS);
        this.size = sizes;
        for (int axis = 0; axis < 3; axis++) {
            for (int a = 0; a < N; a++) {
                for (int b = 0; b < N; b++) {
                    int low = cell(axis, 0, a, b), high = cell(axis, N - 1, a, b);
                    faces[axis * 2][a * N + b] = comp[high];
                    faces[axis * 2 + 1][a * N + b] = comp[low];
                }
            }
        }
    }

    /**
     * The groups the block's cells fall into, given its real cuts, the convex parts that were cut out of it, and the
     * scratches on it.
     */
    public static CellGrid of(List<CutPlane> real, List<List<CutPlane>> excluded, List<Patch> patches) {
        if (real.isEmpty() && excluded.isEmpty() && patches.isEmpty()) {
            return FULL;
        }
        boolean[] inside = new boolean[CELLS];
        for (int i = 0; i < CELLS; i++) {
            double[] c = center(i);
            boolean in = true;
            for (CutPlane p : real) {
                if (p.signedDistance(c[0], c[1], c[2]) < 0) {
                    in = false;
                    break;
                }
            }
            for (int r = 0; in && r < excluded.size(); r++) { // strictly inside a cut-out part: gone
                boolean gone = true;
                for (CutPlane p : excluded.get(r)) {
                    if (p.signedDistance(c[0], c[1], c[2]) <= 1e-9) {
                        gone = false;
                        break;
                    }
                }
                in = !gone;
            }
            inside[i] = in;
        }

        int[] components = new int[CELLS];
        java.util.Arrays.fill(components, -1);
        int next = 0;
        int[] sizes = new int[CELLS];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int start = 0; start < CELLS; start++) {
            if (!inside[start] || components[start] != -1) {
                continue;
            }
            int id = next;
            components[start] = id;
            queue.add(start);
            int count = 0;
            while (!queue.isEmpty()) {
                int cur = queue.poll();
                count++;
                int x = cur / (N * N), y = cur / N % N, z = cur % N;
                for (int axis = 0; axis < 3; axis++) {
                    for (int dir = -1; dir <= 1; dir += 2) {
                        int nx = x + (axis == 0 ? dir : 0), ny = y + (axis == 1 ? dir : 0), nz = z + (axis == 2 ? dir : 0);
                        if (nx < 0 || ny < 0 || nz < 0 || nx >= N || ny >= N || nz >= N) {
                            continue;
                        }
                        int other = (nx * N + ny) * N + nz;
                        if (inside[other] && components[other] == -1 && !blocked(patches, cur, other)) {
                            components[other] = id;
                            queue.add(other);
                        }
                    }
                }
            }
            if (count >= MIN_COMPONENT_CELLS) {
                sizes[id] = count;
                next++;
            } else {
                for (int i = 0; i < CELLS; i++) { // a sliver: forget it, and let its id be reused
                    if (components[i] == id) {
                        components[i] = -1;
                    }
                }
            }
        }
        // slivers were dropped but may have left their cells marked "visited"; they are simply empty now
        return new CellGrid(components, next, java.util.Arrays.copyOf(sizes, next));
    }

    /** Whether some patch lies between the centres of two neighbouring cells. */
    private static boolean blocked(List<Patch> patches, int a, int b) {
        double[] ca = center(a), cb = center(b);
        for (Patch patch : patches) {
            CutPlane p = patch.plane();
            double da = p.d() - (p.nx() * ca[0] + p.ny() * ca[1] + p.nz() * ca[2]);
            double db = p.d() - (p.nx() * cb[0] + p.ny() * cb[1] + p.nz() * cb[2]);
            if ((da > 0) == (db > 0)) {
                continue; // both on the same side
            }
            double t = da / (da - db);
            double hx = ca[0] + (cb[0] - ca[0]) * t, hy = ca[1] + (cb[1] - ca[1]) * t, hz = ca[2] + (cb[2] - ca[2]) * t;
            if (patch.through() || patch.containsProjected(hx, hy, hz, EDGE_TOLERANCE)) {
                return true;
            }
        }
        return false;
    }

    private static double[] center(int index) {
        return new double[]{(index / (N * N) + 0.5) / N, (index / N % N + 0.5) / N, (index % N + 0.5) / N};
    }

    private static int cell(int axis, int along, int a, int b) {
        int x, y, z;
        if (axis == 0) {
            x = along;
            y = a;
            z = b;
        } else if (axis == 1) {
            x = a;
            y = along;
            z = b;
        } else {
            x = a;
            y = b;
            z = along;
        }
        return (x * N + y) * N + z;
    }

    // ---------------------------------------------------------------- queries

    public int componentCount() {
        return size.length;
    }

    /** Number of cells in the group. */
    public int size(int component) {
        return size[component];
    }

    /** The group of cell {@code index} (see {@link #center}), or -1 if it is empty. */
    public int component(int index) {
        return comp[index];
    }

    /** The group touching the given face at cell {@code index} along it, or -1. */
    public int faceComponent(int face, int index) {
        return faces[face][index];
    }

    /** Centre of cell {@code index}, in block-local coordinates. */
    public static double[] centerOf(int index) {
        return center(index);
    }

    /** Average position of the group's cells, in block-local coordinates. */
    public double[] centroid(int component) {
        double x = 0, y = 0, z = 0;
        int n = 0;
        for (int i = 0; i < CELLS; i++) {
            if (comp[i] == component) {
                double[] c = center(i);
                x += c[0];
                y += c[1];
                z += c[2];
                n++;
            }
        }
        return n == 0 ? new double[]{0.5, 0.5, 0.5} : new double[]{x / n, y / n, z / n};
    }

    /** Total number of non-empty cells. */
    public int filledCells() {
        int n = 0;
        for (int s : size) {
            n += s;
        }
        return n;
    }

    /** How many cells of the group are within each of the groups of {@code face}: not needed outside tests. */
    @Override
    public String toString() {
        return "CellGrid" + java.util.Arrays.toString(size);
    }
}
