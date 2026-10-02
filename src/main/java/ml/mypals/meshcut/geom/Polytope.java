package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.List;

/** Geometry of "unit cube ∩ half-spaces", used server-side to decide how a cut affects a block. */
public final class Polytope {
    /** Below this volume (in blocks) a remaining piece is considered a sliver and discarded. */
    public static final double MIN_VOLUME = 0.01;
    /** A remaining face smaller than this (in block faces) doesn't count as touching a neighbour. */
    private static final double MIN_FACE_AREA = 0.02;

    /** Which part of a block lies on the kept side of a plane. */
    public enum Side { ALL_KEPT, ALL_REMOVED, SPLIT }

    private static final List<MeshPoly> CUBE = buildCube();
    private static final float[] DUMMY_UV = {0, 0, 1, 1};

    /**
     * The faces of the unit cube clipped by the planes, caps included, each as {x, y, z} per vertex in turn.
     */
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

    /** The faces of the unit cube clipped by the planes, caps included. */
    public static List<MeshPoly> faceMeshes(List<CutPlane> planes) {
        List<MeshPoly> boundary = new ArrayList<>(MeshClipper.clip(CUBE, planes));
        boundary.addAll(CapBuilder.build(CUBE, planes, DUMMY_UV));
        return boundary;
    }

    /** The six faces of the unit cube, as a closed solid. */
    public static List<MeshPoly> cube() {
        return CUBE;
    }

    /** Volume of the unit cube clipped by the given planes. */
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

    /** Classifies how {@code cut} divides the block already restricted by {@code existing}. */
    public static Side classify(List<CutPlane> existing, CutPlane cut) {
        return classify(existing, cut, MIN_VOLUME);
    }

    /** As {@link #classify(List, CutPlane)}, but a piece smaller than {@code minVolume} counts as nothing. */
    public static Side classify(List<CutPlane> existing, CutPlane cut, double minVolume) {
        return classify(existing, cut, minVolume, 0);
    }

    /**
     * As {@link #classify(List, CutPlane, double)}, and a side that reaches less than {@code minExtent} away from
     * the plane (at its furthest point) counts as nothing too. Volume alone lets a thin layer over a whole face pass
     * as a real cut, though it is only the blade grazing the surface; extent does not.
     */
    public static Side classify(List<CutPlane> existing, CutPlane cut, double minVolume, double minExtent) {
        if (existing.isEmpty()) { // untouched cube: its corners decide, exactly
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

    /**
     * Whether any of the block's face on {@code axis} (0=x, 1=y, 2=z; positive or negative side) is still
     * there after the cuts, i.e. the block still touches a neighbour on that side.
     */
    public static boolean touchesFace(List<CutPlane> planes, int axis, boolean positive) {
        MeshPoly face = CUBE.get(axis * 2 + (positive ? 0 : 1));
        double area = 0;
        for (MeshPoly part : MeshClipper.clip(List.of(face), planes)) {
            area += MeshClipper.area(part.verts());
        }
        return area > MIN_FACE_AREA;
    }

    /** Rough centre of the unit cube clipped by the planes (sampled), or the cube's middle if nothing is left. */
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

    /**
     * Whether the faces of two blocks that touch really overlap. {@code a}'s face on {@code axis}/{@code positive}
     * is clipped by {@code a}'s cuts, then by {@code b}'s cuts moved into {@code a}'s frame; if something is left,
     * the two solids are in contact. A cut running through a shared face can leave the halves on either side of it
     * touching nothing.
     */
    public static boolean facesOverlap(List<CutPlane> a, int axis, boolean positive, List<CutPlane> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return true;
        }
        double step = positive ? 1 : -1;
        List<CutPlane> movedB = new ArrayList<>(b.size());
        for (CutPlane p : b) { // b is one block further along the axis: its local point = ours - step along axis
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

    /** Six faces, counter-clockwise seen from outside. */
    private static List<MeshPoly> buildCube() {
        List<MeshPoly> faces = new ArrayList<>(6);
        int[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                int u = (axis + 1) % 3, w = (axis + 2) % 3;
                float[] verts = new float[4 * MeshPoly.STRIDE];
                for (int k = 0; k < 4; k++) {
                    int[] c = corners[sign > 0 ? k : 3 - k]; // reversed winding for the negative face
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
