package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.List;

public final class HoleReducer {
    private static final double MIN_FACE_AREA = 1e-7;
    private static final double REACH = 8;
    private static final List<CutPlane> CUBE = List.of(new CutPlane(-1, 0, 0, 0), new CutPlane(1, 0, 0, 1),
            new CutPlane(0, -1, 0, 0), new CutPlane(0, 1, 0, 1), new CutPlane(0, 0, -1, 0), new CutPlane(0, 0, 1, 1));

    public record Reduced(List<CutPlane> planes, List<double[]> vertices) {}

    public static final class ReducedPlanes extends java.util.AbstractList<CutPlane> implements java.util.RandomAccess {
        private final CutPlane[] planes;
        private volatile float[][] walls;
        private volatile double[] bounds;

        private ReducedPlanes(List<CutPlane> planes, float[][] walls) {
            this.planes = planes.toArray(new CutPlane[0]);
            this.walls = walls;
        }

        @Override public CutPlane get(int index) { return planes[index]; }
        @Override public int size() { return planes.length; }

        private float[][] walls() {
            float[][] w = walls;
            if (w == null) {
                w = new float[planes.length][];
                for (int j = 0; j < planes.length; j++) {
                    w[j] = wallOn(this, j);
                }
                walls = w;
            }
            return w;
        }

        public double[] bounds() {
            double[] b = bounds;
            if (b == null) {
                b = new double[]{Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
                boolean any = false;
                for (float[] wall : walls()) {
                    if (wall == null) continue;
                    any = true;
                    for (int i = 0; i < wall.length; i += MeshPoly.STRIDE) {
                        for (int a = 0; a < 3; a++) {
                            b[a] = Math.min(b[a], wall[i + a]);
                            b[a + 3] = Math.max(b[a + 3], wall[i + a]);
                        }
                    }
                }
                if (!any) b = new double[]{0, 0, 0, 1, 1, 1};
                bounds = b;
            }
            return b;
        }

        public MeshPoly wall(int j, float[] uv, Object sprite) {
            float[] wall = walls()[j];
            if (wall == null) return null;
            CutPlane facing = planes[j].flipped();
            double nx = facing.nx(), ny = facing.ny(), nz = facing.nz();
            double hx = Math.abs(nx) < 0.9 ? 1 : 0, hy = hx == 1 ? 0 : 1;
            double tx = hy * nz, ty = -hx * nz, tz = hx * ny - hy * nx;
            double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
            tx /= tl; ty /= tl; tz /= tl;
            double bx = ny * tz - nz * ty, by = nz * tx - nx * tz, bz = nx * ty - ny * tx;
            double minA = Double.MAX_VALUE, minB = Double.MAX_VALUE, maxA = -Double.MAX_VALUE, maxB = -Double.MAX_VALUE;
            for (MeshPoly section : MeshClipper.clip(List.of(squareIn(planes[j])), CUBE)) {
                float[] s = section.verts();
                for (int i = 0; i < s.length; i += MeshPoly.STRIDE) {
                    double a = s[i] * tx + s[i + 1] * ty + s[i + 2] * tz, b = s[i] * bx + s[i + 1] * by + s[i + 2] * bz;
                    minA = Math.min(minA, a); maxA = Math.max(maxA, a);
                    minB = Math.min(minB, b); maxB = Math.max(maxB, b);
                }
            }
            double scale = 1.0 / Math.max(1.0, Math.max(maxA - minA, maxB - minB));
            int n = wall.length / MeshPoly.STRIDE;
            float[] v = new float[wall.length];
            for (int i = 0; i < n; i++) {
                int from = (n - 1 - i) * MeshPoly.STRIDE, to = i * MeshPoly.STRIDE;
                v[to] = wall[from]; v[to + 1] = wall[from + 1]; v[to + 2] = wall[from + 2];
                double a = v[to] * tx + v[to + 1] * ty + v[to + 2] * tz, b = v[to] * bx + v[to + 1] * by + v[to + 2] * bz;
                v[to + 3] = uv[0] + (uv[2] - uv[0]) * (float) ((a - minA) * scale);
                v[to + 4] = uv[1] + (uv[3] - uv[1]) * (float) ((b - minB) * scale);
            }
            return new MeshPoly(v, (float) nx, (float) ny, (float) nz, -1, 0, 0xFFFFFFFF, sprite, -1, true);
        }
    }

    public static ReducedPlanes adopt(List<CutPlane> region) {
        return region instanceof ReducedPlanes already ? already : new ReducedPlanes(region, null);
    }

