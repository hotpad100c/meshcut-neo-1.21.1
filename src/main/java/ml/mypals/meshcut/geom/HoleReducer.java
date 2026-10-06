package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a convex cut-out part down to what the block can see of it. A laser's capsule has 62 planes, but a block is one
 * unit cube, and only a few of them ever touch it; the rest change nothing there and yet cost something everywhere the
 * region is used (stored, sent, hashed, clipped against, ...). Dropping them leaves {@code region ∩ cube} as it was.
 */
public final class HoleReducer {
    private static final double MIN_FACE_AREA = 1e-7;
    private static final double REACH = 8;
    private static final List<CutPlane> CUBE = List.of(new CutPlane(-1, 0, 0, 0), new CutPlane(1, 0, 0, 1),
            new CutPlane(0, -1, 0, 0), new CutPlane(0, 1, 0, 1), new CutPlane(0, 0, -1, 0), new CutPlane(0, 0, 1, 1));

    /**
     * @param planes   the planes that bound the part inside the block
     * @param vertices the corners of the part inside the block, {x, y, z} each (for containment tests)
     */
    public record Reduced(List<CutPlane> planes, List<double[]> vertices) {}

    /** Regions this small are boxes of the cell lattice (or burn prisms): nothing to gain, and others read them as such. */
    private static final int WORTH_REDUCING = 8;

    /**
     * @return the part restricted to the unit cube, or {@code null} if it has no volume in it. Regions that are already
     *         small are returned as they are.
     */
    public static Reduced reduce(List<CutPlane> region) {
        List<CutPlane> kept = new ArrayList<>();
        List<double[]> vertices = new ArrayList<>();
        for (int j = 0; j < region.size(); j++) {
            CutPlane plane = region.get(j);
            if (!crossesCube(plane)) {
                continue; // wholly on the inner side of the plane (or wholly outside): it bounds nothing here
            }
            List<CutPlane> limits = new ArrayList<>(region.size() + 5);
            for (int k = 0; k < region.size(); k++) {
                if (k != j) {
                    limits.add(region.get(k));
                }
            }
            limits.addAll(CUBE);
            List<MeshPoly> face = MeshClipper.clip(List.of(squareIn(plane)), limits);
            double area = 0;
            for (MeshPoly poly : face) {
                area += MeshClipper.area(poly.verts());
            }
            if (area > MIN_FACE_AREA) {
                kept.add(plane);
                for (MeshPoly poly : face) {
                    float[] v = poly.verts();
                    for (int i = 0; i < poly.vertexCount(); i++) {
                        vertices.add(new double[]{v[i * MeshPoly.STRIDE], v[i * MeshPoly.STRIDE + 1], v[i * MeshPoly.STRIDE + 2]});
                    }
                }
            }
        }
        if (kept.isEmpty()) {
            // no wall inside the block: the part either swallows all of it or misses it
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
        return new Reduced(List.copyOf(kept), vertices);
    }

    /** Whether every corner of the part (inside the block) lies within the region: it removes nothing new. */
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

    /** A big square lying in the plane. */
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
                // d - n·p = 0 on the plane, so the point nearest the origin is n*d
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
