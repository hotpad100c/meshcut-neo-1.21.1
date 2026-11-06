package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.List;

public final class Polytope {
    public static final double MIN_VOLUME = 0.01;
    private static final double MIN_FACE_AREA = 0.02;

    public enum Side { ALL_KEPT, ALL_REMOVED, SPLIT }

    private static final List<MeshPoly> CUBE = buildCube();
    private static final float[] DUMMY_UV = {0, 0, 1, 1};

    public static List<float[]> faces(List<CutPlane> planes) {
        List<MeshPoly> boundary = new ArrayList<>(MeshClipper.clip(CUBE, planes));
        boundary.addAll(CapBuilder.build(CUBE, planes, DUMMY_UV));
        List<float[]> out = new ArrayList<>();
        for (MeshPoly poly : boundary) {
            float[] v = poly.verts();
            int n = poly.vertexCount();
            float[] flat = new float[n * 3];
            for (int i = 0; i < n; i++) {
                flat[i * 3] = v[i * MeshPoly.STRIDE];
                flat[i * 3 + 1] = v[i * MeshPoly.STRIDE + 1];
                flat[i * 3 + 2] = v[i * MeshPoly.STRIDE + 2];
            }
            out.add(flat);
        }
        return out;
    }

    public static List<MeshPoly> faceMeshes(List<CutPlane> planes) {
        List<MeshPoly> boundary = new ArrayList<>(MeshClipper.clip(CUBE, planes));
        boundary.addAll(CapBuilder.build(CUBE, planes, DUMMY_UV));
        return boundary;
    }

    public static List<MeshPoly> cube() {
        return CUBE;
    }

    public static double volume(List<CutPlane> planes) {
        List<MeshPoly> boundary = new ArrayList<>(MeshClipper.clip(CUBE, planes));
        boundary.addAll(CapBuilder.build(CUBE, planes, DUMMY_UV));
        double sum = 0;
        for (MeshPoly poly : boundary) {
            float[] v = poly.verts();
            int n = poly.vertexCount();
            for (int i = 1; i + 1 < n; i++) {
                sum += det(v, 0, i, i + 1);
            }
        }
        return sum / 6.0;
    }

    public static Side classify(List<CutPlane> existing, CutPlane cut) {
        return classify(existing, cut, MIN_VOLUME);
    }

    public static Side classify(List<CutPlane> existing, CutPlane cut, double minVolume) {
        return classify(existing, cut, minVolume, 0);
    }

    private static final double RELATIVE_VOLUME = 0.15, RELATIVE_EXTENT = 0.3;

    public static Side classify(List<CutPlane> existing, List<List<CutPlane>> excluded, CutPlane cut, List<double[]> shape,
                                double minVolume, double minExtent) {
        double kept = 0, removed = 0, keptReach = 0, removedReach = 0;
        if (!excluded.isEmpty()) {
            List<double[]> keptBoxes = VoxelBoxes.remaining(shape, with(existing, cut), excluded);
            List<double[]> removedBoxes = VoxelBoxes.remaining(shape, with(existing, cut.flipped()), excluded);
            kept = VoxelBoxes.volume(keptBoxes);
            removed = VoxelBoxes.volume(removedBoxes);
            keptReach = reach(keptBoxes, cut);
            removedReach = reach(removedBoxes, cut.flipped());
            return sides(kept, removed, keptReach, removedReach, minVolume, minExtent);
        }
        for (double[] b : shape) {
            List<CutPlane> material = new ArrayList<>(existing);
            material.add(new CutPlane(-1, 0, 0, -b[0]));
            material.add(new CutPlane(1, 0, 0, b[3]));
            material.add(new CutPlane(0, -1, 0, -b[1]));
            material.add(new CutPlane(0, 1, 0, b[4]));
            material.add(new CutPlane(0, 0, -1, -b[2]));
            material.add(new CutPlane(0, 0, 1, b[5]));
            List<CutPlane> keep = new ArrayList<>(material);
            keep.add(cut);
            List<CutPlane> remove = new ArrayList<>(material);
            remove.add(cut.flipped());
            kept += volume(keep);
            removed += volume(remove);
            for (MeshPoly face : MeshClipper.clip(CUBE, material)) {
                float[] v = face.verts();
                for (int o = 0; o < v.length; o += MeshPoly.STRIDE) {
                    double dist = cut.signedDistance(v[o], v[o + 1], v[o + 2]);
                    keptReach = Math.max(keptReach, dist);
                    removedReach = Math.max(removedReach, -dist);
                }
            }
        }
        return sides(kept, removed, keptReach, removedReach, minVolume, minExtent);
    }

    private static Side sides(double kept, double removed, double keptReach, double removedReach, double minVolume,
                              double minExtent) {
        double total = kept + removed;
        if (total <= 0) {
            return Side.ALL_KEPT;
        }
        double volumeNeeded = Math.min(minVolume, RELATIVE_VOLUME * total);
        double extentNeeded = Math.min(minExtent, RELATIVE_EXTENT * (keptReach + removedReach));
        if (kept < volumeNeeded || keptReach < extentNeeded) {
            return removed > 0 ? Side.ALL_REMOVED : Side.ALL_KEPT;
        }
        if (removed < volumeNeeded || removedReach < extentNeeded) {
            return Side.ALL_KEPT;
        }
        return Side.SPLIT;
    }

