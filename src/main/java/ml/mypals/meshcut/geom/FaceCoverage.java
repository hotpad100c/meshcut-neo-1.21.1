package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;
import java.util.ArrayList;
import java.util.List;

public final class FaceCoverage {
    public static boolean whole(int axis, boolean high, List<CutPlane> planes, List<List<CutPlane>> holes) {
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        List<double[]> square = new ArrayList<>();
        for (int[] corner : new int[][]{{0,0},{1,0},{1,1},{0,1}}) {
            double[] p = new double[3];
            p[axis] = high ? 1 : 0; p[u] = corner[0]; p[v] = corner[1];
            square.add(p);
        }
        for (var plane : planes) for (var p : square)
            if (plane.signedDistance(p[0], p[1], p[2]) < -1e-7) return false;
        for (var p : square) p[axis] = high ? 1 - 1e-7 : 1e-7;
        for (var hole : holes) {
            List<double[]> polygon = square;
            for (var plane : hole) {
                polygon = clip(polygon, plane);
                if (polygon.isEmpty()) break;
            }
            double area = 0;
            for (int i = 0; i < polygon.size(); i++) {
                var a = polygon.get(i); var b = polygon.get((i + 1) % polygon.size());
                area += a[u] * b[v] - b[u] * a[v];
            }
            if (Math.abs(area) > 1e-12) return false;
        }
        return true;
    }

    private static List<double[]> clip(List<double[]> input, CutPlane plane) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            var a = input.get(i); var b = input.get((i + 1) % input.size());
            double da = plane.signedDistance(a[0], a[1], a[2]), db = plane.signedDistance(b[0], b[1], b[2]);
            if (da >= 0) out.add(a);
            if ((da >= 0) != (db >= 0)) {
                double t = da / (da - db);
                out.add(new double[]{a[0] + (b[0]-a[0])*t, a[1] + (b[1]-a[1])*t, a[2] + (b[2]-a[2])*t});
            }
        }
        return out;
    }
    private FaceCoverage() {}
}
