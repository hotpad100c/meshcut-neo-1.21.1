package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.List;

/**
 * Walls of parts cut out of a block, where several such parts overlap (a laser burns one block after another, and each
 * burn reaches back into the one before).
 */
public final class HoleWalls {
    private static final double COPLANAR = 1e-5;

    /**
     * What is left of a wall of {@code excluded.get(index)} once the other cut-out parts are taken from it: nothing is
     * behind a wall where another part was cut out too. Where two parts have a wall in the very same plane facing the
     * same way (the walls of two bores along one axis), each would take the other's away as lying inside it, and
     * neither would be drawn; the earlier part keeps the wall there, the later one gives up what the earlier covers.
     */
    public static List<MeshPoly> exposed(List<MeshPoly> wall, int index, List<List<CutPlane>> excluded) {
        List<CutPlane> region = excluded.get(index);
        for (int q = 0; q < excluded.size() && !wall.isEmpty(); q++) {
            List<CutPlane> other = excluded.get(q);
            if (other == region) {
                continue;
            }
            // Most holes of a block are nowhere near a given wall: a plane of theirs with the whole wall behind it says so
            // at once (it is also what makes the comparisons below, which are quadratic in the planes, rare).
            if (clearOf(wall, other)) {
                continue;
            }
            if (CutPlane.sameRegion(other, region)) {
                continue; // the same part cut out twice has one set of walls (the caller draws it once)
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

    private static boolean clearOf(List<MeshPoly> wall, List<CutPlane> region) {
        for (MeshPoly poly : wall) {
            if (!MeshClipper.separated(poly.verts(), region)) return false;
        }
        return true;
    }

    /** The plane of the region the face lies in, facing the way the region's wall there faces; null if none. */
    private static CutPlane sharedPlane(MeshPoly face, List<CutPlane> region) {
        float[] v = face.verts();
        for (CutPlane p : region) {
            double dot = p.nx() * face.nx() + p.ny() * face.ny() + p.nz() * face.nz();
            // the wall of a part faces into it, which is against its plane's outward normal
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
