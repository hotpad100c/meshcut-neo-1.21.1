package ml.mypals.meshcut.geom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PolyMerge {
    private static final int S = MeshPoly.STRIDE;
    private static final double QUANT = 1e4;
    private static final double COLLINEAR = 1e-6;

    private record Group(int nx, int ny, int nz, long offset, int cull, int tint, int light, int argb, Object sprite, boolean shade) {}

    public static List<MeshPoly> merge(List<MeshPoly> polys) {
        if (polys.size() < 2) {
            return polys;
        }
        Map<Group, List<MeshPoly>> groups = new HashMap<>();
        for (MeshPoly p : polys) {
            groups.computeIfAbsent(groupOf(p), k -> new ArrayList<>()).add(p);
        }
        List<MeshPoly> out = new ArrayList<>(polys.size());
        for (List<MeshPoly> group : groups.values()) {
            out.addAll(group.size() < 2 ? group : mergeGroup(group));
        }
        return out;
    }

    private static Group groupOf(MeshPoly p) {
        float[] v = p.verts();
        double offset = p.nx() * v[0] + p.ny() * v[1] + p.nz() * v[2];
        return new Group(Math.round(p.nx() * 1000), Math.round(p.ny() * 1000), Math.round(p.nz() * 1000),
                Math.round(offset * 1000), p.cullFace(), p.tintIndex(), p.lightEmission(), p.argb(), p.sprite(), p.shade());
    }

    private static List<MeshPoly> mergeGroup(List<MeshPoly> group) {
        List<float[]> verts = new ArrayList<>(group.size());
        for (MeshPoly p : group) {
            verts.add(p.verts());
        }
        MeshPoly template = group.get(0);
        boolean merged = true;
        for (int pass = 0; merged && pass < 12; pass++) {
            merged = false;
            Map<Long, int[]> edges = new HashMap<>();
            for (int i = 0; i < verts.size(); i++) {
                float[] v = verts.get(i);
                if (v == null) continue;
                int n = v.length / S;
                for (int e = 0; e < n; e++) {
                    edges.put(edgeKey(v, e, (e + 1) % n), new int[]{i, e});
                }
            }
            boolean[] changedThisPass = new boolean[verts.size()];
            for (int i = 0; i < verts.size(); i++) {
                float[] p = verts.get(i);
                if (p == null || changedThisPass[i]) continue;
                int n = p.length / S;
                for (int e = 0; e < n; e++) {
                    int[] other = edges.get(edgeKey(p, (e + 1) % n, e));
                    if (other == null || other[0] == i || changedThisPass[other[0]]) continue;
                    float[] q = verts.get(other[0]);
                    if (q == null) continue;
                    float[] joined = join(p, e, q, other[1], template);
                    if (joined != null && quads(joined) <= quads(p) + quads(q)) {
                        verts.set(i, joined);
                        verts.set(other[0], null);
                        changedThisPass[i] = true;
                        changedThisPass[other[0]] = true;
                        merged = true;
                        break;
                    }
                }
            }
        }
        List<MeshPoly> out = new ArrayList<>();
        Map<float[], MeshPoly> original = new java.util.IdentityHashMap<>();
        for (MeshPoly p : group) {
            original.put(p.verts(), p);
        }
        for (float[] v : verts) {
            if (v == null) continue;
            MeshPoly same = original.get(v);
            out.add(same != null ? same : template.withVerts(v));
        }
        return out;
    }

    private static int quads(float[] v) {
        int n = v.length / S;
        return n == 4 ? 1 : n - 2;
    }

    private static float[] join(float[] p, int e, float[] q, int f, MeshPoly template) {
        int pn = p.length / S, qn = q.length / S;
        if (dist(vertex(p, (e + 1) % pn), vertex(q, f)) > 1e-3 || dist(vertex(p, e), vertex(q, (f + 1) % qn)) > 1e-3) {
            return null;
        }
        if (!sameMapping(p, q)) {
            return null;
        }
        List<float[]> ring = new ArrayList<>(pn + qn);
        for (int k = 1; k <= pn; k++) {
            ring.add(vertex(p, (e + k) % pn));
        }
        for (int k = 2; k < qn; k++) {
            ring.add(vertex(q, (f + k) % qn));
        }
        boolean changed = true;
        while (changed && ring.size() > 3) {
            changed = false;
            for (int i = 0; i < ring.size(); i++) {
                float[] a = ring.get((i + ring.size() - 1) % ring.size()), b = ring.get(i), c = ring.get((i + 1) % ring.size());
                if (length(cross(a, b, c, template)) < COLLINEAR * Math.max(1e-3, dist(a, c))) {
                    ring.remove(i);
                    changed = true;
                    break;
                }
            }
        }
        if (ring.size() < 3) {
            return null;
        }
        for (int i = 0; i < ring.size(); i++) {
            float[] a = ring.get(i), b = ring.get((i + 1) % ring.size()), c = ring.get((i + 2) % ring.size());
            double[] x = cross(a, b, c, template);
            double side = x[0] * template.nx() + x[1] * template.ny() + x[2] * template.nz();
            if (side < -1e-7) {
                return null;
            }
        }
        float[] out = new float[ring.size() * S];
        for (int i = 0; i < ring.size(); i++) {
            System.arraycopy(ring.get(i), 0, out, i * S, S);
        }
        return out;
    }

    private static boolean sameMapping(float[] p, float[] q) {
        int n = p.length / S;
        int ia = 0, ib = 1, ic = 2;
        double best = -1;
        for (int i = 0; i + 2 < n; i++) {
            double[] c = cross(vertex(p, 0), vertex(p, i + 1), vertex(p, i + 2), null);
            double area = length(c);
            if (area > best) { best = area; ia = 0; ib = i + 1; ic = i + 2; }
        }
        if (best < 1e-9) return false;
        float[] a = vertex(p, ia), b = vertex(p, ib), c = vertex(p, ic);
        double v0x = b[0] - a[0], v0y = b[1] - a[1], v0z = b[2] - a[2];
        double v1x = c[0] - a[0], v1y = c[1] - a[1], v1z = c[2] - a[2];
        double d00 = v0x * v0x + v0y * v0y + v0z * v0z, d01 = v0x * v1x + v0y * v1y + v0z * v1z, d11 = v1x * v1x + v1y * v1y + v1z * v1z;
        double det = d00 * d11 - d01 * d01;
        if (Math.abs(det) < 1e-12) return false;
        for (int i = 0; i < q.length / S; i++) {
            double wx = q[i * S] - a[0], wy = q[i * S + 1] - a[1], wz = q[i * S + 2] - a[2];
            double d20 = wx * v0x + wy * v0y + wz * v0z, d21 = wx * v1x + wy * v1y + wz * v1z;
            double sc = (d11 * d20 - d01 * d21) / det, tc = (d00 * d21 - d01 * d20) / det;
            double u = a[3] + sc * (b[3] - a[3]) + tc * (c[3] - a[3]);
            double v = a[4] + sc * (b[4] - a[4]) + tc * (c[4] - a[4]);
            if (Math.abs(u - q[i * S + 3]) > 2e-4 || Math.abs(v - q[i * S + 4]) > 2e-4) return false;
        }
        return true;
    }

    private static float[] vertex(float[] v, int i) {
        float[] out = new float[S];
        System.arraycopy(v, i * S, out, 0, S);
        return out;
    }

    private static double[] cross(float[] a, float[] b, float[] c, MeshPoly t) {
        double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        double vx = c[0] - b[0], vy = c[1] - b[1], vz = c[2] - b[2];
        return new double[]{uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx};
    }

    private static double length(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static double dist(float[] a, float[] c) {
        double x = c[0] - a[0], y = c[1] - a[1], z = c[2] - a[2];
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static long edgeKey(float[] v, int from, int to) {
        return point(v, from) * 0x9E3779B97F4A7C15L + point(v, to);
    }

    private static long point(float[] v, int i) {
        long x = Math.round(v[i * S] * QUANT), y = Math.round(v[i * S + 1] * QUANT), z = Math.round(v[i * S + 2] * QUANT);
        return (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L);
    }

    private PolyMerge() {}
}
