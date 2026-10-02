package ml.mypals.meshcut.cut;

import ml.mypals.meshcut.geom.CellGrid;
import ml.mypals.meshcut.geom.Patch;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns what the blade swept inside each block into the cuts ({@link Patch}es) that block remembers. Pure geometry,
 * no world access.
 */
public final class StrokeFit {
    /** Cuts remembered per block; beyond this the oldest is forgotten. */
    private static final int MAX_PATCHES = 16;

    /** The part of one swept quad inside one block, in that block's frame; {@code motion} is how the blade travelled. */
    public record Fragment(Vector3d n, Vector3d motion, float[] polygon) {}

    /** How far a stroke's pieces inside a block may stray from one plane for them to count as one straight cut. */
    private static final double STRAIGHT_TOLERANCE = 0.2;
    /** The sharpest bend (as the cosine of the angle between normals) a stroke may take and still be one cut. */
    private static final double STRAIGHT_COS = 0.87;
    /** How far a stroke is stretched at its ends, so one that reaches a block's edge really reaches it. */
    private static final double STROKE_PAD = 0.06;

    /** One straight approximation of what was swept inside a block. */
    private static final class Stroke {
        final Vector3d normalSum = new Vector3d();
        final Vector3d motionSum = new Vector3d();
        final List<float[]> points = new ArrayList<>();

        Vector3d normal() {
            return new Vector3d(normalSum).normalize();
        }

        double offset(Vector3d n) {
            double sum = 0;
            for (float[] p : points) {
                sum += n.x * p[0] + n.y * p[1] + n.z * p[2];
            }
            return sum / points.size();
        }

        boolean accepts(Fragment f) {
            Vector3d n = normal();
            if (Math.abs(n.dot(f.n())) < STRAIGHT_COS) {
                return false;
            }
            double d = offset(n);
            float[] v = f.polygon();
            for (int i = 0; i < v.length; i += 3) {
                if (Math.abs(n.x * v[i] + n.y * v[i + 1] + n.z * v[i + 2] - d) > STRAIGHT_TOLERANCE) {
                    return false;
                }
            }
            return true;
        }

        void add(Fragment f) {
            motionSum.add(f.motion());
            if (normalSum.dot(f.n()) >= 0) {
                normalSum.add(f.n());
            } else {
                normalSum.sub(f.n());
            }
            float[] v = f.polygon();
            for (int i = 0; i < v.length; i += 3) {
                points.add(new float[]{v[i], v[i + 1], v[i + 2]});
            }
        }
    }

    /** A stroke that covers less than this along its path (in blocks) has no entry or exit to speak of. */
    private static final double MIN_RUN = 0.05;
    /** Points this close (along the path) to where the stroke enters or leaves the block count as that entry or exit. */
    private static final double END_BAND = 0.04;

    /**
     * The cut a stroke makes in one block. Where the swept surface crosses the faces of the block it enters and
     * leaves by, it has two end points each: the entry points and the exit points. The cut is the plane through those
     * four points. The next block along the stroke has the same points on the face the two share, so the cuts of
     * neighbouring blocks meet there instead of each being fitted on its own.
     */
    @Nullable
    static Patch patchOf(Stroke s) {
        Vector3d n = s.normal();
        Vector3d d = new Vector3d(s.motionSum);
        d.sub(new Vector3d(n).mul(d.dot(n)));
        if (d.lengthSquared() > 1e-10) {
            d.normalize();
            Vector3d b = new Vector3d(n).cross(d).normalize();
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (float[] p : s.points) {
                double along = d.x * p[0] + d.y * p[1] + d.z * p[2];
                lo = Math.min(lo, along);
                hi = Math.max(hi, along);
            }
            if (hi - lo >= MIN_RUN) {
                float[][] corners = new float[4][]; // entry low, entry high, exit high, exit low (across the blade)
                double[] best = {Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE};
                for (float[] p : s.points) {
                    double along = d.x * p[0] + d.y * p[1] + d.z * p[2];
                    double across = b.x * p[0] + b.y * p[1] + b.z * p[2];
                    if (along <= lo + END_BAND) {
                        if (across < best[0]) {
                            best[0] = across;
                            corners[0] = p;
                        }
                        if (across > best[1]) {
                            best[1] = across;
                            corners[1] = p;
                        }
                    }
                    if (along >= hi - END_BAND) {
                        if (across > best[2]) {
                            best[2] = across;
                            corners[2] = p;
                        }
                        if (across < best[3]) {
                            best[3] = across;
                            corners[3] = p;
                        }
                    }
                }
                // the normal of the quad through the four points, and its position
                Vector3d q = new Vector3d();
                for (int i = 0; i < 4; i++) {
                    float[] a = corners[i], c = corners[(i + 1) % 4];
                    q.x += (a[1] - c[1]) * (a[2] + c[2]);
                    q.y += (a[2] - c[2]) * (a[0] + c[0]);
                    q.z += (a[0] - c[0]) * (a[1] + c[1]);
                }
                if (q.lengthSquared() > 1e-8) {
                    q.normalize();
                    if (q.dot(n) < 0) {
                        q.negate();
                    }
                    double offset = 0;
                    for (float[] c : corners) {
                        offset += q.x * c[0] + q.y * c[1] + q.z * c[2];
                    }
                    CutPlane plane = new CutPlane(q.x, q.y, q.z, offset / 4);
                    List<float[]> all = new ArrayList<>(s.points);
                    return Patch.fromPoints(plane, all, STROKE_PAD);
                }
            }
        }
        // too short to have an entry and an exit: the plane that fits what was swept
        CutPlane plane = new CutPlane(n.x, n.y, n.z, s.offset(n));
        return Patch.fromPoints(plane, s.points, STROKE_PAD);
    }

    /**
     * Turns what the blade swept inside each block into patches. A stroke that bends within a block is replaced by
     * the straight cut that is its best fit, so a wavy path still cuts; one that bends too much, or crosses itself,
     * becomes several cuts.
     */
    public static <K> Map<K, List<Patch>> fitPatches(Map<K, List<Fragment>> fragments) {
        Map<K, List<Patch>> out = new LinkedHashMap<>();
        for (var entry : fragments.entrySet()) {
            List<Stroke> strokes = new ArrayList<>();
            for (Fragment f : entry.getValue()) {
                Stroke home = null;
                for (Stroke s : strokes) {
                    if (s.accepts(f)) {
                        home = s;
                        break;
                    }
                }
                if (home == null) {
                    home = new Stroke();
                    strokes.add(home);
                }
                home.add(f);
            }
            for (Stroke s : strokes) {
                Patch patch = patchOf(s);
                if (patch != null) {
                    mergeInto(out.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()), patch);
                }
            }
        }
        return out;
    }

    /** Adds a patch to a block's list, joining it to one it continues. */
    public static void mergeInto(List<Patch> list, Patch patch) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).samePlane(patch)) {
                list.set(i, list.get(i).union(patch));
                return;
            }
        }
        list.add(patch);
        while (list.size() > MAX_PATCHES) {
            list.remove(forgettable(list));
        }
    }


    /**
     * Which cut to forget when there are too many: one that divides nothing (the pieces are the same without it), or
     * else the oldest.
     */
    private static int forgettable(List<Patch> list) {
        int all = CellGrid.of(List.of(), List.of(), list).componentCount();
        for (int i = 0; i < list.size(); i++) {
            List<Patch> without = new ArrayList<>(list);
            without.remove(i);
            if (CellGrid.of(List.of(), List.of(), without).componentCount() >= all) {
                return i;
            }
        }
        return 0;
    }

    private StrokeFit() {}
}
