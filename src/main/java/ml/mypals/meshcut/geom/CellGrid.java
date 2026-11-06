package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.List;

public final class CellGrid {
    public static final int N = 8;
    public static final int CELLS = N * N * N;
    private static final int MIN_COMPONENT_CELLS = 4;
    private static final double EDGE_TOLERANCE = 0.03;
    private static final double FACE_GAP = 0.03;
    private static final double FACE_REACH = 0.14;
    private static final double[][] CENTERS = centers();

    public static final CellGrid FULL = new CellGrid();

    public static final CellGrid EMPTY = new CellGrid(0);

    private final int[] comp = new int[CELLS];
    private final int[] size;
    private final int[][] faces = new int[6][N * N];

    private CellGrid() {
        java.util.Arrays.fill(comp, 0);
        size = new int[]{CELLS};
        for (int[] face : faces) {
            java.util.Arrays.fill(face, 0);
        }
    }

    private CellGrid(int none) {
        java.util.Arrays.fill(comp, -1);
        size = new int[0];
        for (int[] face : faces) {
            java.util.Arrays.fill(face, -1);
        }
    }

    private CellGrid(int[] components, int count, int[] sizes, List<CutPlane> real, List<List<CutPlane>> excluded,
                     List<Patch> patches, List<double[]> shape) {
        System.arraycopy(components, 0, comp, 0, CELLS);
        this.size = sizes;
        for (int axis = 0; axis < 3; axis++) {
            for (int a = 0; a < N; a++) {
                for (int b = 0; b < N; b++) {
                    faces[axis * 2][a * N + b] = faceSample(axis, 1, a, b, real, excluded, patches, shape);
                    faces[axis * 2 + 1][a * N + b] = faceSample(axis, 0, a, b, real, excluded, patches, shape);
                }
            }
        }
    }

    private int faceSample(int axis, int value, int a, int b, List<CutPlane> real, List<List<CutPlane>> excluded,
                           List<Patch> patches, List<double[]> shape) {
        double[] q = new double[3];
        int first = axis == 0 ? 1 : 0, second = axis == 2 ? 1 : 2;
        q[axis] = value;
        q[first] = (a + 0.5) / N;
        q[second] = (b + 0.5) / N;
        if (shape != null && !insideShape(shape, q)) {
            return -1;
        }
        for (CutPlane p : real) {
            if (p.signedDistance(q[0], q[1], q[2]) < -1e-6) {
                return -1;
            }
        }
        for (List<CutPlane> region : excluded) {
            boolean gone = true;
            for (CutPlane p : region) {
                if (p.signedDistance(q[0], q[1], q[2]) <= 1e-9) {
                    gone = false;
                    break;
                }
            }
            if (gone) {
                return -1;
            }
        }
        for (Patch patch : patches) {
            CutPlane p = patch.plane();
            if (Math.abs(p.signedDistance(q[0], q[1], q[2])) < FACE_GAP
                    && (patch.through() || patch.containsProjected(q[0], q[1], q[2], EDGE_TOLERANCE))) {
                return -1;
            }
        }
        int best = -1;
        double bestDistance = FACE_REACH * FACE_REACH;
        int depth = value == 1 ? N - 1 : 0;
        int inward = value == 1 ? -1 : 1;
        for (int layer = 0; layer < 2; layer++) {
            for (int da = -1; da <= 1; da++) {
                for (int db = -1; db <= 1; db++) {
                    int ca = a + da, cb = b + db;
                    if (ca < 0 || cb < 0 || ca >= N || cb >= N) {
                        continue;
                    }
                    int index = cell(axis, depth + inward * layer, ca, cb);
                    if (comp[index] < 0) {
                        continue;
                    }
                    double[] c = center(index);
                    double dist = (c[0] - q[0]) * (c[0] - q[0]) + (c[1] - q[1]) * (c[1] - q[1]) + (c[2] - q[2]) * (c[2] - q[2]);
                    if (dist < bestDistance && !blocked(patches, q, c)) {
                        bestDistance = dist;
                        best = comp[index];
                    }
                }
            }
        }
        return best;
    }

