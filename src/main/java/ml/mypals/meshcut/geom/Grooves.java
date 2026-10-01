package ml.mypals.meshcut.geom;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a scratch that stops midway reaches the outside of its block it leaves a mark: a narrow strip lying on that
 * face of the block. (A cut that goes right through is drawn as a slit instead.)
 */
public final class Grooves {
    private static final double HALF_WIDTH = 0.035;
    /** How far the strip stands off the face, to keep it from fighting the face itself. */
    private static final float LIFT = 0.003f;
    /** {@code Direction} ordinals of the block faces, indexed by {@code axis * 2 + (value == 1 ? 0 : 1)}. */
    private static final int[] DIRECTION = {5, 4, 1, 0, 3, 2}; // +x east, -x west, +y up, -y down, +z south, -z north

    /**
     * @param uv       {u0, v0, u1, v1} of the texture the strip is coloured from (its centre is sampled)
     * @param argb     colour multiplier
     * @param emission block light the strip gives off (0..15)
     */
    public static List<MeshPoly> of(Patch patch, float[] uv, Object sprite, int argb, int emission) {
        List<MeshPoly> out = new ArrayList<>();
        if (patch.through()) {
            return out;
        }
        float u = (uv[0] + uv[2]) / 2, v = (uv[1] + uv[3]) / 2;
        float[] poly = patch.polygon();
        int n = poly.length / 3;
        for (int axis = 0; axis < 3; axis++) {
            for (int value = 0; value <= 1; value++) {
                double[][] ends = crossing(poly, n, axis, value);
                if (ends == null) {
                    continue;
                }
                double nx = axis == 0 ? (value == 1 ? 1 : -1) : 0;
                double ny = axis == 1 ? (value == 1 ? 1 : -1) : 0;
                double nz = axis == 2 ? (value == 1 ? 1 : -1) : 0;
                double ax = ends[1][0] - ends[0][0], ay = ends[1][1] - ends[0][1], az = ends[1][2] - ends[0][2];
                double len = Math.sqrt(ax * ax + ay * ay + az * az);
                if (len < 0.02) {
                    continue;
                }
                ax /= len;
                ay /= len;
                az /= len;
                // side = normal x along
                double sx = ny * az - nz * ay, sy = nz * ax - nx * az, sz = nx * ay - ny * ax;
                double[][] corners = new double[4][];
                double[] p0 = ends[0], p1 = ends[1];
                corners[0] = new double[]{p0[0] - sx * HALF_WIDTH, p0[1] - sy * HALF_WIDTH, p0[2] - sz * HALF_WIDTH};
                corners[1] = new double[]{p0[0] + sx * HALF_WIDTH, p0[1] + sy * HALF_WIDTH, p0[2] + sz * HALF_WIDTH};
                corners[2] = new double[]{p1[0] + sx * HALF_WIDTH, p1[1] + sy * HALF_WIDTH, p1[2] + sz * HALF_WIDTH};
                corners[3] = new double[]{p1[0] - sx * HALF_WIDTH, p1[1] - sy * HALF_WIDTH, p1[2] - sz * HALF_WIDTH};
                float[] verts = new float[4 * MeshPoly.STRIDE];
                for (int i = 0; i < 4; i++) {
                    verts[i * 5] = (float) (corners[i][0] + nx * LIFT);
                    verts[i * 5 + 1] = (float) (corners[i][1] + ny * LIFT);
                    verts[i * 5 + 2] = (float) (corners[i][2] + nz * LIFT);
                    verts[i * 5 + 3] = u;
                    verts[i * 5 + 4] = v;
                }
                // keep the strip inside the face: it is only a mark, and must not poke into a neighbouring block
                float[] clipped = clipToFace(verts, axis, value);
                if (clipped == null) {
                    continue;
                }
                out.add(new MeshPoly(clipped, (float) nx, (float) ny, (float) nz, -1, emission, argb, sprite,
                        DIRECTION[axis * 2 + (value == 1 ? 0 : 1)], false));
            }
        }
        return out;
    }

    /** The two points where a convex polygon crosses the plane {@code coordinate[axis] = value}, or null. */
    private static double[][] crossing(float[] poly, int n, int axis, int value) {
        List<double[]> points = new ArrayList<>();
        boolean flat = true;
        for (int i = 0; i < n; i++) {
            if (Math.abs(poly[i * 3 + axis] - value) > 1e-4) {
                flat = false;
            }
        }
        if (flat) {
            return null; // lies in the face: nothing to mark but the face itself
        }
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double fi = poly[i * 3 + axis] - value, fj = poly[j * 3 + axis] - value;
            if (Math.abs(fi) <= 1e-5) {
                points.add(new double[]{poly[i * 3], poly[i * 3 + 1], poly[i * 3 + 2]});
            } else if (fi * fj < 0) {
                double t = fi / (fi - fj);
                points.add(new double[]{poly[i * 3] + (poly[j * 3] - poly[i * 3]) * t,
                        poly[i * 3 + 1] + (poly[j * 3 + 1] - poly[i * 3 + 1]) * t,
                        poly[i * 3 + 2] + (poly[j * 3 + 2] - poly[i * 3 + 2]) * t});
            }
        }
        if (points.size() < 2) {
            return null;
        }
        double[][] best = null;
        double bestLen = -1;
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                double[] a = points.get(i), b = points.get(j);
                double len = (a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]);
                if (len > bestLen) {
                    bestLen = len;
                    best = new double[][]{a, b};
                }
            }
        }
        return best;
    }

    /** Clamps the strip's corners into the unit square of the face (the lift off the face is kept). */
    private static float[] clipToFace(float[] verts, int axis, int value) {
        for (int i = 0; i < 4; i++) {
            for (int a = 0; a < 3; a++) {
                if (a != axis) {
                    verts[i * 5 + a] = Math.max(0f, Math.min(1f, verts[i * 5 + a]));
                }
            }
        }
        return verts;
    }

    private Grooves() {}
}
