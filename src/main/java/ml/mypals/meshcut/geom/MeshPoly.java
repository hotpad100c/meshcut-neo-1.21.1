package ml.mypals.meshcut.geom;

/**
 * One convex polygon of a captured model: {@code verts} holds {@code x,y,z,u,v} per vertex
 * (block-local positions, atlas UVs). Attributes constant across the polygon are stored once.
 *
 * @param argb      colour multiplier (white for ordinary model quads)
 * @param sprite    the texture the UVs refer to, kept opaque so this class stays free of client types; may be null
 * @param cullFace  ordinal of the {@code Direction} whose neighbour hides this face, or -1 if it is never culled
 * @param shade     whether directional shading applies
 */
public record MeshPoly(float[] verts, float nx, float ny, float nz, int tintIndex, int lightEmission, int argb,
                       Object sprite, int cullFace, boolean shade) {
    public static final int STRIDE = 5;

    /** Polygon with no sprite, never culled and shaded. */
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