    public static CellGrid of(List<CutPlane> real, List<List<CutPlane>> excluded, List<Patch> patches) {
        return of(real, excluded, patches, null);
    }

    public static CellGrid of(List<CutPlane> real, List<List<CutPlane>> excluded, List<Patch> patches, List<double[]> shape) {
        try (var timing = ml.mypals.meshcut.util.CutTiming.phase("CellGrid.of")) {
            return build(real, excluded, patches, shape);
        }
    }

    private static CellGrid build(List<CutPlane> real, List<List<CutPlane>> excluded, List<Patch> patches, List<double[]> shape) {
        if (isWhole(shape)) {
            shape = null;
        }
        if (real.isEmpty() && excluded.isEmpty() && patches.isEmpty() && shape == null) {
            return FULL;
        }
        long[] occupied = CellMasks.occupied(real, excluded);
        long[][] barriers = new long[3][7];
        for (Patch patch : patches) {
            long[][] edges = patch.cellEdges();
            for (int axis = 0; axis < 3; axis++) for (int word = 0; word < 7; word++) barriers[axis][word] |= edges[axis][word];
        }
        boolean[] inside = new boolean[CELLS];
        for (int i = 0; i < CELLS; i++) {
            inside[i] = (occupied[i / 64] & (1L << (i % 64))) != 0 && (shape == null || touchesShape(shape, i));
        }

        int[] components = new int[CELLS];
        java.util.Arrays.fill(components, -1);
        int next = 0;
        int[] sizes = new int[CELLS];
        int[] queue = new int[CELLS];
        for (int start = 0; start < CELLS; start++) {
            if (!inside[start] || components[start] != -1) {
                continue;
            }
            int id = next;
            components[start] = id;
            int head = 0, tail = 0;
            queue[tail++] = start;
            int count = 0;
            while (head < tail) {
                int cur = queue[head++];
                count++;
                int x = cur / (N * N), y = cur / N % N, z = cur % N;
                for (int axis = 0; axis < 3; axis++) {
                    for (int dir = -1; dir <= 1; dir += 2) {
                        int nx = x + (axis == 0 ? dir : 0), ny = y + (axis == 1 ? dir : 0), nz = z + (axis == 2 ? dir : 0);
                        if (nx < 0 || ny < 0 || nz < 0 || nx >= N || ny >= N || nz >= N) {
                            continue;
                        }
                        int other = (nx * N + ny) * N + nz;
                        int lower = Math.min(cur, other);
                        int edge = axis == 0 ? lower : axis == 1
                                ? (lower / 64 * 7 + lower / 8 % 8) * 8 + lower % 8
                                : (lower / 8) * 7 + lower % 8;
                        if (inside[other] && components[other] == -1 && (barriers[axis][edge / 64] & (1L << (edge % 64))) == 0) {
                            components[other] = id;
                            queue[tail++] = other;
                        }
                    }
                }
            }
            if (count >= MIN_COMPONENT_CELLS) {
                sizes[id] = count;
                next++;
            } else {
                for (int i = 0; i < CELLS; i++) {
                    if (components[i] == id) {
                        components[i] = -1;
                    }
                }
            }
        }
        return new CellGrid(components, next, java.util.Arrays.copyOf(sizes, next), real, excluded, patches, shape);
    }

    private static boolean isWhole(List<double[]> shape) {
        if (shape == null) {
            return true;
        }
        for (double[] b : shape) {
            if (b[0] <= 1e-6 && b[1] <= 1e-6 && b[2] <= 1e-6 && b[3] >= 1 - 1e-6 && b[4] >= 1 - 1e-6 && b[5] >= 1 - 1e-6) {
                return true;
            }
        }
        return false;
    }

