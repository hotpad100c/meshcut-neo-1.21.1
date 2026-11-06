package ml.mypals.meshcut.cut;

import ml.mypals.meshcut.geom.CellGrid;
import ml.mypals.meshcut.geom.Patch;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StrokeFit {
    private static final int MAX_PATCHES = 16;

    public record Fragment(Vector3d n, Vector3d motion, float[] polygon) {}

    private static final double STRAIGHT_TOLERANCE = 0.2;
    private static final double STRAIGHT_COS = 0.87;
    private static final double STROKE_PAD = 0.06;

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

    private static final double MIN_RUN = 0.05;
    private static final double END_BAND = 0.04;

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
                float[][] corners = new float[4][];
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
        CutPlane plane = new CutPlane(n.x, n.y, n.z, s.offset(n));
        return Patch.fromPoints(plane, s.points, STROKE_PAD);
    }

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

    public static void mergeInto(List<Patch> list, Patch patch) {
        mergeInto(list, patch, List.of(), List.of(), null);
    }

    public static void mergeInto(List<Patch> list, Patch patch, List<CutPlane> real, List<List<CutPlane>> excluded,
                                 @Nullable List<double[]> shape) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).samePlane(patch)) {
                list.set(i, list.get(i).union(patch));
                return;
            }
        }
        list.add(patch);
        while (list.size() > MAX_PATCHES) {
            list.remove(forgettable(list, real, excluded, shape));
        }
    }


    private static int forgettable(List<Patch> list, List<CutPlane> real, List<List<CutPlane>> excluded,
                                   @Nullable List<double[]> shape) {
        int all = CellGrid.of(real, excluded, list, shape).componentCount();
        for (int i = 0; i < list.size(); i++) {
            List<Patch> without = new ArrayList<>(list);
            without.remove(i);
            if (CellGrid.of(real, excluded, without, shape).componentCount() >= all) {
                return i;
            }
        }
        return 0;
    }

    private StrokeFit() {}
}
