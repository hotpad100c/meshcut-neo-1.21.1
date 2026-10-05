package ml.mypals.meshcut.geom;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;

/** Co-planar glow triangles become one bounding quad. Only the temporary glow is approximated. */
public final class GlowQuads {
    public static List<MeshPoly> simplify(List<MeshPoly> faces) {
        List<Group> groups = new ArrayList<>();
        for (MeshPoly face : faces) {
            if (face.vertexCount() < 3) continue;
            Group match = null;
            for (Group group : groups) if (group.matches(face)) { match = group; break; }
            if (match == null) { match = new Group(face); groups.add(match); }
            match.add(face);
        }
        return groups.stream().map(Group::quad).toList();
    }

    private static final class Group {
        final MeshPoly source;
        final Vector3f n, u, v;
        final float d;
        float minU = Float.POSITIVE_INFINITY, minV = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
        Group(MeshPoly face) {
            source = face;
            n = new Vector3f(face.nx(), face.ny(), face.nz()).normalize();
            u = new Vector3f(Math.abs(n.x) < .9f ? 1 : 0, Math.abs(n.x) < .9f ? 0 : 1, 0).cross(n).normalize();
            v = new Vector3f(n).cross(u);
            float[] p = face.verts();
            d = n.x*p[0] + n.y*p[1] + n.z*p[2];
        }
        boolean matches(MeshPoly face) {
            if (n.dot(face.nx(), face.ny(), face.nz()) < .9999f || source.sprite() != face.sprite()) return false;
            float[] p = face.verts();
            for (int i=0; i<p.length; i+=MeshPoly.STRIDE)
                if (Math.abs(n.x*p[i]+n.y*p[i+1]+n.z*p[i+2]-d) > .0001f) return false;
            return true;
        }
        void add(MeshPoly face) {
            float[] p = face.verts();
            for (int i=0; i<p.length; i+=MeshPoly.STRIDE) {
                float a=u.x*p[i]+u.y*p[i+1]+u.z*p[i+2], b=v.x*p[i]+v.y*p[i+1]+v.z*p[i+2];
                minU=Math.min(minU,a); maxU=Math.max(maxU,a);
                minV=Math.min(minV,b); maxV=Math.max(maxV,b);
            }
        }
        MeshPoly quad() {
            float[] p = new float[4*MeshPoly.STRIDE];
            float[] original = source.verts();
            for (int i=0; i<4; i++) {
                float a=(i==1 || i==2) ? maxU : minU, b=i>=2 ? maxV : minV;
                int o=i*MeshPoly.STRIDE;
                p[o]=u.x*a+v.x*b+n.x*d; p[o+1]=u.y*a+v.y*b+n.y*d; p[o+2]=u.z*a+v.z*b+n.z*d;
                // Sample the lava's existing atlas region; no atlas coordinates outside the original face.
                int t=(i%source.vertexCount())*MeshPoly.STRIDE;
                p[o+3]=original[t+3]; p[o+4]=original[t+4];
            }
            return source.withVerts(p);
        }
    }
    private GlowQuads() {}
}
