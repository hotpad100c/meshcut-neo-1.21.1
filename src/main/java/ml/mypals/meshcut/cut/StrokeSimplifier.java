package ml.mypals.meshcut.cut;

import java.util.ArrayList;
import java.util.List;

/** Reduces both endpoints together; reversals and extension changes retain their sweep. */
public final class StrokeSimplifier {
    public static final double ERROR = 0.005;
    private static final double MAX_SPAN = 2;

    public static List<Integer> retain(List<double[]> samples) {
        if (samples.size() < 2) return samples.isEmpty() ? List.of() : List.of(0);
        boolean[] keep = new boolean[samples.size()];
        keep[0] = keep[keep.length - 1] = true;
        split(samples, keep, 0, keep.length - 1);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < keep.length; i++) if (keep[i]) out.add(i);
        return out;
    }

    private static void split(List<double[]> points, boolean[] keep, int first, int last) {
        if (last - first < 2) return;
        double[] a = points.get(first), b = points.get(last);
        double worst = ERROR * ERROR;
        int pivot = -1;
        for (int i = first + 1; i < last; i++) {
            double t = (double) (i - first) / (last - first);
            double[] p = points.get(i);
            for (int endpoint = 0; endpoint < 6; endpoint += 3) {
                double error = 0;
                for (int k = endpoint; k < endpoint + 3; k++) {
                    double d = p[k] - (a[k] + (b[k] - a[k]) * t);
                    error += d * d;
                }
                if (error > worst) { worst = error; pivot = i; }
            }
        }
        for (int endpoint = 0; endpoint < 6; endpoint += 3) {
            double distance = 0;
            for (int k = endpoint; k < endpoint + 3; k++) distance += Math.pow(b[k] - a[k], 2);
            if (distance > MAX_SPAN * MAX_SPAN && pivot < 0) pivot = (first + last) / 2;
        }
        if (pivot >= 0) {
            keep[pivot] = true;
            split(points, keep, first, pivot);
            split(points, keep, pivot, last);
        }
    }

    private StrokeSimplifier() {}
}
