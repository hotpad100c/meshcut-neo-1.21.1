package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Convex half-space intersections on the 64 columns of the cell lattice. */
final class CellMasks {
    private static final int N = CellGrid.N;
    private record Key(List<CutPlane> planes, boolean strict) {}
    // Values are immutable after publication; compute outside the small cache lock.
    private static final Map<Key, long[]> CACHE = Collections.synchronizedMap(new LinkedHashMap<>(256, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, long[]> entry) { return size() > 256; }
    });

    static long[] of(List<CutPlane> planes, boolean strict) {
        Key key = new Key(List.copyOf(planes), strict);
        long[] known = CACHE.get(key);
        if (known != null) return known;
        long[] result = build(key.planes, strict);
        CACHE.put(key, result);
        return result;
    }

    private static boolean accepts(CutPlane p, int x, double y, double z, boolean strict) {
        double distance = p.signedDistance((x + .5) / N, y, z);
        // Preserve the original comparisons, including their handling of NaN.
        return strict ? !(distance <= 1e-9) : !(distance < 0);
    }

    private static long[] build(List<CutPlane> planes, boolean strict) {
        for (CutPlane p : planes) {
            double magnitude = Math.abs(p.d()) + Math.abs(p.nx()) + Math.abs(p.ny()) + Math.abs(p.nz());
            if (!Double.isFinite(magnitude) || magnitude > Double.MAX_VALUE / 4) return pointMask(planes, strict);
        }
        double[] inverse = new double[planes.size()];
        for (int i = 0; i < inverse.length; i++) inverse[i] = 1 / planes.get(i).nx();
        long[] mask = new long[N];
        for (int y = 0; y < N; y++) for (int z = 0; z < N; z++) {
            double yy = (y + .5) / N, zz = (z + .5) / N;
            int lo = 0, hi = N - 1;
            for (int i = 0; i < planes.size() && lo <= hi; i++) {
                CutPlane p = planes.get(i);
                boolean first = accepts(p, lo, yy, zz, strict), last = accepts(p, hi, yy, zz, strict);
                if (first == last) {
                    if (!first) hi = -1;
                    continue;
                }
                double root = (p.d() - p.ny() * yy - p.nz() * zz - (strict ? 1e-9 : 0)) * inverse[i];
                int guess = Double.isFinite(root)
                        ? (int) Math.clamp(Math.floor(root * N - .5), lo, hi) : (lo + hi) / 2;
                // The root is only a hint. Confirm the integer boundary with exactly the original
                // signedDistance expression, so rearrangement/division never decides a near-zero sign.
                if (first) {
                    while (guess >= lo && !accepts(p, guess, yy, zz, strict)) guess--;
                    while (guess < hi && accepts(p, guess + 1, yy, zz, strict)) guess++;
                    hi = guess;
                } else {
                    while (guess <= hi && !accepts(p, guess, yy, zz, strict)) guess++;
                    while (guess > lo && accepts(p, guess - 1, yy, zz, strict)) guess--;
                    lo = guess;
                }
            }
            long bit = 1L << (y * N + z);
            for (int x = lo; x <= hi; x++) mask[x] |= bit;
        }
        return mask;
    }

    private static long[] pointMask(List<CutPlane> planes, boolean strict) {
        long[] result = new long[N];
        for (int x = 0; x < N; x++) for (int y = 0; y < N; y++) for (int z = 0; z < N; z++) {
            boolean inside = true;
            for (CutPlane p : planes) if (!accepts(p, x, (y + .5) / N, (z + .5) / N, strict)) { inside = false; break; }
            if (inside) result[x] |= 1L << (y * N + z);
        }
        return result;
    }

    static long[] occupied(List<CutPlane> real, List<List<CutPlane>> holes) {
        long[] result = of(real, false).clone();
        for (List<CutPlane> hole : holes) {
            long[] removed = of(hole, true);
            for (int x = 0; x < N; x++) result[x] &= ~removed[x];
        }
        return result;
    }

    private CellMasks() {}
}
