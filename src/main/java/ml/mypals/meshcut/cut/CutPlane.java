package ml.mypals.meshcut.cut;

/**
 * A half-space in block-local coordinates (the block occupies 0..1 on every axis).
 * A point p is kept when {@code d - n·p >= 0}; i.e. the normal points towards the removed side.
 */
public record CutPlane(double nx, double ny, double nz, double d) {

    /** Builds a plane with a normalised normal. A zero normal yields {@code null}. */
    public static CutPlane of(double nx, double ny, double nz, double d) {
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-9) {
            return null;
        }
        return new CutPlane(nx / len, ny / len, nz / len, d / len);
    }

    /** Positive on the kept side, negative on the removed side. */
    public double signedDistance(double x, double y, double z) {
        return d - (nx * x + ny * y + nz * z);
    }

    /** The complementary half-space (the other piece of the same cut). */
    public CutPlane flipped() {
        return new CutPlane(-nx, -ny, -nz, -d);
    }

    /** Whether the two are (about) the same plane, facing either way. */
    public boolean sameAs(CutPlane o) {
        double dot = nx * o.nx + ny * o.ny + nz * o.nz;
        return Math.abs(dot) > 0.9999 && Math.abs(dot > 0 ? d - o.d : d + o.d) < 1e-4;
    }

    /** Whether the two lists hold (about) the same planes, in any order. */
    public static boolean sameRegion(java.util.List<CutPlane> a, java.util.List<CutPlane> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (CutPlane p : a) {
            boolean found = false;
            for (CutPlane q : b) {
                if (p.nx * q.nx + p.ny * q.ny + p.nz * q.nz > 0.9999 && Math.abs(p.d - q.d) < 1e-4) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