    private static double reach(List<double[]> boxes, CutPlane plane) {
        double reach = 0;
        for (double[] b : boxes) {
            for (int i = 0; i < 8; i++) {
                reach = Math.max(reach, plane.signedDistance(b[(i & 1) == 0 ? 0 : 3], b[(i & 2) == 0 ? 1 : 4], b[(i & 4) == 0 ? 2 : 5]));
            }
        }
        return reach;
    }

    private static List<CutPlane> with(List<CutPlane> planes, CutPlane extra) {
        List<CutPlane> out = new ArrayList<>(planes);
        out.add(extra);
        return out;
    }

    public static Side classify(List<CutPlane> existing, CutPlane cut, double minVolume, double minExtent) {
        if (existing.isEmpty()) {
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                double dist = cut.signedDistance(i & 1, i >> 1 & 1, i >> 2 & 1);
                min = Math.min(min, dist);
                max = Math.max(max, dist);
            }
            if (min >= 0) {
                return Side.ALL_KEPT;
            }
            if (max <= 0) {
                return Side.ALL_REMOVED;
            }
        }
        List<CutPlane> keep = new ArrayList<>(existing);
        keep.add(cut);
        List<CutPlane> remove = new ArrayList<>(existing);
        remove.add(cut.flipped());
        if (volume(keep) < minVolume) {
            return Side.ALL_REMOVED;
        }
        if (volume(remove) < minVolume) {
            return Side.ALL_KEPT;
        }
        if (minExtent > 0) {
            double keptReach = 0, removedReach = 0;
            for (MeshPoly face : MeshClipper.clip(CUBE, existing)) {
                float[] v = face.verts();
                for (int o = 0; o < v.length; o += MeshPoly.STRIDE) {
                    double dist = cut.signedDistance(v[o], v[o + 1], v[o + 2]);
                    keptReach = Math.max(keptReach, dist);
                    removedReach = Math.max(removedReach, -dist);
                }
            }
            if (keptReach < minExtent) {
                return Side.ALL_REMOVED;
            }
            if (removedReach < minExtent) {
                return Side.ALL_KEPT;
            }
        }
        return Side.SPLIT;
    }

    public static boolean touchesFace(List<CutPlane> planes, int axis, boolean positive) {
        MeshPoly face = CUBE.get(axis * 2 + (positive ? 0 : 1));
        double area = 0;
        for (MeshPoly part : MeshClipper.clip(List.of(face), planes)) {
            area += MeshClipper.area(part.verts());
        }
        return area > MIN_FACE_AREA;
    }

    public static double[] centroid(List<CutPlane> planes) {
        final int n = 6;
        double x = 0, y = 0, z = 0;
        int count = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                for (int k = 0; k < n; k++) {
                    double px = (i + 0.5) / n, py = (j + 0.5) / n, pz = (k + 0.5) / n;
                    boolean inside = true;
                    for (CutPlane p : planes) {
                        if (p.signedDistance(px, py, pz) < 0) {
                            inside = false;
                            break;
                        }
                    }
                    if (inside) {
                        x += px;
                        y += py;
                        z += pz;
                        count++;
                    }
                }
            }
        }
        return count == 0 ? new double[]{0.5, 0.5, 0.5} : new double[]{x / count, y / count, z / count};
    }

    public static boolean facesOverlap(List<CutPlane> a, int axis, boolean positive, List<CutPlane> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return true;
        }
        double step = positive ? 1 : -1;
        List<CutPlane> movedB = new ArrayList<>(b.size());
        for (CutPlane p : b) {
            double shift = (axis == 0 ? p.nx() : axis == 1 ? p.ny() : p.nz()) * step;
            movedB.add(new CutPlane(p.nx(), p.ny(), p.nz(), p.d() + shift));
        }
        List<CutPlane> all = new ArrayList<>(a);
        all.addAll(movedB);
        double area = 0;
        for (MeshPoly part : MeshClipper.clip(List.of(CUBE.get(axis * 2 + (positive ? 0 : 1))), all)) {
            area += MeshClipper.area(part.verts());
        }
        return area > MIN_FACE_AREA;
    }

    private static double det(float[] v, int a, int b, int c) {
        int S = MeshPoly.STRIDE;
        double ax = v[a * S], ay = v[a * S + 1], az = v[a * S + 2];
        double bx = v[b * S], by = v[b * S + 1], bz = v[b * S + 2];
        double cx = v[c * S], cy = v[c * S + 1], cz = v[c * S + 2];
        return ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx);
    }

    private static List<MeshPoly> buildCube() {
        List<MeshPoly> faces = new ArrayList<>(6);
        int[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                int u = (axis + 1) % 3, w = (axis + 2) % 3;
                float[] verts = new float[4 * MeshPoly.STRIDE];
                for (int k = 0; k < 4; k++) {
                    int[] c = corners[sign > 0 ? k : 3 - k];
                    float[] p = new float[3];
                    p[axis] = sign > 0 ? 1 : 0;
                    p[u] = c[0];
                    p[w] = c[1];
                    System.arraycopy(p, 0, verts, k * MeshPoly.STRIDE, 3);
                }
                float[] n = new float[3];
                n[axis] = sign;
                faces.add(new MeshPoly(verts, n[0], n[1], n[2], -1, 0, 0xFFFFFFFF));
            }
        }
        return faces;
    }

    private Polytope() {}
}
