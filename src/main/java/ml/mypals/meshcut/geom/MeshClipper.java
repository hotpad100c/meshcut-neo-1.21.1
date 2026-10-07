package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Sutherland–Hodgman clipping of {@link MeshPoly}s against a list of half-spaces. */
public final class MeshClipper {
    private static final int S = MeshPoly.STRIDE;
    private static final float MIN_AREA = 1e-8f;

    public static List<MeshPoly> clip(List<MeshPoly> polys, List<CutPlane> planes) {
        if (planes.isEmpty()) {
            return polys;
        }
        List<MeshPoly> out = new ArrayList<>(polys.size());
        for (MeshPoly poly : polys) {
            float[] verts = poly.verts();
            for (CutPlane plane : planes) {
                verts = clipOnce(verts, plane);
                if (verts.length < 3 * S) {
                    break;
                }
            }
            if (verts.length >= 3 * S && area(verts) > MIN_AREA) {
                out.add(poly.withVerts(verts));
            }
        }
        return out;
    }

    /**
     * What is left of the polygons after removing the convex region (the part satisfying every half-space). Each
     * polygon is cut by the region's planes in turn: the part outside the plane is kept, the part inside goes on to
     * the next plane, and what is inside them all is dropped.
     */
    public static List<MeshPoly> subtract(List<MeshPoly> polys, List<CutPlane> region) {
        if (region.isEmpty()) {
            return new ArrayList<>();
        }
        List<MeshPoly> out = new ArrayList<>();
        for (MeshPoly poly : polys) {
            if (separated(poly.verts(), region)) {
                // The polygon is clear of the region: it stays whole. (Cutting it plane by plane would still leave it in
                // pieces, split along the extensions of planes that never touch it, and every hole makes more of them.)
                out.add(poly);
                continue;
            }
            List<MeshPoly> remaining = List.of(poly);
            for (CutPlane plane : region) {
                // A coplanar face belongs to the inside branch only. Keeping it in both branches duplicates
                // it once per box plane, and repeated exclusions can grow the mesh exponentially.
                for (MeshPoly part : remaining) {
                    boolean outside = false;
                    for (int i = 0; i < part.verts().length; i += S) {
                        if (plane.signedDistance(part.verts()[i], part.verts()[i + 1], part.verts()[i + 2]) < -1e-7) {
                            outside = true;
                            break;
                        }
                    }
                    if (outside) out.addAll(clip(List.of(part), List.of(plane.flipped())));
                }
                remaining = clip(remaining, List.of(plane));
                if (remaining.isEmpty()) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Whether some plane of the region has the whole polygon strictly on its outer side: they share no volume. (A polygon
     * lying in a plane of the region is not clear of it: a hole that starts at a face of the block opens that face.)
     */
    static boolean separated(float[] verts, List<CutPlane> region) {
        for (CutPlane plane : region) {
            boolean clear = true;
            for (int i = 0; i < verts.length; i += S) {
                if (plane.signedDistance(verts[i], verts[i + 1], verts[i + 2]) >= -1e-7) {
                    clear = false;
                    break;
                }
            }
            if (clear) return true;
        }
        return false;
    }

    private static float[] clipOnce(float[] in, CutPlane plane) {
        int n = in.length / S;
        float[] out = new float[(2 * n + 2) * S]; // a concave polygon can cross the plane at every edge
        int m = 0;
        for (int i = 0; i < n; i++) {
            int a = i * S;
            int b = ((i + 1) % n) * S;
            double da = plane.signedDistance(in[a], in[a + 1], in[a + 2]);
            double db = plane.signedDistance(in[b], in[b + 1], in[b + 2]);
            boolean aIn = da >= 0;
            boolean bIn = db >= 0;
            if (aIn) {
                System.arraycopy(in, a, out, m * S, S);
                m++;
            }
            if (aIn != bIn) {
                float t = (float) (da / (da - db)); // da and db have opposite signs, so da - db != 0
                for (int k = 0; k < S; k++) {
                    out[m * S + k] = in[a + k] + (in[b + k] - in[a + k]) * t;
                }
                m++;
            }
        }
        return Arrays.copyOf(out, m * S);
    }

    /** Polygon area via the cross-product sum. */
    public static float area(float[] v) {
        int n = v.length / S;
        double ax = 0, ay = 0, az = 0;
        for (int i = 0; i < n; i++) {
            int a = i * S;
            int b = ((i + 1) % n) * S;
            ax += v[a + 1] * v[b + 2] - v[a + 2] * v[b + 1];
            ay += v[a + 2] * v[b] - v[a] * v[b + 2];
            az += v[a] * v[b + 1] - v[a + 1] * v[b];
        }
        return (float) (0.5 * Math.sqrt(ax * ax + ay * ay + az * az));
    }

    private MeshClipper() {}
}
