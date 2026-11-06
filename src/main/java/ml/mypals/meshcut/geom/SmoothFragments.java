package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SmoothFragments {
    private static final int N = CellGrid.N;
    private static final double MIN_SPLIT = 2e-3;
    private static final double MERGE_COS = Math.cos(Math.toRadians(9));
    private static final double MERGE_DISTANCE = 0.025;
    private static final int MAX_PLANES = 6;
    private static final int MAX_PIECES = 48;
    private static final double MIN_AREA = 1e-7;

    private record Face(double[] v, int kind) {}

    private static final class Piece {
        final List<Face> faces;
        final int[] ids;
        final boolean[] positive;

        Piece(List<Face> faces, int[] ids, boolean[] positive) {
            this.faces = faces;
            this.ids = ids;
            this.positive = positive;
        }
    }

    public record Boundary(double[] verts, double nx, double ny, double nz, String key) {}

    private record SplitPlane(int id, CutPlane plane, boolean material) {}
    private record CellKey(int cell, List<SplitPlane> cuts) {}
    private static final Map<CellKey, List<Piece>> PARTITIONS = new java.util.LinkedHashMap<>(128, 0.75f, true);
    private static int cachedPieces;
    private static final int MAX_CACHED_PIECES = 32768;
    private static final int MAX_CACHED_CELLS = 8192;
    private int reusedCells, extendedCells, rebuiltCells;

    public record CacheStats(int reusedCells, int extendedCells, int rebuiltCells) {}
    public CacheStats cacheStats() { return new CacheStats(reusedCells, extendedCells, rebuiltCells); }
    public static void clearCellCache() {
        synchronized (PARTITIONS) { PARTITIONS.clear(); cachedPieces = 0; }
    }

    private static List<Piece> cached(CellKey key) {
        synchronized (PARTITIONS) { return PARTITIONS.get(key); }
    }

    private static void remember(CellKey key, List<Piece> pieces) {
        synchronized (PARTITIONS) {
            List<Piece> old = PARTITIONS.put(key, List.copyOf(pieces));
            cachedPieces += pieces.size() - (old == null ? 0 : old.size());
            var entries = PARTITIONS.entrySet().iterator();
            while (cachedPieces > MAX_CACHED_PIECES || PARTITIONS.size() > MAX_CACHED_CELLS) {
                cachedPieces -= entries.next().getValue().size();
                entries.remove();
            }
        }
    }

    private final boolean[] mask;
    private final List<Patch> patches;
    private final List<CutPlane> real;
    private final List<CutPlane> planes = new ArrayList<>();
    private final double[][] patchBounds;
    private final Piece[][] cells = new Piece[CellGrid.CELLS][];
    private final boolean[] whole = new boolean[CellGrid.CELLS];
    private final List<CutPlane> transitions = new ArrayList<>();

    public SmoothFragments(boolean[] mask, List<Patch> patches, List<CutPlane> real) {
        this.mask = mask.clone();
        this.patches = List.copyOf(patches);
        this.real = List.copyOf(real);
        planes.addAll(real);
        patchBounds = new double[patches.size()][6];
        for (int i = 0; i < patchBounds.length; i++) {
            float[] v = patches.get(i).polygon();
            double[] b = patchBounds[i];
            java.util.Arrays.fill(b, 0, 3, Double.MAX_VALUE);
            java.util.Arrays.fill(b, 3, 6, -Double.MAX_VALUE);
            for (int k = 0; k < v.length; k++) {
                b[k % 3] = Math.min(b[k % 3], v[k] - 0.01);
                b[k % 3 + 3] = Math.max(b[k % 3 + 3], v[k] + 0.01);
            }
        }
        for (CutPlane p : surfaces(patches, real)) {
            planes.add(p);
        }
        for (int cell = 0; cell < CellGrid.CELLS; cell++) {
            cells[cell] = divide(cell);
        }
        for (int axis = 0; axis < 3; axis++) {
            for (int at = 1; at < N; at++) {
                boolean changes = false;
                for (int a = 0; a < N && !changes; a++) {
                    for (int b = 0; b < N && !changes; b++) {
                        int[] lo = new int[3], hi = new int[3];
                        lo[axis] = at - 1;
                        hi[axis] = at;
                        lo[(axis + 1) % 3] = hi[(axis + 1) % 3] = a;
                        lo[(axis + 2) % 3] = hi[(axis + 2) % 3] = b;
                        changes = mask[(lo[0] * N + lo[1]) * N + lo[2]] != mask[(hi[0] * N + hi[1]) * N + hi[2]];
                    }
                }
                if (changes) {
                    double[] normal = new double[3];
                    normal[axis] = 1;
                    transitions.add(new CutPlane(normal[0], normal[1], normal[2], at / (double) N));
                }
            }
        }
    }

    private static List<CutPlane> surfaces(List<Patch> patches, List<CutPlane> real) {
        List<Patch> bySize = new ArrayList<>(patches);
        bySize.sort((a, b) -> Double.compare(Patch.area(b.polygon()), Patch.area(a.polygon())));
        List<double[]> clusters = new ArrayList<>();
        for (Patch patch : bySize) {
            float[] poly = patch.polygon();
            double w = Math.max(1e-6, Patch.area(poly));
            double cx = 0, cy = 0, cz = 0;
            int n = poly.length / 3;
            for (int i = 0; i < n; i++) {
                cx += poly[i * 3];
                cy += poly[i * 3 + 1];
                cz += poly[i * 3 + 2];
            }
            cx /= n;
            cy /= n;
            cz /= n;
            CutPlane p = patch.plane();
            int best = -1;
            double bestScore = Double.MAX_VALUE;
            for (int i = 0; i < clusters.size(); i++) {
                double[] c = clusters.get(i);
                double len = Math.sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2]);
                double dot = (p.nx() * c[0] + p.ny() * c[1] + p.nz() * c[2]) / len;
                double distance = Math.abs((c[0] * (cx - c[3] / c[6]) + c[1] * (cy - c[4] / c[6]) + c[2] * (cz - c[5] / c[6])) / len);
                boolean close = Math.abs(dot) >= MERGE_COS && distance <= MERGE_DISTANCE;
                double score = (1 - Math.abs(dot)) + distance;
                if ((close || clusters.size() >= MAX_PLANES) && score < bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            if (best < 0) {
                clusters.add(new double[]{p.nx() * w, p.ny() * w, p.nz() * w, cx * w, cy * w, cz * w, w});
            } else {
                double[] c = clusters.get(best);
                double sign = p.nx() * c[0] + p.ny() * c[1] + p.nz() * c[2] >= 0 ? 1 : -1;
                c[0] += sign * p.nx() * w;
                c[1] += sign * p.ny() * w;
                c[2] += sign * p.nz() * w;
                c[3] += cx * w;
                c[4] += cy * w;
                c[5] += cz * w;
                c[6] += w;
            }
        }
        List<CutPlane> out = new ArrayList<>();
        for (double[] c : clusters) {
            CutPlane plane = CutPlane.of(c[0], c[1], c[2], (c[0] * c[3] + c[1] * c[4] + c[2] * c[5]) / c[6]);
            if (plane == null) {
                continue;
            }
            boolean onReal = false;
            for (CutPlane r : real) {
                double dot = plane.nx() * r.nx() + plane.ny() * r.ny() + plane.nz() * r.nz();
                double offset = plane.signedDistance(0.5, 0.5, 0.5) - Math.copySign(1, dot) * r.signedDistance(0.5, 0.5, 0.5);
                onReal |= Math.abs(dot) > MERGE_COS && Math.abs(offset) < 0.02;
            }
            if (!onReal) {
                out.add(plane);
            }
        }
        return out;
    }

    private Piece[] divide(int cell) {
        int x = cell / (N * N), y = cell / N % N, z = cell % N;
        double[] b = {x / (double) N, y / (double) N, z / (double) N, (x + 1) / (double) N, (y + 1) / (double) N, (z + 1) / (double) N};
        List<Integer> crossing = new ArrayList<>();
        for (int k = 0; k < planes.size(); k++) {
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (int corner = 0; corner < 8; corner++) {
                double d = planes.get(k).signedDistance(b[(corner & 1) * 3], b[1 + (corner >> 1 & 1) * 3], b[2 + (corner >> 2 & 1) * 3]);
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
            if (k < real.size() && max <= MIN_SPLIT) {
                return new Piece[0];
            }
            if (min < -MIN_SPLIT && max > MIN_SPLIT) {
                crossing.add(k);
            }
        }
        List<SplitPlane> cuts = crossing.stream()
                .map(k -> new SplitPlane(k, planes.get(k), k < real.size())).toList();
        CellKey key = new CellKey(cell, cuts);
        List<Piece> pieces = cached(key);
        int start = cuts.size();
        if (pieces != null) {
            reusedCells++;
        } else {
            start = cuts.size() - 1;
            for (; start > 0; start--) {
                pieces = cached(new CellKey(cell, List.copyOf(cuts.subList(0, start))));
                if (pieces != null) break;
            }
            if (pieces == null) {
                start = 0;
                pieces = List.of(new Piece(box(b), new int[0], new boolean[0]));
                rebuiltCells++;
            } else {
                extendedCells++;
            }
        }
        for (int at = start; at < crossing.size(); at++) {
            if (pieces.size() > MAX_PIECES) break;
            int k = crossing.get(at);
            List<Piece> next = new ArrayList<>();
            for (Piece piece : pieces) {
                Piece[] halves = split(piece, k);
                if (halves == null) {
                    boolean positive = side(piece, planes.get(k));
                    if (k >= real.size() || positive) {
                        next.add(with(piece, k, positive));
                    }
                } else {
                    next.add(halves[0]);
                    if (k >= real.size()) {
                        next.add(halves[1]);
                    }
                }
            }
            pieces = next;
            if (pieces.size() > MAX_PIECES) {
                break;
            }
        }
        if (start < cuts.size() || cuts.isEmpty()) remember(key, pieces);
        int kept = 0;
        List<Piece> selected = new ArrayList<>();
        for (Piece piece : pieces) {
            if (belongs(inside(piece))) {
                kept++;
                selected.add(piece);
            }
        }
        if (kept == 0) {
            return new Piece[0];
        }
        boolean cutByReal = crossing.stream().anyMatch(k -> k < real.size());
        if (kept == pieces.size() && !cutByReal) {
            Piece one = new Piece(box(b), new int[0], new boolean[0]);
            whole[cell] = true;
            return new Piece[]{one};
        }
        return selected.toArray(Piece[]::new);
    }

    private static boolean side(Piece piece, CutPlane plane) {
        double max = -Double.MAX_VALUE;
        for (Face f : piece.faces) {
            for (int i = 0; i < f.v.length; i += 3) {
                max = Math.max(max, plane.signedDistance(f.v[i], f.v[i + 1], f.v[i + 2]));
            }
        }
        return max > 0;
    }

    private static Piece with(Piece piece, int k, boolean positive) {
        int n = piece.ids.length;
        int[] ids = java.util.Arrays.copyOf(piece.ids, n + 1);
        boolean[] pos = java.util.Arrays.copyOf(piece.positive, n + 1);
        ids[n] = k;
        pos[n] = positive;
        return new Piece(piece.faces, ids, pos);
    }

    private Piece[] split(Piece piece, int k) {
        CutPlane plane = planes.get(k);
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (Face f : piece.faces) {
            for (int i = 0; i < f.v.length; i += 3) {
                double d = plane.signedDistance(f.v[i], f.v[i + 1], f.v[i + 2]);
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
        }
        if (min >= -MIN_SPLIT || max <= MIN_SPLIT) {
            return null;
        }
        List<Face> positive = new ArrayList<>(), negative = new ArrayList<>();
        List<double[]> onPlane = new ArrayList<>();
        for (Face f : piece.faces) {
            double[] in = clip(f.v, plane);
            double[] out = clip(f.v, plane.flipped());
            if (in.length >= 9 && area(in) > 1e-10) {
                positive.add(new Face(in, f.kind));
            }
            if (out.length >= 9 && area(out) > 1e-10) {
                negative.add(new Face(out, f.kind));
            }
            for (int i = 0; i < in.length; i += 3) {
                if (Math.abs(plane.signedDistance(in[i], in[i + 1], in[i + 2])) < 1e-9) {
                    boolean seen = false;
                    for (double[] q : onPlane) {
                        seen |= Math.abs(q[0] - in[i]) + Math.abs(q[1] - in[i + 1]) + Math.abs(q[2] - in[i + 2]) < 1e-8;
                    }
                    if (!seen) {
                        onPlane.add(new double[]{in[i], in[i + 1], in[i + 2]});
                    }
                }
            }
        }
        if (onPlane.size() < 3) {
            return null;
        }
        double[] section = ordered(onPlane, plane.nx(), plane.ny(), plane.nz());
        positive.add(new Face(section, 6 + k));
        negative.add(new Face(reversed(section), 6 + k));
        int n = piece.ids.length;
        int[] ids = java.util.Arrays.copyOf(piece.ids, n + 1);
        ids[n] = k;
        boolean[] a = java.util.Arrays.copyOf(piece.positive, n + 1), c = java.util.Arrays.copyOf(piece.positive, n + 1);
        a[n] = true;
        c[n] = false;
        return new Piece[]{new Piece(positive, ids, a), new Piece(negative, ids.clone(), c)};
    }

    private static double[] ordered(List<double[]> points, double nx, double ny, double nz) {
        double cx = 0, cy = 0, cz = 0;
        for (double[] p : points) {
            cx += p[0];
            cy += p[1];
            cz += p[2];
        }
        cx /= points.size();
        cy /= points.size();
        cz /= points.size();
        double hx = Math.abs(nx) < 0.9 ? 1 : 0, hy = hx == 1 ? 0 : 1;
        double tx = hy * nz, ty = -hx * nz, tz = hx * ny - hy * nx;
        double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
        tx /= tl;
        ty /= tl;
        tz /= tl;
        double bx = ny * tz - nz * ty, by = nz * tx - nx * tz, bz = nx * ty - ny * tx;
        final double[] angles = new double[points.size()];
        Integer[] order = new Integer[points.size()];
        for (int i = 0; i < order.length; i++) {
            double[] p = points.get(i);
            double dx = p[0] - cx, dy = p[1] - cy, dz = p[2] - cz;
            angles[i] = Math.atan2(dx * bx + dy * by + dz * bz, dx * tx + dy * ty + dz * tz);
            order[i] = i;
        }
        java.util.Arrays.sort(order, (p, q) -> Double.compare(angles[p], angles[q]));
        double[] out = new double[order.length * 3];
        for (int i = 0; i < order.length; i++) {
            System.arraycopy(points.get(order[i]), 0, out, i * 3, 3);
        }
        return out;
    }

    private static double[] reversed(double[] v) {
        int n = v.length / 3;
        double[] out = new double[v.length];
        for (int i = 0; i < n; i++) {
            System.arraycopy(v, (n - 1 - i) * 3, out, i * 3, 3);
        }
        return out;
    }

    private static List<Face> box(double[] b) {
        double x0 = b[0], y0 = b[1], z0 = b[2], x1 = b[3], y1 = b[4], z1 = b[5];
        List<Face> faces = new ArrayList<>(8);
        faces.add(new Face(new double[]{x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0}, 0));
        faces.add(new Face(new double[]{x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1}, 1));
        faces.add(new Face(new double[]{x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1}, 2));
        faces.add(new Face(new double[]{x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0}, 3));
        faces.add(new Face(new double[]{x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0}, 4));
        faces.add(new Face(new double[]{x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1}, 5));
        return faces;
    }

    private static double[] inside(Piece piece) {
        double[] c = new double[3];
        int count = 0;
        for (Face f : piece.faces) {
            for (int i = 0; i < f.v.length; i += 3) {
                c[0] += f.v[i];
                c[1] += f.v[i + 1];
                c[2] += f.v[i + 2];
                count++;
            }
        }
        for (int a = 0; a < 3; a++) {
            c[a] /= count;
        }
        return c;
    }

    private static double[] clip(double[] in, CutPlane plane) {
        int n = in.length / 3;
        double[] out = new double[(n + 2) * 3];
        int m = 0;
        for (int i = 0; i < n; i++) {
            int a = i * 3, b = (i + 1) % n * 3;
            double da = plane.signedDistance(in[a], in[a + 1], in[a + 2]);
            double db = plane.signedDistance(in[b], in[b + 1], in[b + 2]);
            if (da >= 0) {
                System.arraycopy(in, a, out, m, 3);
                m += 3;
            }
            if ((da >= 0) != (db >= 0)) {
                double t = da / (da - db);
                for (int k = 0; k < 3; k++) {
                    out[m + k] = in[a + k] + (in[b + k] - in[a + k]) * t;
                }
                m += 3;
            }
        }
        return java.util.Arrays.copyOf(out, m);
    }

    private static double area(double[] v) {
        int n = v.length / 3;
        double ax = 0, ay = 0, az = 0;
        for (int i = 0; i < n; i++) {
            int a = i * 3, b = (i + 1) % n * 3;
            ax += v[a + 1] * v[b + 2] - v[a + 2] * v[b + 1];
            ay += v[a + 2] * v[b] - v[a] * v[b + 2];
            az += v[a] * v[b + 1] - v[a + 1] * v[b];
        }
        return 0.5 * Math.sqrt(ax * ax + ay * ay + az * az);
    }

    private static List<double[]> subtract(List<double[]> polys, List<CutPlane> region) {
        List<double[]> out = new ArrayList<>();
        for (double[] poly : polys) {
            double[] rest = poly;
            for (CutPlane plane : region) {
                double[] outside = clip(rest, plane.flipped());
                if (outside.length >= 9 && area(outside) > MIN_AREA) {
                    out.add(outside);
                }
                rest = clip(rest, plane);
                if (rest.length < 9) {
                    break;
                }
            }
        }
        return out;
    }

    private List<CutPlane> region(Piece piece, int except) {
        List<CutPlane> out = new ArrayList<>(piece.ids.length);
        for (int i = 0; i < piece.ids.length; i++) {
            if (piece.ids[i] != except) {
                CutPlane p = planes.get(piece.ids[i]);
                out.add(piece.positive[i] ? p : p.flipped());
            }
        }
        return out;
    }

    public List<MeshPoly> clip(List<MeshPoly> source) {
        return clip(source, true);
    }

    public List<MeshPoly> clip(List<MeshPoly> source, boolean beyond) {
        List<MeshPoly> out = new ArrayList<>();
        for (double[] box : VoxelFragments.boxes(whole)) {
            out.addAll(MeshClipper.clip(source, VoxelFragments.planes(box)));
        }
        for (int cell = 0; cell < cells.length; cell++) {
            if (whole[cell]) {
                continue;
            }
            for (Piece piece : cells[cell]) {
                List<CutPlane> bounds = new ArrayList<>(VoxelFragments.planes(bounds(cell)));
                bounds.addAll(region(piece, -1));
                out.addAll(MeshClipper.clip(overlapping(source, bounds(cell)), bounds));
            }
        }
        if (beyond && extendsOutside(source)) {
            out.addAll(keptOutside(outsideOf(source)));
        }
        return out;
    }

    private static boolean extendsOutside(List<MeshPoly> source) {
        for (MeshPoly poly : source) {
            float[] v = poly.verts();
            for (int i = 0; i < v.length; i += MeshPoly.STRIDE) {
                for (int a = 0; a < 3; a++) {
                    if (v[i + a] < -1e-5f || v[i + a] > 1 + 1e-5f) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<MeshPoly> outsideOf(List<MeshPoly> source) {
        List<MeshPoly> out = new ArrayList<>();
        List<MeshPoly> rest = source;
        for (int axis = 0; axis < 3; axis++) {
            double[] n = new double[3];
            n[axis] = 1;
            for (int side = 0; side < 2; side++) {
                CutPlane beyond = side == 0 ? new CutPlane(n[0], n[1], n[2], 0) : new CutPlane(-n[0], -n[1], -n[2], -1);
                for (MeshPoly part : MeshClipper.clip(rest, List.of(beyond))) {
                    float[] v = part.verts();
                    float reach = 0;
                    for (int i = axis; i < v.length; i += MeshPoly.STRIDE) {
                        reach = Math.max(reach, side == 0 ? -v[i] : v[i] - 1);
                    }
                    if (reach > 1e-5f) {
                        out.add(part);
                    }
                }
                rest = MeshClipper.clip(rest, List.of(beyond.flipped()));
            }
        }
        return out;
    }

    private List<MeshPoly> keptOutside(List<MeshPoly> pieces) {
        List<CutPlane> divide = new ArrayList<>(transitions);
        divide.addAll(planes);
        List<MeshPoly> out = new ArrayList<>();
        for (MeshPoly part : splitAll(pieces, divide)) {
            if (keptAt(centre(part))) {
                out.add(part);
            }
        }
        return out;
    }

    private static double[] centre(MeshPoly poly) {
        double[] c = new double[3];
        float[] v = poly.verts();
        int n = poly.vertexCount();
        for (int i = 0; i < n; i++) {
            for (int a = 0; a < 3; a++) {
                c[a] += v[i * MeshPoly.STRIDE + a];
            }
        }
        for (int a = 0; a < 3; a++) {
            c[a] /= n;
        }
        return c;
    }

    private boolean keptAt(double[] p) {
        if (p[0] >= 0 && p[0] <= 1 && p[1] >= 0 && p[1] <= 1 && p[2] >= 0 && p[2] <= 1) {
            return belongs(p);
        }
        double[] q = {Math.clamp(p[0], 0, 1), Math.clamp(p[1], 0, 1), Math.clamp(p[2], 0, 1)};
        for (int pass = 0; pass < 3; pass++) {
            for (CutPlane plane : planes) {
                double sp = plane.signedDistance(p[0], p[1], p[2]), sq = plane.signedDistance(q[0], q[1], q[2]);
                if ((sp >= 0) != (sq >= 0)) {
                    double delta = (sp >= 0 ? 1e-3 : -1e-3) - sq;
                    q[0] = Math.clamp(q[0] - plane.nx() * delta, 0, 1);
                    q[1] = Math.clamp(q[1] - plane.ny() * delta, 0, 1);
                    q[2] = Math.clamp(q[2] - plane.nz() * delta, 0, 1);
                }
            }
        }
        return belongs(q);
    }

    private List<MeshPoly> outsideCaps(List<MeshPoly> source, float[] uv, Object sprite) {
        List<MeshPoly> out = new ArrayList<>();
        for (int k = 0; k < planes.size(); k++) {
            CutPlane plane = planes.get(k);
            List<MeshPoly> section = CapBuilder.face(source, plane, List.of(), uv, sprite);
            if (section.isEmpty()) {
                continue;
            }
            List<CutPlane> others = new ArrayList<>(transitions);
            for (int j = 0; j < planes.size(); j++) {
                if (j != k) {
                    others.add(planes.get(j));
                }
            }
            for (MeshPoly piece : splitAll(outsideOf(section), others)) {
                double[] c = centre(piece);
                double e = 1e-3;
                boolean inner = keptAt(new double[]{c[0] - plane.nx() * e, c[1] - plane.ny() * e, c[2] - plane.nz() * e});
                boolean outer = keptAt(new double[]{c[0] + plane.nx() * e, c[1] + plane.ny() * e, c[2] + plane.nz() * e});
                if (inner == outer) {
                    continue;
                }
                MeshPoly face = piece;
                double sign = 1;
                if (outer) {
                    float[] v = piece.verts();
                    int n = piece.vertexCount();
                    float[] flipped = new float[v.length];
                    for (int i = 0; i < n; i++) {
                        System.arraycopy(v, (n - 1 - i) * MeshPoly.STRIDE, flipped, i * MeshPoly.STRIDE, MeshPoly.STRIDE);
                    }
                    face = piece.withVerts(flipped);
                    sign = -1;
                }
                out.add(textured(null, new Boundary(null, sign * plane.nx(), sign * plane.ny(), sign * plane.nz(), ""),
                        uv, sprite, face));
            }
        }
        return out;
    }

    private static List<MeshPoly> splitAll(List<MeshPoly> pieces, List<CutPlane> divide) {
        List<MeshPoly> parts = pieces;
        for (CutPlane plane : divide) {
            List<MeshPoly> next = new ArrayList<>(parts.size());
            for (MeshPoly part : parts) {
                float[] v = part.verts();
                boolean above = false, below = false;
                for (int i = 0; i < v.length; i += MeshPoly.STRIDE) {
                    double d = plane.signedDistance(v[i], v[i + 1], v[i + 2]);
                    above |= d > 1e-6;
                    below |= d < -1e-6;
                }
                if (above && below) {
                    next.addAll(MeshClipper.clip(List.of(part), List.of(plane)));
                    next.addAll(MeshClipper.clip(List.of(part), List.of(plane.flipped())));
                } else {
                    next.add(part);
                }
            }
            parts = next;
        }
        return parts;
    }

    private static double[] bounds(int cell) {
        int x = cell / (N * N), y = cell / N % N, z = cell % N;
        return new double[]{x / (double) N, y / (double) N, z / (double) N, (x + 1) / (double) N, (y + 1) / (double) N, (z + 1) / (double) N};
    }

    private static List<MeshPoly> overlapping(List<MeshPoly> source, double[] b) {
        List<MeshPoly> out = new ArrayList<>();
        for (MeshPoly poly : source) {
            float[] v = poly.verts();
            boolean overlap = true;
            for (int axis = 0; axis < 3 && overlap; axis++) {
                float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
                for (int i = axis; i < v.length; i += MeshPoly.STRIDE) {
                    low = Math.min(low, v[i]);
                    high = Math.max(high, v[i]);
                }
                overlap = high >= b[axis] - 1e-6 && low <= b[axis + 3] + 1e-6;
            }
            if (overlap) {
                out.add(poly);
            }
        }
        return out;
    }

    public List<Boundary> boundary(boolean exterior) {
        List<Boundary> out = new ArrayList<>();
        for (int cell = 0; cell < cells.length; cell++) {
            for (Piece piece : cells[cell]) {
                for (Face f : piece.faces) {
                    List<double[]> visible = List.of(f.v);
                    String key;
                    double nx, ny, nz;
                    if (f.kind < 6) {
                        int axis = f.kind / 2, dir = f.kind % 2 == 1 ? 1 : -1;
                        int x = cell / (N * N), y = cell / N % N, z = cell % N;
                        int[] c = {x + (axis == 0 ? dir : 0), y + (axis == 1 ? dir : 0), z + (axis == 2 ? dir : 0)};
                        nx = axis == 0 ? dir : 0;
                        ny = axis == 1 ? dir : 0;
                        nz = axis == 2 ? dir : 0;
                        int at = axis == 0 ? x : axis == 1 ? y : z;
                        key = "a" + axis + ":" + (at + (dir > 0 ? 1 : 0)) + (dir > 0 ? "+" : "-");
                        if (c[0] < 0 || c[1] < 0 || c[2] < 0 || c[0] >= N || c[1] >= N || c[2] >= N) {
                            if (!exterior) {
                                continue;
                            }
                        } else {
                            int neighbour = (c[0] * N + c[1]) * N + c[2];
                            if (whole[neighbour] && whole[cell]) {
                                continue;
                            }
                            for (Piece other : cells[neighbour]) {
                                visible = subtract(visible, region(other, -1));
                                if (visible.isEmpty()) {
                                    break;
                                }
                            }
                        }
                    } else {
                        int k = f.kind - 6;
                        boolean positive = false;
                        for (int i = 0; i < piece.ids.length; i++) {
                            if (piece.ids[i] == k) {
                                positive = piece.positive[i];
                            }
                        }
                        CutPlane p = planes.get(k);
                        nx = positive ? p.nx() : -p.nx();
                        ny = positive ? p.ny() : -p.ny();
                        nz = positive ? p.nz() : -p.nz();
                        key = "p" + k + (positive ? "+" : "-");
                        Piece sibling = across(cells[cell], piece, k);
                        if (sibling != null) {
                            visible = subtract(visible, region(sibling, k));
                        }
                    }
                    for (double[] poly : visible) {
                        if (poly.length >= 9 && area(poly) > MIN_AREA) {
                            out.add(new Boundary(poly, nx, ny, nz, key));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static Piece across(Piece[] pieces, Piece piece, int k) {
        for (Piece other : pieces) {
            if (other == piece) {
                continue;
            }
            boolean opposite = false, same = true;
            for (int i = 0; i < other.ids.length; i++) {
                for (int j = 0; j < piece.ids.length; j++) {
                    if (other.ids[i] == piece.ids[j]) {
                        if (other.positive[i] != piece.positive[j]) {
                            if (other.ids[i] == k) {
                                opposite = true;
                            } else {
                                same = false;
                            }
                        }
                    }
                }
            }
            if (opposite && same) {
                return other;
            }
        }
        return null;
    }

    public List<MeshPoly> caps(List<MeshPoly> source, float[] uv, Object sprite) {
        return caps(source, uv, sprite, true);
    }

    public List<MeshPoly> caps(List<MeshPoly> source, float[] uv, Object sprite, boolean beyond) {
        boolean cube = source == Polytope.cube();
        Map<String, List<MeshPoly>> sections = new HashMap<>();
        List<MeshPoly> out = new ArrayList<>();
        for (Boundary face : boundary(false)) {
            double[] poly = face.verts();
            if (cube) {
                out.add(textured(poly, face, uv, sprite, null));
                continue;
            }
            double d = face.nx() * poly[0] + face.ny() * poly[1] + face.nz() * poly[2];
            List<MeshPoly> section = sections.computeIfAbsent(face.key(),
                    k -> CapBuilder.face(source, new CutPlane(face.nx(), face.ny(), face.nz(), d), List.of(), uv, sprite));
            if (section.isEmpty()) {
                continue;
            }
            List<CutPlane> edges = edgePlanes(poly, face);
            for (MeshPoly part : MeshClipper.clip(section, edges)) {
                out.add(textured(null, face, uv, sprite, part));
            }
        }
        if (beyond && !cube && extendsOutside(source)) {
            out.addAll(outsideCaps(source, uv, sprite));
        }
        return out;
    }

    private static List<CutPlane> edgePlanes(double[] poly, Boundary face) {
        List<CutPlane> out = new ArrayList<>();
        int n = poly.length / 3;
        for (int i = 0; i < n; i++) {
            int a = i * 3, b = (i + 1) % n * 3;
            double ex = poly[b] - poly[a], ey = poly[b + 1] - poly[a + 1], ez = poly[b + 2] - poly[a + 2];
            double mx = ey * face.nz() - ez * face.ny(), my = ez * face.nx() - ex * face.nz(), mz = ex * face.ny() - ey * face.nx();
            CutPlane p = CutPlane.of(mx, my, mz, mx * poly[a] + my * poly[a + 1] + mz * poly[a + 2]);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    private static MeshPoly textured(double[] poly, Boundary face, float[] uv, Object sprite, MeshPoly from) {
        float[] v;
        if (from != null) {
            v = from.verts().clone();
        } else {
            int n = poly.length / 3;
            v = new float[n * MeshPoly.STRIDE];
            for (int i = 0; i < n; i++) {
                v[i * MeshPoly.STRIDE] = (float) poly[i * 3];
                v[i * MeshPoly.STRIDE + 1] = (float) poly[i * 3 + 1];
                v[i * MeshPoly.STRIDE + 2] = (float) poly[i * 3 + 2];
            }
        }
        double ax = Math.abs(face.nx()), ay = Math.abs(face.ny()), az = Math.abs(face.nz());
        for (int i = 0; i < v.length; i += MeshPoly.STRIDE) {
            double x = v[i], y = v[i + 1], z = v[i + 2], a, b;
            if (ay >= ax && ay >= az) {
                a = x;
                b = z;
            } else if (ax >= az) {
                a = z;
                b = 1 - y;
            } else {
                a = x;
                b = 1 - y;
            }
            if (a < 0 || a > 1) {
                a -= Math.floor(a);
            }
            if (b < 0 || b > 1) {
                b -= Math.floor(b);
            }
            v[i + 3] = uv[0] + (uv[2] - uv[0]) * (float) a;
            v[i + 4] = uv[1] + (uv[3] - uv[1]) * (float) b;
        }
        return new MeshPoly(v, (float) face.nx(), (float) face.ny(), (float) face.nz(), -1, 0, 0xFFFFFFFF, sprite, -1, true);
    }

    public List<double[]> outline(List<double[]> shape) {
        boolean fullCube = false;
        for (double[] b : shape) {
            fullCube |= b[0] <= 1e-6 && b[1] <= 1e-6 && b[2] <= 1e-6 && b[3] >= 1 - 1e-6 && b[4] >= 1 - 1e-6 && b[5] >= 1 - 1e-6;
        }
        if (fullCube) {
            return BoundaryEdges.of(boundary(true));
        }
        List<Boundary> faces = new ArrayList<>();
        for (Boundary face : boundary(false)) {
            for (int i = 0; i < shape.size(); i++) {
                double[] box = shape.get(i);
                double[] clipped = face.verts();
                for (CutPlane p : VoxelFragments.planes(box)) {
                    clipped = clipped.length < 9 ? clipped : clip(clipped, p);
                }
                if (clipped.length >= 9 && area(clipped) > MIN_AREA) {
                    faces.add(new Boundary(clipped, face.nx(), face.ny(), face.nz(), face.key()));
                }
            }
        }
        for (int i = 0; i < shape.size(); i++) {
            double[] box = shape.get(i);
            double[][] sides = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
            List<Face> quad = box(box);
            for (int s = 0; s < 6; s++) {
                double[] inward = {-sides[s][0] * 1e-4, -sides[s][1] * 1e-4, -sides[s][2] * 1e-4};
                double[] q = quad.get(s).v().clone();
                for (int k = 0; k < q.length; k += 3) {
                    q[k] += inward[0];
                    q[k + 1] += inward[1];
                    q[k + 2] += inward[2];
                }
                for (int cell = 0; cell < cells.length; cell++) {
                    for (Piece piece : cells[cell]) {
                        double[] part = q;
                        List<CutPlane> bounds = new ArrayList<>(VoxelFragments.planes(bounds(cell)));
                        bounds.addAll(region(piece, -1));
                        for (CutPlane p : bounds) {
                            part = part.length < 9 ? part : clip(part, p);
                        }
                        if (part.length >= 9 && area(part) > MIN_AREA) {
                            for (int k = 0; k < part.length; k += 3) {
                                part[k] -= inward[0];
                                part[k + 1] -= inward[1];
                                part[k + 2] -= inward[2];
                            }
                            faces.add(new Boundary(part, sides[s][0], sides[s][1], sides[s][2], "b" + i + ":" + s));
                        }
                    }
                }
            }
        }
        return BoundaryEdges.of(faces);
    }

    private boolean belongs(double[] p) {
        for (CutPlane plane : real) {
            if (plane.signedDistance(p[0], p[1], p[2]) < -1e-7) {
                return false;
            }
        }
        int x = (int) Math.floor(p[0] * N), y = (int) Math.floor(p[1] * N), z = (int) Math.floor(p[2] * N);
        int nearest = (Math.clamp(x, 0, N - 1) * N + Math.clamp(y, 0, N - 1)) * N + Math.clamp(z, 0, N - 1);
        if (!blocked(p, CellGrid.centerOf(nearest))) {
            return mask[nearest];
        }
        double best = Double.MAX_VALUE;
        boolean owned = false;
        for (int a = Math.max(0, x - 2); a <= Math.min(N - 1, x + 2); a++) {
            for (int b = Math.max(0, y - 2); b <= Math.min(N - 1, y + 2); b++) {
                for (int c = Math.max(0, z - 2); c <= Math.min(N - 1, z + 2); c++) {
                    int i = (a * N + b) * N + c;
                    double[] q = CellGrid.centerOf(i);
                    double distance = (q[0] - p[0]) * (q[0] - p[0]) + (q[1] - p[1]) * (q[1] - p[1]) + (q[2] - p[2]) * (q[2] - p[2]);
                    if (distance >= best || blocked(p, q)) {
                        continue;
                    }
                    best = distance;
                    owned = mask[i];
                }
            }
        }
        return owned;
    }

    private boolean blocked(double[] a, double[] b) {
        for (int i = 0; i < patches.size(); i++) {
            Patch patch = patches.get(i);
            double[] box = patchBounds[i];
            if (!patch.through() && (Math.max(a[0], b[0]) < box[0] || Math.min(a[0], b[0]) > box[3]
                    || Math.max(a[1], b[1]) < box[1] || Math.min(a[1], b[1]) > box[4]
                    || Math.max(a[2], b[2]) < box[2] || Math.min(a[2], b[2]) > box[5])) {
                continue;
            }
            CutPlane p = patch.plane();
            double da = p.signedDistance(a[0], a[1], a[2]), db = p.signedDistance(b[0], b[1], b[2]);
            if ((da > 0) == (db > 0) || Math.abs(da - db) < 1e-12) {
                continue;
            }
            double t = da / (da - db);
            if (patch.through() || patch.containsProjected(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]), a[2] + t * (b[2] - a[2]), 1e-5)) {
                return true;
            }
        }
        return false;
    }
}
