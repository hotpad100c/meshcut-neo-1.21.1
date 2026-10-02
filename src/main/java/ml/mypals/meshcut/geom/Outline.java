package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The edges of a cut block, for its selection outline: each piece is a convex shape (the block's boxes clipped by the
 * piece's half-spaces), with the parts that were cut out of the block taken away. Pure geometry.
 */
public final class Outline {
    private static final double EPS = 1e-5;
    private static final double BIG = 4;

    /**
     * @param pieces the pieces, each a list of half-spaces
     * @param holes  convex parts cut out of the block, each a list of half-spaces (strictly inside is gone)
     * @param boxes  what the block is made of, {minX, minY, minZ, maxX, maxY, maxZ} in block space
     * @return line segments {x0, y0, z0, x1, y1, z1}
     */
    public static List<double[]> edges(List<List<CutPlane>> pieces, List<List<CutPlane>> holes, List<double[]> boxes) {
        List<double[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<CutPlane> piece : pieces) {
            for (double[] box : boxes) {
                List<CutPlane> shape = new ArrayList<>(piece);
                shape.addAll(boxPlanes(box));
                // the piece's own edges, without what lies inside a cut-out part
                for (double[] e : convexEdges(shape)) {
                    for (double[] kept : outsideAll(e, holes)) {
                        add(out, seen, kept);
                    }
                }
                // the edges of the notches the cut-out parts leave in it
                for (List<CutPlane> hole : holes) {
                    List<CutPlane> notch = new ArrayList<>(shape);
                    notch.addAll(hole);
                    for (double[] e : convexEdges(notch)) {
                        if (onAny(e, hole)) {
                            List<List<CutPlane>> others = new ArrayList<>(holes);
                            others.remove(hole);
                            for (double[] kept : outsideAll(e, others)) {
                                add(out, seen, kept);
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static List<CutPlane> boxPlanes(double[] b) {
        return List.of(new CutPlane(-1, 0, 0, -b[0]), new CutPlane(1, 0, 0, b[3]), new CutPlane(0, -1, 0, -b[1]),
                new CutPlane(0, 1, 0, b[4]), new CutPlane(0, 0, -1, -b[2]), new CutPlane(0, 0, 1, b[5]));
    }

    /** The edges of the convex shape the half-spaces make: the outline of each of its faces. */
    private static List<double[]> convexEdges(List<CutPlane> planes) {
        List<double[]> edges = new ArrayList<>();
        for (int i = 0; i < planes.size(); i++) {
            double[] face = square(planes.get(i));
            for (int j = 0; j < planes.size() && face.length >= 9; j++) {
                if (j != i) {
                    face = clip(face, planes.get(j));
                }
            }
            int n = face.length / 3;
            if (n < 3) {
                continue;
            }
            for (int k = 0; k < n; k++) {
                int a = k * 3, b = ((k + 1) % n) * 3;
                double[] e = {face[a], face[a + 1], face[a + 2], face[b], face[b + 1], face[b + 2]};
                double dx = e[3] - e[0], dy = e[4] - e[1], dz = e[5] - e[2];
                if (dx * dx + dy * dy + dz * dz > 1e-8) {
                    edges.add(e);
                }
            }
        }
        return edges;
    }

    /** A big square lying in the plane. */
    private static double[] square(CutPlane p) {
        double nx = p.nx(), ny = p.ny(), nz = p.nz();
        double hx = Math.abs(nx) < 0.9 ? 1 : 0, hy = hx == 1 ? 0 : 1;
        double tx = hy * nz, ty = -hx * nz, tz = hx * ny - hy * nx;
        double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
        tx /= tl;
        ty /= tl;
        tz /= tl;
        double bx = ny * tz - nz * ty, by = nz * tx - nx * tz, bz = nx * ty - ny * tx;
        double cx = nx * p.d(), cy = ny * p.d(), cz = nz * p.d();
        double[] out = new double[12];
        double[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < 4; i++) {
            double a = corners[i][0] * BIG, b = corners[i][1] * BIG;
            out[i * 3] = cx + tx * a + bx * b;
            out[i * 3 + 1] = cy + ty * a + by * b;
            out[i * 3 + 2] = cz + tz * a + bz * b;
        }
        return out;
    }

    /** Sutherland-Hodgman: the part of a convex polygon on the kept side of the plane. */
    private static double[] clip(double[] in, CutPlane p) {
        int n = in.length / 3;
        double[] out = new double[(2 * n + 2) * 3];
        int m = 0;
        for (int i = 0; i < n; i++) {
            int a = i * 3, b = ((i + 1) % n) * 3;
            double da = p.signedDistance(in[a], in[a + 1], in[a + 2]);
            double db = p.signedDistance(in[b], in[b + 1], in[b + 2]);
            if (da >= -EPS) {
                System.arraycopy(in, a, out, m * 3, 3);
                m++;
            }
            if ((da >= -EPS) != (db >= -EPS)) {
                double t = da / (da - db);
                for (int k = 0; k < 3; k++) {
                    out[m * 3 + k] = in[a + k] + (in[b + k] - in[a + k]) * t;
                }
                m++;
            }
        }
        return java.util.Arrays.copyOf(out, m * 3);
    }

    /** The parts of the segment that are not strictly inside any of the regions. */
    private static List<double[]> outsideAll(double[] e, List<List<CutPlane>> regions) {
        List<double[]> parts = new ArrayList<>(List.of(e));
        for (List<CutPlane> region : regions) {
            List<double[]> next = new ArrayList<>();
            for (double[] s : parts) {
                // the stretch [lo, hi] of the segment that is inside the region
                double lo = 0, hi = 1;
                for (CutPlane p : region) {
                    double d0 = p.signedDistance(s[0], s[1], s[2]), d1 = p.signedDistance(s[3], s[4], s[5]);
                    if (Math.abs(d1 - d0) < 1e-12) {
                        if (d0 <= EPS) {
                            lo = 1;
                            hi = 0;
                        }
                        continue;
                    }
                    double t = d0 / (d0 - d1); // where it crosses the plane
                    if (d1 > d0) {
                        lo = Math.max(lo, t + EPS);
                    } else {
                        hi = Math.min(hi, t - EPS);
                    }
                }
                if (hi <= lo) {
                    next.add(s);
                    continue;
                }
                if (lo > EPS) {
                    next.add(part(s, 0, lo));
                }
                if (hi < 1 - EPS) {
                    next.add(part(s, hi, 1));
                }
            }
            parts = next;
        }
        return parts;
    }

    private static double[] part(double[] s, double t0, double t1) {
        return new double[]{s[0] + (s[3] - s[0]) * t0, s[1] + (s[4] - s[1]) * t0, s[2] + (s[5] - s[2]) * t0,
                s[0] + (s[3] - s[0]) * t1, s[1] + (s[4] - s[1]) * t1, s[2] + (s[5] - s[2]) * t1};
    }

    /** Whether both ends of the segment lie on one of the planes. */
    private static boolean onAny(double[] e, List<CutPlane> planes) {
        for (CutPlane p : planes) {
            if (Math.abs(p.signedDistance(e[0], e[1], e[2])) < 1e-4 && Math.abs(p.signedDistance(e[3], e[4], e[5])) < 1e-4) {
                return true;
            }
        }
        return false;
    }

    private static void add(List<double[]> out, Set<String> seen, double[] e) {
        String a = key(e[0], e[1], e[2]), b = key(e[3], e[4], e[5]);
        if (a.equals(b)) {
            return;
        }
        if (seen.add(a.compareTo(b) < 0 ? a + b : b + a)) {
            out.add(e);
        }
    }

    private static String key(double x, double y, double z) {
        return Math.round(x * 1e4) + "," + Math.round(y * 1e4) + "," + Math.round(z * 1e4) + ";";
    }

    private Outline() {}
}
