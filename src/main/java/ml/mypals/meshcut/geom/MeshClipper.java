package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    public static List<MeshPoly> subtract(List<MeshPoly> polys, List<CutPlane> region) {
        if (region.isEmpty()) {
            return new ArrayList<>();
        }
        List<MeshPoly> out = new ArrayList<>();
        for (MeshPoly poly : polys) {
            if (HoleReducer.apart(region, poly.verts()) || separated(poly.verts(), region)) {
                out.add(poly);
                continue;
            }
            List<MeshPoly> remaining = List.of(poly);
            for (CutPlane plane : region) {
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
        float[] out = new float[(2 * n + 2) * S];
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
                float t = (float) (da / (da - db));
                for (int k = 0; k < S; k++) {
                    out[m * S + k] = in[a + k] + (in[b + k] - in[a + k]) * t;
                }
                m++;
            }
        }
        return Arrays.copyOf(out, m * S);
    }

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
