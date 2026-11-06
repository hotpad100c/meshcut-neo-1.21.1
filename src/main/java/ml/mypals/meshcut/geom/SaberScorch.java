package ml.mypals.meshcut.geom;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

public final class SaberScorch {
    private static final double EPSILON = 1e-7;

    public static float[] project(MeshPoly face, Vector3d[] triangle, double halfWidth) {
        List<Vector3d> boundary = new ArrayList<>();
        float[] vertices = face.verts();
        for (int i = 0; i < vertices.length; i += MeshPoly.STRIDE)
            boundary.add(new Vector3d(vertices[i], vertices[i + 1], vertices[i + 2]));
        if (boundary.size() < 3) return new float[0];
        Vector3d normal = new Vector3d(face.nx(), face.ny(), face.nz());
        if (normal.lengthSquared() < EPSILON * EPSILON) return new float[0];
        normal.normalize();
        double plane = normal.dot(boundary.getFirst());
        List<Vector3d> intersections = new ArrayList<>();
        boolean coplanar = true;
        for (int i = 0; i < 3; i++) {
            Vector3d a = triangle[i], b = triangle[(i + 1) % 3];
            double da = normal.dot(a) - plane, db = normal.dot(b) - plane;
            if (Math.abs(da) <= EPSILON) addUnique(intersections, a);
            else coplanar = false;
            if (da * db < 0) addUnique(intersections, new Vector3d(a).lerp(b, da / (da - db)));
        }
        List<Vector3d> polygon;
        if (coplanar) {
            polygon = new ArrayList<>(List.of(new Vector3d(triangle[0]), new Vector3d(triangle[1]),
                    new Vector3d(triangle[2])));
        } else {
            if (intersections.size() < 2) return new float[0];
            Vector3d a = intersections.get(0), b = intersections.get(1);
            Vector3d along = new Vector3d(b).sub(a);
            if (along.lengthSquared() < EPSILON * EPSILON) return new float[0];
            Vector3d side = new Vector3d(normal).cross(along).normalize().mul(halfWidth);
            polygon = new ArrayList<>(List.of(new Vector3d(a).sub(side), new Vector3d(b).sub(side),
                    new Vector3d(b).add(side), new Vector3d(a).add(side)));
        }
        Vector3d area = new Vector3d();
        for (int i = 0; i < boundary.size(); i++)
            area.add(new Vector3d(boundary.get(i)).cross(boundary.get((i + 1) % boundary.size())));
        double sign = area.dot(normal) >= 0 ? 1 : -1;
        for (int i = 0; i < boundary.size() && polygon.size() >= 3; i++) {
            Vector3d a = boundary.get(i), b = boundary.get((i + 1) % boundary.size());
            Vector3d inward = new Vector3d(normal).cross(new Vector3d(b).sub(a)).mul(sign);
            List<Vector3d> clipped = new ArrayList<>();
            for (int j = 0; j < polygon.size(); j++) {
                Vector3d p = polygon.get(j), q = polygon.get((j + 1) % polygon.size());
                double dp = inward.dot(new Vector3d(p).sub(a)), dq = inward.dot(new Vector3d(q).sub(a));
                if (dp >= -EPSILON) clipped.add(p);
                if ((dp >= 0) != (dq >= 0)) clipped.add(new Vector3d(p).lerp(q, dp / (dp - dq)));
            }
            polygon = clipped;
        }
        if (polygon.size() < 3) return new float[0];
        Vector3d polygonArea = new Vector3d();
        for (int i = 0; i < polygon.size(); i++)
            polygonArea.add(new Vector3d(polygon.get(i)).cross(polygon.get((i + 1) % polygon.size())));
        if (polygonArea.lengthSquared() < 1e-14) return new float[0];
        float[] result = new float[polygon.size() * 3];
        for (int i = 0; i < polygon.size(); i++) {
            Vector3d point = polygon.get(i);
            result[i * 3] = (float) point.x;
            result[i * 3 + 1] = (float) point.y;
            result[i * 3 + 2] = (float) point.z;
        }
        return result;
    }

    private static void addUnique(List<Vector3d> points, Vector3d point) {
        for (Vector3d existing : points)
            if (existing.distanceSquared(point) < EPSILON * EPSILON) return;
        points.add(new Vector3d(point));
    }

    private SaberScorch() {}
}
