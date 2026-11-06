package ml.mypals.meshcut.cut;

public record CutPlane(double nx, double ny, double nz, double d) {

    public static CutPlane of(double nx, double ny, double nz, double d) {
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-9) {
            return null;
        }
        return new CutPlane(nx / len, ny / len, nz / len, d / len);
    }

    public double signedDistance(double x, double y, double z) {
        return d - (nx * x + ny * y + nz * z);
    }

    public CutPlane flipped() {
        return new CutPlane(-nx, -ny, -nz, -d);
    }

    public boolean sameAs(CutPlane o) {
        double dot = nx * o.nx + ny * o.ny + nz * o.nz;
        return Math.abs(dot) > 0.9999 && Math.abs(dot > 0 ? d - o.d : d + o.d) < 1e-4;
    }

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
