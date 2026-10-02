package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A cut that may end inside a block: a plane together with the convex polygon, in that plane, the blade actually
 * swept. A cut that crosses the whole block ({@link #through()}) is the same as a half-space cut. One that stops
 * midway only blocks passage where it is, so a block can be scratched without coming apart, and several scratches
 * that close a loop seal off what is inside.
 * <p>
 * The polygon is in block-local coordinates (the block is the unit cube) and has {@code x,y,z} per vertex.
 */
public final class Patch {
    /** A patch covering at least this much of the plane's cross-section of the block counts as going through it. */
    public static final double THROUGH_COVERAGE = 0.9;
    private static final double BIG = 3.0;

    private final CutPlane plane;
    private final float[] polygon;
    private final boolean through;

    private Patch(CutPlane plane, float[] polygon, boolean through) {
        this.plane = plane;
        this.polygon = polygon;
        this.through = through;
    }

    /** The plane's whole cross-section of the block. */
    public static Patch full(CutPlane plane) {
        return new Patch(plane, fullPolygon(plane), true);
    }

    /**
     * @param swept a convex polygon in (or near) the plane, in block-local coordinates; it is clipped to the block
     * @return {@code null} if nothing of it is inside the block
     */
    public static Patch of(CutPlane plane, float[] swept) {
        float[] clipped = clipToCube(swept);
        if (clipped.length < 9) {
            return null;
        }
        double coverage = area(clipped) / area(fullPolygon(plane));
        // a cut that stops inside the block is not a cut: only those that go right through count
        return coverage >= THROUGH_COVERAGE ? full(plane) : null;
    }

    /**
     * The cut that approximates a bent stroke: all the given points, projected onto the plane and grown by
     * {@code pad} on every side (so that a stroke that reaches the edge of the block really reaches it), as one
     * patch.
     *
     * @return {@code null} if nothing of it is inside the block
     */
    public static Patch fromPoints(CutPlane plane, List<float[]> points, double pad) {
        double nx = plane.nx(), ny = plane.ny(), nz = plane.nz();
        double hx = Math.abs(nx) < 0.9 ? 1 : 0, hy = hx == 1 ? 0 : 1;
        double tx = hy * nz, ty = -hx * nz, tz = hx * ny - hy * nx;
        double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
        tx /= tl;
        ty /= tl;
        tz /= tl;
        double bx = ny * tz - nz * ty, by = nz * tx - nx * tz, bz = nx * ty - ny * tx;
        List<float[]> grown = new ArrayList<>(points.size() * 4);
        for (float[] p : points) {
            double dist = plane.d() - (nx * p[0] + ny * p[1] + nz * p[2]);
            double px = p[0] + nx * dist, py = p[1] + ny * dist, pz = p[2] + nz * dist;
            for (int s = 0; s < 4; s++) {
                double a = (s & 1) == 0 ? pad : -pad, b = (s & 2) == 0 ? pad : -pad;
                grown.add(new float[]{(float) (px + tx * a + bx * b), (float) (py + ty * a + by * b), (float) (pz + tz * a + bz * b)});
            }
        }
        float[] hull = new Patch(plane, new float[0], false).hull(grown);
        return hull.length < 9 ? null : of(plane, hull);
    }

    /** A patch from stored data. */
    public static Patch restore(CutPlane plane, float[] polygon, boolean through) {
        return new Patch(plane, polygon, through);
    }

    public CutPlane plane() {
        return plane;
    }

    public float[] polygon() {
        return polygon;
    }

    /** Whether it crosses the whole block, i.e. is a half-space cut. */
    public boolean through() {
        return through;
    }

    public int vertexCount() {
        return polygon.length / 3;
    }

    /**
     * Whether this and {@code other} are the same cut, or the continuation of one: about the same plane. (The
     * normals of two sweeps of one slash differ a little from tick to tick.)
     */
    public boolean samePlane(Patch other) {
        CutPlane a = plane, b = other.plane;
        double dot = a.nx() * b.nx() + a.ny() * b.ny() + a.nz() * b.nz();
        double offset = dot >= 0 ? Math.abs(a.d() - b.d()) : Math.abs(a.d() + b.d());
        return Math.abs(dot) > 0.96 && offset < 0.12;
    }

    /** The cut made by both: the smallest convex polygon in this plane that contains both patches. */
    public Patch union(Patch other) {
        if (through || other.through) {
            return full(plane);
        }
        List<float[]> points = new ArrayList<>();
        addPoints(points, polygon);
        addPoints(points, project(other.polygon));
        float[] hull = hull(points);
        Patch merged = hull.length < 9 ? this : of(plane, hull);
        return merged == null ? this : merged;
    }

    private float[] project(float[] poly) {
        float[] out = poly.clone();
        for (int i = 0; i < out.length; i += 3) {
            double dist = plane.d() - (plane.nx() * out[i] + plane.ny() * out[i + 1] + plane.nz() * out[i + 2]);
            out[i] += (float) (plane.nx() * dist);
            out[i + 1] += (float) (plane.ny() * dist);
            out[i + 2] += (float) (plane.nz() * dist);
        }
        return out;
    }

    /** Whether the point, projected onto the plane, lies inside the polygon grown by {@code tolerance}. */
    public boolean containsProjected(double x, double y, double z, double tolerance) {
        int n = vertexCount();
        double nx = plane.nx(), ny = plane.ny(), nz = plane.nz();
        double dist = plane.d() - (nx * x + ny * y + nz * z);
        double px = x + nx * dist, py = y + ny * dist, pz = z + nz * dist;
        double orientation = orientationSign();
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double ex = polygon[j * 3] - polygon[i * 3], ey = polygon[j * 3 + 1] - polygon[i * 3 + 1], ez = polygon[j * 3 + 2] - polygon[i * 3 + 2];
            double qx = px - polygon[i * 3], qy = py - polygon[i * 3 + 1], qz = pz - polygon[i * 3 + 2];
            double cx = ey * qz - ez * qy, cy = ez * qx - ex * qz, cz = ex * qy - ey * qx;
            double side = (cx * nx + cy * ny + cz * nz) * orientation;
            double len = Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (side < -tolerance * len) {
                return false;
            }
        }
        return true;
    }

    private double orientationSign() {
        double sx = 0, sy = 0, sz = 0;
        int n = vertexCount();
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sx += polygon[i * 3 + 1] * polygon[j * 3 + 2] - polygon[i * 3 + 2] * polygon[j * 3 + 1];
            sy += polygon[i * 3 + 2] * polygon[j * 3] - polygon[i * 3] * polygon[j * 3 + 2];
            sz += polygon[i * 3] * polygon[j * 3 + 1] - polygon[i * 3 + 1] * polygon[j * 3];
        }
        return sx * plane.nx() + sy * plane.ny() + sz * plane.nz() >= 0 ? 1 : -1;
    }

    // ---------------------------------------------------------------- geometry

    /** The plane's cross-section of the unit cube. */
    public static float[] fullPolygon(CutPlane plane) {
        double nx = plane.nx(), ny = plane.ny(), nz = plane.nz();
        double hx = Math.abs(nx) < 0.9 ? 1 : 0, hy = hx == 1 ? 0 : 1;
        double tx = hy * nz, ty = -hx * nz, tz = hx * ny - hy * nx;
        double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
        tx /= tl;
        ty /= tl;
        tz /= tl;
        double bx = ny * tz - nz * ty, by = nz * tx - nx * tz, bz = nx * ty - ny * tx;
        double cx = nx * plane.d(), cy = ny * plane.d(), cz = nz * plane.d();
        float[] square = new float[12];
        double[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < 4; i++) {
            double a = corners[i][0] * BIG, b = corners[i][1] * BIG;
            square[i * 3] = (float) (cx + tx * a + bx * b);
            square[i * 3 + 1] = (float) (cy + ty * a + by * b);
            square[i * 3 + 2] = (float) (cz + tz * a + bz * b);
        }
        return clipToCube(square);
    }

    /** Clips a convex polygon by the six faces of the unit cube. */
    public static float[] clipToCube(float[] poly) {
        float[] current = poly;
        for (int axis = 0; axis < 3 && current.length >= 9; axis++) {
            current = clipAxis(current, axis, 0, true);
            if (current.length >= 9) {
                current = clipAxis(current, axis, 1, false);
            }
        }
        return current.length < 9 ? new float[0] : current;
    }

    private static float[] clipAxis(float[] in, int axis, double bound, boolean keepAbove) {
        int n = in.length / 3;
        float[] out = new float[(n + 2) * 3];
        int m = 0;
        for (int i = 0; i < n; i++) {
            int a = i * 3, b = ((i + 1) % n) * 3;
            double da = (in[a + axis] - bound) * (keepAbove ? 1 : -1);
            double db = (in[b + axis] - bound) * (keepAbove ? 1 : -1);
            if (da >= 0) {
                System.arraycopy(in, a, out, m * 3, 3);
                m++;
            }
            if ((da >= 0) != (db >= 0)) {
                float t = (float) (da / (da - db));
                for (int k = 0; k < 3; k++) {
                    out[m * 3 + k] = in[a + k] + (in[b + k] - in[a + k]) * t;
                }
                m++;
            }
        }
        return Arrays.copyOf(out, m * 3);
    }

    public static double area(float[] poly) {
        int n = poly.length / 3;
        double ax = 0, ay = 0, az = 0;
        for (int i = 0; i < n; i++) {
            int a = i * 3, b = ((i + 1) % n) * 3;
            ax += poly[a + 1] * poly[b + 2] - poly[a + 2] * poly[b + 1];
            ay += poly[a + 2] * poly[b] - poly[a] * poly[b + 2];
            az += poly[a] * poly[b + 1] - poly[a + 1] * poly[b];
        }
        return 0.5 * Math.sqrt(ax * ax + ay * ay + az * az);
    }

    private static void addPoints(List<float[]> out, float[] poly) {
        for (int i = 0; i < poly.length; i += 3) {
            out.add(new float[]{poly[i], poly[i + 1], poly[i + 2]});
        }
    }

    /** Convex hull of coplanar points (Andrew's monotone chain in the plane's own 2D frame). */
    private float[] hull(List<float[]> points) {
        double nx = plane.nx(), ny = plane.ny(), nz = plane.nz();
        double hx = Math.abs(nx) < 0.9 ? 1 : 0, hy = hx == 1 ? 0 : 1;
        double tx = hy * nz, ty = -hx * nz, tz = hx * ny - hy * nx;
        double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
        tx /= tl;
        ty /= tl;
        tz /= tl;
        double bx = ny * tz - nz * ty, by = nz * tx - nx * tz, bz = nx * ty - ny * tx;
        List<double[]> pts = new ArrayList<>();
        for (float[] p : points) {
            pts.add(new double[]{p[0] * tx + p[1] * ty + p[2] * tz, p[0] * bx + p[1] * by + p[2] * bz, p[0], p[1], p[2]});
        }
        pts.sort((u, v) -> u[0] != v[0] ? Double.compare(u[0], v[0]) : Double.compare(u[1], v[1]));
        List<double[]> lower = new ArrayList<>();
        for (double[] p : pts) {
            while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), p) <= 1e-12) {
                lower.remove(lower.size() - 1);
            }
            lower.add(p);
        }
        List<double[]> upper = new ArrayList<>();
        for (int i = pts.size() - 1; i >= 0; i--) {
            double[] p = pts.get(i);
            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), p) <= 1e-12) {
                upper.remove(upper.size() - 1);
            }
            upper.add(p);
        }
        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        lower.addAll(upper);
        float[] out = new float[lower.size() * 3];
        for (int i = 0; i < lower.size(); i++) {
            out[i * 3] = (float) lower.get(i)[2];
            out[i * 3 + 1] = (float) lower.get(i)[3];
            out[i * 3 + 2] = (float) lower.get(i)[4];
        }
        return out;
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    // ---------------------------------------------------------------- value semantics (patches are cache keys)

    @Override
    public boolean equals(Object o) {
        return o instanceof Patch p && through == p.through && plane.equals(p.plane) && Arrays.equals(polygon, p.polygon);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * plane.hashCode() + Arrays.hashCode(polygon)) + (through ? 1 : 0);
    }

    @Override
    public String toString() {
        return "Patch[" + plane + (through ? ", through" : ", " + vertexCount() + " vertices") + "]";
    }
}
