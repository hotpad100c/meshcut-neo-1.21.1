package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PlaneIndex<T> {
    private final Map<Long, List<Entry<T>>> buckets = new HashMap<>();

    private record Entry<T>(CutPlane plane, T value) {}

    private static long key(CutPlane p) {
        double s = p.nx() > 1e-6 || p.nx() > -1e-6 && (p.ny() > 1e-6 || p.ny() > -1e-6 && p.nz() >= 0) ? 1 : -1;
        long x = Math.round(s * p.nx() * 100), y = Math.round(s * p.ny() * 100), z = Math.round(s * p.nz() * 100);
        return (x + 128) | (y + 128) << 8 | (z + 128) << 16;
    }

    public void put(CutPlane plane, T value) {
        buckets.computeIfAbsent(key(plane), k -> new ArrayList<>(2)).add(new Entry<>(plane, value));
    }

    public T find(CutPlane plane) {
        List<Entry<T>> bucket = buckets.get(key(plane));
        if (bucket != null) {
            for (Entry<T> e : bucket) {
                if (plane.sameAs(e.plane())) return e.value();
            }
        }
        return null;
    }

    public List<T> findAll(CutPlane plane) {
        List<T> out = new ArrayList<>(2);
        List<Entry<T>> bucket = buckets.get(key(plane));
        if (bucket != null) {
            for (Entry<T> e : bucket) {
                if (plane.sameAs(e.plane())) out.add(e.value());
            }
        }
        return out;
    }

    public boolean has(CutPlane plane) {
        return find(plane) != null;
    }

    public static PlaneIndex<Boolean> of(Iterable<CutPlane> planes) {
        PlaneIndex<Boolean> index = new PlaneIndex<>();
        for (CutPlane p : planes) index.put(p, Boolean.TRUE);
        return index;
    }
}
