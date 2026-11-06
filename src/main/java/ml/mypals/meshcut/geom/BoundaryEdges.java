package ml.mypals.meshcut.geom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BoundaryEdges {
    private static final double SNAP = 1e4;
    private static final double ON_EDGE = 1e-5;

    static List<double[]> of(List<SmoothFragments.Boundary> faces) {
        Map<String, List<SmoothFragments.Boundary>> groups = new LinkedHashMap<>();
        for (SmoothFragments.Boundary face : faces) {
            groups.computeIfAbsent(face.key(), k -> new ArrayList<>()).add(face);
        }
        List<double[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<SmoothFragments.Boundary> group : groups.values()) {
            edges(group, out, seen);
        }
        return out;
    }

    private static void edges(List<SmoothFragments.Boundary> group, List<double[]> out, Set<String> seen) {
        List<double[]> points = new ArrayList<>();
        Map<Long, Integer> index = new HashMap<>();
        List<int[]> rings = new ArrayList<>();
        for (SmoothFragments.Boundary face : group) {
            double[] v = face.verts();
            int[] ring = new int[v.length / 3];
            for (int i = 0; i < ring.length; i++) {
                long key = key(v[i * 3], v[i * 3 + 1], v[i * 3 + 2]);
                Integer at = index.get(key);
                if (at == null) {
                    at = points.size();
                    index.put(key, at);
                    points.add(new double[]{v[i * 3], v[i * 3 + 1], v[i * 3 + 2]});
                }
                ring[i] = at;
            }
            rings.add(ring);
        }
        Set<Long> directed = new HashSet<>();
        List<int[]> pieces = new ArrayList<>();
        for (int[] ring : rings) {
            for (int i = 0; i < ring.length; i++) {
                int a = ring[i], b = ring[(i + 1) % ring.length];
                if (a == b) {
                    continue;
                }
                List<double[]> cuts = new ArrayList<>();
                double[] p = points.get(a), q = points.get(b);
                double dx = q[0] - p[0], dy = q[1] - p[1], dz = q[2] - p[2];
                double length2 = dx * dx + dy * dy + dz * dz;
                for (int w = 0; w < points.size(); w++) {
                    if (w == a || w == b) {
                        continue;
                    }
                    double[] r = points.get(w);
                    double t = ((r[0] - p[0]) * dx + (r[1] - p[1]) * dy + (r[2] - p[2]) * dz) / length2;
                    if (t <= 0 || t >= 1) {
                        continue;
                    }
                    double ex = p[0] + dx * t - r[0], ey = p[1] + dy * t - r[1], ez = p[2] + dz * t - r[2];
                    if (ex * ex + ey * ey + ez * ez < ON_EDGE * ON_EDGE) {
                        cuts.add(new double[]{t, w});
                    }
                }
                cuts.sort((m, n) -> Double.compare(m[0], n[0]));
                int from = a;
                for (double[] cut : cuts) {
                    pieces.add(new int[]{from, (int) cut[1]});
                    from = (int) cut[1];
                }
                pieces.add(new int[]{from, b});
            }
        }
        for (int[] e : pieces) {
            directed.add((long) e[0] << 32 | e[1]);
        }
        for (int[] e : pieces) {
            if (directed.contains((long) e[1] << 32 | e[0])) {
                continue;
            }
            double[] p = points.get(e[0]), q = points.get(e[1]);
            String id = key(p[0], p[1], p[2]) < key(q[0], q[1], q[2])
                    ? key(p[0], p[1], p[2]) + ":" + key(q[0], q[1], q[2])
                    : key(q[0], q[1], q[2]) + ":" + key(p[0], p[1], p[2]);
            if (seen.add(id)) {
                out.add(new double[]{p[0], p[1], p[2], q[0], q[1], q[2]});
            }
        }
    }

    private static long key(double x, double y, double z) {
        return (Math.round(x * SNAP) + (1 << 20)) << 42 | (Math.round(y * SNAP) + (1 << 20)) << 21 | (Math.round(z * SNAP) + (1 << 20));
    }

    private BoundaryEdges() {}
}
