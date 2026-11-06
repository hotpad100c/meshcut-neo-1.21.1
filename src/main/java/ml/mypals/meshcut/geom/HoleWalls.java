package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.List;

public final class HoleWalls {
    private static final double COPLANAR = 1e-5;

    public static List<MeshPoly> exposed(List<MeshPoly> wall, int index, List<List<CutPlane>> excluded) {
        List<CutPlane> region = excluded.get(index);
        double[] near = boundsOf(wall);
        for (int q = 0; q < excluded.size() && !wall.isEmpty(); q++) {
            List<CutPlane> other = excluded.get(q);
            if (other == region) {
                continue;
            }
            if (apart(HoleReducer.bounds(other), near)) {
                continue;
            }
            if (clearOf(wall, other)) {
                continue;
            }
            wall = withoutInside(wall, other);
            if (wall.isEmpty()) {
                break;
            }
            if (CutPlane.sameRegion(other, region)) {
                continue;
            }
            List<MeshPoly> kept = new ArrayList<>(), test = new ArrayList<>();
            for (MeshPoly poly : wall) {
                CutPlane shared = sharedPlane(poly, other);
                if (shared == null) {
                    test.add(poly);
                } else if (q > index) {
                    kept.add(poly);
                } else {
                    // Without that plane: a face lying in it is neither inside nor outside it, and float error would
                    // decide whether it survives. What is in it is limited by the other planes alone.
                    List<CutPlane> rest = new ArrayList<>(other);
                    rest.remove(shared);
                    kept.addAll(MeshClipper.subtract(List.of(poly), rest));
                }
            }
            kept.addAll(MeshClipper.subtract(test, other));
            wall = kept;
        }
        return wall;
    }

    private static double[] boundsOf(List<MeshPoly> polys) {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (MeshPoly poly : polys) {
            float[] v = poly.verts();
            for (int i = 0; i < v.length; i += MeshPoly.STRIDE) {
                for (int a = 0; a < 3; a++) {
                    b[a] = Math.min(b[a], v[i + a]);
                    b[a + 3] = Math.max(b[a + 3], v[i + a]);
                }
            }
        }
        return b;
    }

    private static boolean apart(double[] a, double[] b) {
        final double eps = 1e-4;
        for (int axis = 0; axis < 3; axis++) {
            if (a[axis + 3] < b[axis] - eps || b[axis + 3] < a[axis] - eps) return true;
        }
        return false;
    }

    private static List<MeshPoly> withoutInside(List<MeshPoly> wall, List<CutPlane> region) {
        List<MeshPoly> kept = null;
        for (int i = 0; i < wall.size(); i++) {
            if (insideStrictly(wall.get(i).verts(), region)) {
                if (kept == null) kept = new ArrayList<>(wall.subList(0, i));
            } else if (kept != null) {
                kept.add(wall.get(i));
            }
        }
        return kept == null ? wall : kept;
    }

    private static boolean insideStrictly(float[] v, List<CutPlane> region) {
        for (CutPlane plane : region) {
            for (int i = 0; i < v.length; i += MeshPoly.STRIDE) {
                if (plane.signedDistance(v[i], v[i + 1], v[i + 2]) <= 1e-7) return false;
            }
        }
        return true;
    }

    private static boolean clearOf(List<MeshPoly> wall, List<CutPlane> region) {
        for (MeshPoly poly : wall) {
            if (!MeshClipper.separated(poly.verts(), region)) return false;
        }
        return true;
    }

    private static CutPlane sharedPlane(MeshPoly face, List<CutPlane> region) {
        float[] v = face.verts();
        for (CutPlane p : region) {
            double dot = p.nx() * face.nx() + p.ny() * face.ny() + p.nz() * face.nz();
            if (dot < -0.9999) {
                boolean on = true;
                for (int i = 0; i < face.vertexCount() && on; i++) {
                    on = Math.abs(p.signedDistance(v[i * MeshPoly.STRIDE], v[i * MeshPoly.STRIDE + 1], v[i * MeshPoly.STRIDE + 2])) < COPLANAR;
                }
                if (on) {
                    return p;
                }
            }
        }
        return null;
    }

    private HoleWalls() {}
}
