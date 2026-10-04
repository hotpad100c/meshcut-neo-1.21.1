package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.List;

/** Convex polygon capsule. These planes cut actual model triangles; voxels only decide connectivity. */
public final class PolygonCapsule {
    public static double radius(int value) { return (VoxelCapsule.radius(value) + 0.50001) / 8.0; }

    public static List<CutPlane> planes(Vector3d a, Vector3d b, double radius) {
        if (!a.isFinite() || !b.isFinite() || !Double.isFinite(radius) || radius <= 0) return List.of();
        Vector3d axis = new Vector3d(b).sub(a);
        if (axis.lengthSquared() < 1e-12) axis.set(0, 0, 1);
        else axis.normalize();
        Vector3d u = axis.cross(Math.abs(axis.y) > 0.9 ? new Vector3d(1,0,0) : new Vector3d(0,1,0), new Vector3d()).normalize();
        Vector3d v = axis.cross(u, new Vector3d());
        List<CutPlane> out = new ArrayList<>();
        for (int latitude = -2; latitude <= 2; latitude++) {
            double angle = latitude * Math.PI / 6;
            for (int side = 0; side < 12; side++) {
                double phi = side * Math.PI / 6;
                Vector3d n = new Vector3d(u).mul(Math.cos(phi) * Math.cos(angle))
                        .fma(Math.sin(phi) * Math.cos(angle), v).fma(Math.sin(angle), axis);
                out.add(new CutPlane(n.x, n.y, n.z, Math.max(n.dot(a), n.dot(b)) + radius));
            }
        }
        out.add(new CutPlane(axis.x, axis.y, axis.z, Math.max(axis.dot(a), axis.dot(b)) + radius));
        axis.negate();
        out.add(new CutPlane(axis.x, axis.y, axis.z, Math.max(axis.dot(a), axis.dot(b)) + radius));
        return List.copyOf(out);
    }

    public static List<CutPlane> local(List<CutPlane> planes, int x, int y, int z) {
        return planes.stream().map(p -> new CutPlane(p.nx(), p.ny(), p.nz(), p.d() - p.nx()*x - p.ny()*y - p.nz()*z)).toList();
    }
    private PolygonCapsule() {}
}
