package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CapBuilder {
    private static final double ON_PLANE = 1e-4;
    private static final double MIN_EDGE = 1e-5;
    private static final double QUANT = 1e4;
    private static final int S = MeshPoly.STRIDE;

    private record Edge(long from, long to) {}

    public static List<MeshPoly> build(List<MeshPoly> original, List<CutPlane> planes, float[] uv) {
        return build(original, planes, uv, null);
    }

    public static List<MeshPoly> build(List<MeshPoly> original, List<CutPlane> planes, float[] uv, Object sprite) {
        List<MeshPoly> caps = new ArrayList<>();
        for (int i = 0; i < planes.size(); i++) {
            CutPlane plane = planes.get(i);
            if (!removesSomething(original, plane)) {
                continue;
            }
            List<CutPlane> others = new ArrayList<>(planes);
            others.remove(i);
            caps.addAll(MeshClipper.clip(capFor(original, plane, uv, sprite), others));
        }
        return caps;
    }

    public static List<MeshPoly> face(List<MeshPoly> original, CutPlane plane, List<CutPlane> others, float[] uv, Object sprite) {
        if (!removesSomething(original, plane)) {
            return List.of();
        }
        return MeshClipper.clip(capFor(original, plane, uv, sprite), others);
    }

    private static boolean removesSomething(List<MeshPoly> original, CutPlane plane) {
        for (MeshPoly poly : original) {
            float[] v = poly.verts();
            for (int o = 0; o < v.length; o += S) {
                if (plane.signedDistance(v[o], v[o + 1], v[o + 2]) < -ON_PLANE) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<MeshPoly> capFor(List<MeshPoly> original, CutPlane plane, float[] uv, Object sprite) {
        List<MeshPoly> clipped = MeshClipper.clip(original, List.of(plane));
        Map<Long, double[]> points = new HashMap<>();
        Set<Edge> edges = new HashSet<>();

        for (MeshPoly poly : clipped) {
            float[] v = poly.verts();
            int n = poly.vertexCount();
            boolean coplanar = true;
            for (int k = 0; k < n && coplanar; k++) {
                coplanar = Math.abs(plane.signedDistance(v[k * S], v[k * S + 1], v[k * S + 2])) < ON_PLANE;
            }
            if (coplanar) {
                continue;
            }
            for (int k = 0; k < n; k++) {
                int a = k * S;
                int b = ((k + 1) % n) * S;
                if (Math.abs(plane.signedDistance(v[a], v[a + 1], v[a + 2])) >= ON_PLANE
                        || Math.abs(plane.signedDistance(v[b], v[b + 1], v[b + 2])) >= ON_PLANE) {
                    continue;
                }
                double dx = v[b] - v[a], dy = v[b + 1] - v[a + 1], dz = v[b + 2] - v[a + 2];
                if (dx * dx + dy * dy + dz * dz < MIN_EDGE * MIN_EDGE) {
                    continue;
                }
                long from = key(v[b], v[b + 1], v[b + 2]);
                long to = key(v[a], v[a + 1], v[a + 2]);
                points.putIfAbsent(from, new double[]{v[b], v[b + 1], v[b + 2]});
                points.putIfAbsent(to, new double[]{v[a], v[a + 1], v[a + 2]});
                if (from == to) {
                    continue;
                }
                if (!edges.remove(new Edge(to, from))) {
                    edges.add(new Edge(from, to));
                }
            }
        }
        if (edges.isEmpty()) {
            return List.of();
        }

        double nx = plane.nx(), ny = plane.ny(), nz = plane.nz();
        double hx = Math.abs(nx) < 0.9 ? 1 : 0, hy = hx == 1 ? 0 : 1;
        double tx = hy * nz, ty = -hx * nz, tz = hx * ny - hy * nx;
        double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
        tx /= tl;
        ty /= tl;
        tz /= tl;
        double bx = ny * tz - nz * ty, by = nz * tx - nx * tz, bz = nx * ty - ny * tx;

        List<List<double[]>> loops = stitch(edges, points);
        double minA = Double.MAX_VALUE, minB = Double.MAX_VALUE, maxA = -Double.MAX_VALUE, maxB = -Double.MAX_VALUE;
        for (List<double[]> loop : loops) {
            for (double[] p : loop) {
                double a = p[0] * tx + p[1] * ty + p[2] * tz;
                double b = p[0] * bx + p[1] * by + p[2] * bz;
                minA = Math.min(minA, a);
                maxA = Math.max(maxA, a);
                minB = Math.min(minB, b);
                maxB = Math.max(maxB, b);
            }
        }
        double scale = 1.0 / Math.max(1.0, Math.max(maxA - minA, maxB - minB));

        List<MeshPoly> out = new ArrayList<>();
        for (List<double[]> loop : loops) {
            int n = loop.size();
            double[] xs = new double[n], ys = new double[n];
            for (int k = 0; k < n; k++) {
                double[] p = loop.get(k);
                xs[k] = p[0] * tx + p[1] * ty + p[2] * tz;
                ys[k] = p[0] * bx + p[1] * by + p[2] * bz;
            }
            if (signedArea(xs, ys) <= 1e-8) {
                continue;
            }
            for (int[] tri : earClip(xs, ys)) {
                float[] verts = new float[3 * S];
                for (int k = 0; k < 3; k++) {
                    double[] p = loop.get(tri[k]);
                    verts[k * S] = (float) p[0];
                    verts[k * S + 1] = (float) p[1];
                    verts[k * S + 2] = (float) p[2];
                    verts[k * S + 3] = uv[0] + (uv[2] - uv[0]) * (float) ((xs[tri[k]] - minA) * scale);
                    verts[k * S + 4] = uv[1] + (uv[3] - uv[1]) * (float) ((ys[tri[k]] - minB) * scale);
                }
                out.add(new MeshPoly(verts, (float) nx, (float) ny, (float) nz, -1, 0, 0xFFFFFFFF, sprite, -1, true));
            }
        }
        return out;
    }

    private static List<List<double[]>> stitch(Set<Edge> edges, Map<Long, double[]> points) {
        Map<Long, List<Long>> next = new HashMap<>();
        for (Edge e : edges) {
            next.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
        }
        List<List<double[]>> loops = new ArrayList<>();
        while (!next.isEmpty()) {
            long start = next.keySet().iterator().next();
            List<double[]> loop = new ArrayList<>();
            long cur = start;
            boolean closed = false;
            while (true) {
                loop.add(points.get(cur));
                List<Long> outs = next.get(cur);
                if (outs == null) {
                    break;
                }
                long to = outs.remove(outs.size() - 1);
                if (outs.isEmpty()) {
                    next.remove(cur);
                }
                if (to == start) {
                    closed = true;
                    break;
                }
                cur = to;
            }
            if (closed && loop.size() >= 3) {
                loops.add(loop);
            }
        }
        return loops;
    }

    private static double signedArea(double[] xs, double[] ys) {
        double a = 0;
        for (int i = 0, n = xs.length; i < n; i++) {
            int j = (i + 1) % n;
            a += xs[i] * ys[j] - xs[j] * ys[i];
        }
        return a / 2;
    }

    private static List<int[]> earClip(double[] xs, double[] ys) {
        int n = xs.length;
        List<Integer> idx = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            idx.add(i);
        }
        List<int[]> tris = new ArrayList<>();
        int guard = 0;
        while (idx.size() > 3 && guard++ < n * n) {
            boolean clipped = false;
            for (int k = 0; k < idx.size(); k++) {
                int ia = idx.get((k + idx.size() - 1) % idx.size()), ib = idx.get(k), ic = idx.get((k + 1) % idx.size());
                double cross = (xs[ib] - xs[ia]) * (ys[ic] - ys[ia]) - (ys[ib] - ys[ia]) * (xs[ic] - xs[ia]);
                if (cross < 1e-10) {
                    if (Math.abs(cross) < 1e-10) {
                        idx.remove(k);
                        clipped = true;
                        break;
                    }
                    continue;
                }
                boolean ear = true;
                for (int other : idx) {
                    if (other != ia && other != ib && other != ic
                            && inTriangle(xs[other], ys[other], xs[ia], ys[ia], xs[ib], ys[ib], xs[ic], ys[ic])) {
                        ear = false;
                        break;
                    }
                }
                if (ear) {
                    tris.add(new int[]{ia, ib, ic});
                    idx.remove(k);
                    clipped = true;
                    break;
                }
            }
            if (!clipped) {
                break;
            }
        }
        if (idx.size() == 3) {
            tris.add(new int[]{idx.get(0), idx.get(1), idx.get(2)});
        }
        return tris;
    }

    private static boolean inTriangle(double px, double py, double ax, double ay, double bx, double by, double cx, double cy) {
        double d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by);
        double d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy);
        double d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay);
        boolean neg = d1 < -1e-12 || d2 < -1e-12 || d3 < -1e-12;
        boolean pos = d1 > 1e-12 || d2 > 1e-12 || d3 > 1e-12;
        return !(neg && pos);
    }

    private static long key(double x, double y, double z) {
        long qx = Math.round(x * QUANT) + (1 << 20);
        long qy = Math.round(y * QUANT) + (1 << 20);
        long qz = Math.round(z * QUANT) + (1 << 20);
        return (qx << 42) | (qy << 21) | qz;
    }

    private CapBuilder() {}
}