    private static boolean touchesShape(List<double[]> shape, int index) {
        int x = index / (N * N), y = index / N % N, z = index % N;
        double[] lo = {(double) x / N, (double) y / N, (double) z / N};
        for (double[] b : shape) {
            boolean overlaps = true;
            for (int axis = 0; axis < 3 && overlaps; axis++) {
                overlaps = b[axis] < lo[axis] + 1.0 / N - 1e-9 && b[axis + 3] > lo[axis] + 1e-9;
            }
            if (overlaps) {
                return true;
            }
        }
        return false;
    }

    private static boolean insideShape(List<double[]> shape, double[] q) {
        for (double[] b : shape) {
            if (q[0] >= b[0] - 1e-6 && q[0] <= b[3] + 1e-6 && q[1] >= b[1] - 1e-6 && q[1] <= b[4] + 1e-6
                    && q[2] >= b[2] - 1e-6 && q[2] <= b[5] + 1e-6) {
                return true;
            }
        }
        return false;
    }

    private static boolean blocked(List<Patch> patches, double[] ca, double[] cb) {
        for (Patch patch : patches) {
            CutPlane p = patch.plane();
            double da = p.d() - (p.nx() * ca[0] + p.ny() * ca[1] + p.nz() * ca[2]);
            double db = p.d() - (p.nx() * cb[0] + p.ny() * cb[1] + p.nz() * cb[2]);
            if ((da > 0) == (db > 0)) {
                continue;
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
        return CENTERS[index];
    }

    private static double[][] centers() {
        double[][] result = new double[CELLS][3];
        for (int i = 0; i < CELLS; i++) result[i] = new double[]{(i / 64 + .5) / N, (i / N % N + .5) / N, (i % N + .5) / N};
        return result;
    }

    static long[][] cellEdges(Patch patch) {
        long[][] result = new long[3][7];
        CutPlane p = patch.plane();
        for (int axis = 0; axis < 3; axis++) for (int a = 0; a < N; a++) for (int b = 0; b < N; b++) {
            int first = cell(axis, 0, a, b), last = cell(axis, N - 1, a, b);
            double[] ca = center(first), cb = center(last);
            double da = p.signedDistance(ca[0], ca[1], ca[2]), db = p.signedDistance(cb[0], cb[1], cb[2]);
            if (!Double.isFinite(da) || !Double.isFinite(db)) {
                for (int depth = 0; depth < N - 1; depth++) markEdge(result, patch, axis, depth, a, b);
                continue;
            }
            if ((da > 0) == (db > 0)) continue;
            int lo = 0, hi = N - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) / 2;
                double[] c = center(cell(axis, mid, a, b));
                if ((p.signedDistance(c[0], c[1], c[2]) > 0) == (da > 0)) lo = mid; else hi = mid;
            }
            markEdge(result, patch, axis, lo, a, b);
        }
        return result;
    }

    private static void markEdge(long[][] out, Patch patch, int axis, int depth, int a, int b) {
        int lower = cell(axis, depth, a, b), upper = cell(axis, depth + 1, a, b);
        if (!blocked(List.of(patch), center(lower), center(upper))) return;
        int edge = axis == 0 ? lower : axis == 1
                ? (lower / 64 * 7 + lower / 8 % 8) * 8 + lower % 8 : (lower / 8) * 7 + lower % 8;
        out[axis][edge / 64] |= 1L << (edge % 64);
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

    public int componentCount() {
        return size.length;
    }

    public int size(int component) {
        return size[component];
    }

    public int component(int index) {
        return comp[index];
    }

    public int faceComponent(int face, int index) {
        return faces[face][index];
    }

    public static double[] centerOf(int index) {
        return center(index).clone();
    }

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

    public int filledCells() {
        int n = 0;
        for (int s : size) {
            n += s;
        }
        return n;
    }

    @Override
    public String toString() {
        return "CellGrid" + java.util.Arrays.toString(size);
    }
}
