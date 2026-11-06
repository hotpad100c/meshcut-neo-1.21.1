package ml.mypals.meshcut.geom;

public record MeshPoly(float[] verts, float nx, float ny, float nz, int tintIndex, int lightEmission, int argb,
                       Object sprite, int cullFace, boolean shade) {
    public static final int STRIDE = 5;

    public MeshPoly(float[] verts, float nx, float ny, float nz, int tintIndex, int lightEmission, int argb) {
        this(verts, nx, ny, nz, tintIndex, lightEmission, argb, null, -1, true);
    }

    public int vertexCount() {
        return verts.length / STRIDE;
    }

    public MeshPoly withVerts(float[] newVerts) {
        return new MeshPoly(newVerts, nx, ny, nz, tintIndex, lightEmission, argb, sprite, cullFace, shade);
    }
}