    private static float[] wallOn(List<CutPlane> region, int j) {
        CutPlane plane = region.get(j);
        if (!crossesCube(plane)) return null;
        List<CutPlane> limits = new ArrayList<>(region.size() + 5);
        for (int k = 0; k < region.size(); k++) {
            if (k != j) limits.add(region.get(k));
        }
        limits.addAll(CUBE);
        float[] best = null;
        double bestArea = MIN_FACE_AREA;
        for (MeshPoly poly : MeshClipper.clip(List.of(squareIn(plane)), limits)) {
            double area = MeshClipper.area(poly.verts());
            if (area > bestArea) {
                bestArea = area;
                best = poly.verts();
            }
        }
        return best;
    }

    public static double[] bounds(List<CutPlane> region) {
        if (region instanceof ReducedPlanes prepared) return prepared.bounds();
        return WHOLE;
    }

    private static final double[] WHOLE = {0, 0, 0, 1, 1, 1};

    public static boolean apart(List<CutPlane> region, float[] verts) {
        if (!(region instanceof ReducedPlanes prepared)) return false;
        double[] b = prepared.bounds();
        double lx = Double.MAX_VALUE, ly = lx, lz = lx, hx = -Double.MAX_VALUE, hy = hx, hz = hx;
        for (int i = 0; i < verts.length; i += MeshPoly.STRIDE) {
            lx = Math.min(lx, verts[i]); hx = Math.max(hx, verts[i]);
            ly = Math.min(ly, verts[i + 1]); hy = Math.max(hy, verts[i + 1]);
            lz = Math.min(lz, verts[i + 2]); hz = Math.max(hz, verts[i + 2]);
        }
        final double eps = 1e-4;
        return hx < b[0] - eps || lx > b[3] + eps || hy < b[1] - eps || ly > b[4] + eps || hz < b[2] - eps || lz > b[5] + eps;
    }

    private static final int WORTH_REDUCING = 8;

    public static Reduced reduce(List<CutPlane> region) {
        List<CutPlane> kept = new ArrayList<>();
        List<float[]> walls = new ArrayList<>();
        List<double[]> vertices = new ArrayList<>();
        for (int j = 0; j < region.size(); j++) {
            float[] wall = wallOn(region, j);
            if (wall != null) {
                kept.add(region.get(j));
                walls.add(wall);
                for (int i = 0; i < wall.length / MeshPoly.STRIDE; i++) {
                    vertices.add(new double[]{wall[i * MeshPoly.STRIDE], wall[i * MeshPoly.STRIDE + 1], wall[i * MeshPoly.STRIDE + 2]});
                }
            }
        }
        if (kept.isEmpty()) {
            boolean everything = true;
            for (CutPlane p : region) {
                everything &= p.signedDistance(0.5, 0.5, 0.5) > 0;
            }
            if (!everything) {
                return null;
            }
            for (int corner = 0; corner < 8; corner++) {
                vertices.add(new double[]{corner & 1, corner >> 1 & 1, corner >> 2 & 1});
            }
            return new Reduced(region, vertices);
        }
        if (region.size() <= WORTH_REDUCING) {
            return new Reduced(region, vertices);
        }
        return new Reduced(new ReducedPlanes(kept, walls.toArray(new float[0][])), vertices);
    }

    public static boolean contains(List<CutPlane> region, List<double[]> vertices) {
        for (double[] v : vertices) {
            for (CutPlane p : region) {
                if (p.signedDistance(v[0], v[1], v[2]) < -1e-6) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean crossesCube(CutPlane plane) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            double d = plane.signedDistance(corner & 1, corner >> 1 & 1, corner >> 2 & 1);
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        return min < 1e-9 && max > -1e-9;
    }

    private static MeshPoly squareIn(CutPlane plane) {
        double[] n = {plane.nx(), plane.ny(), plane.nz()};
        double[] helper = Math.abs(n[0]) < 0.9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
        double[] t = cross(n, helper);
        double tl = Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]);
        for (int i = 0; i < 3; i++) t[i] /= tl;
        double[] b = cross(n, t);
        float[] v = new float[4 * MeshPoly.STRIDE];
        double[][] corner = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < 4; i++) {
            for (int axis = 0; axis < 3; axis++) {
                v[i * MeshPoly.STRIDE + axis] = (float) (n[axis] * plane.d() + REACH * (corner[i][0] * t[axis] + corner[i][1] * b[axis]));
            }
        }
        return new MeshPoly(v, (float) n[0], (float) n[1], (float) n[2], -1, 0, 0xFFFFFFFF);
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private HoleReducer() {}
}
