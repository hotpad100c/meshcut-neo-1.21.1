package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;
import java.util.ArrayList;
import java.util.List;

/** Temporary emission is derived directly from cut patches, never from the detailed fragment mesh. */
public final class CoarseGlow {
    public static List<MeshPoly> build(List<CutPlane> cuts, List<Patch> patches, List<CutPlane> material,
                                      float[] uv, Object sprite) {
        return build(cuts, patches, material, List.of(), List.of(new double[]{0,0,0,1,1,1}), uv, sprite);
    }

    public static List<MeshPoly> build(List<CutPlane> cuts, List<Patch> patches, List<CutPlane> material,
                                      List<List<CutPlane>> excluded, List<double[]> shape, float[] uv, Object sprite) {
        List<MeshPoly> faces = new ArrayList<>();
        List<CutPlane> seen = new ArrayList<>();
        boolean[] mask = VoxelFragments.latticeMask(excluded);
        List<List<CutPlane>> regions = mask == null ? List.of() : VoxelFragments.boxes(mask).stream()
                .map(VoxelFragments::planes).toList();
        List<List<CutPlane>> bases = shape.stream().map(VoxelFragments::planes).toList();
        for (CutPlane cut : cuts) {
            if (seen.stream().anyMatch(cut::sameAs)) continue;
            seen.add(cut);
            float[] polygon = null;
            for (Patch patch : patches) if (cut.sameAs(patch.plane())) { polygon=patch.polygon(); break; }
            if (polygon==null) polygon=Patch.full(cut).polygon();
            if (polygon.length<9) continue;
            CutPlane outward=null;
            for (CutPlane plane : material) if (cut.sameAs(plane)) { outward=plane; break; }
            List<MeshPoly> section = new ArrayList<>();
            if (outward!=null) add(section,polygon,outward,uv,sprite);
            else { add(section,polygon,cut,uv,sprite); add(section,polygon,cut.flipped(),uv,sprite); }
            section = MeshClipper.clip(section, material);
            if (mask != null) {
                for (List<CutPlane> box : regions) {
                    List<MeshPoly> local = MeshClipper.clip(section, box);
                    for (List<CutPlane> base : bases) faces.addAll(MeshClipper.clip(local, base));
                }
            } else {
                for (List<CutPlane> hole : excluded) section = MeshClipper.subtract(section, hole);
                for (List<CutPlane> base : bases) faces.addAll(MeshClipper.clip(section, base));
            }
        }
        // Lift only AFTER clipping: clipping a lifted face against its own half-space would erase it.
        return faces.stream().map(face -> {
            float[] v = face.verts().clone();
            for (int i=0; i<v.length; i+=MeshPoly.STRIDE) {
                v[i] += face.nx()*.002f; v[i+1] += face.ny()*.002f; v[i+2] += face.nz()*.002f;
            }
            return face.withVerts(v);
        }).toList();
    }
    private static void add(List<MeshPoly> faces,float[] polygon,CutPlane plane,float[] uv,Object sprite) {
        float[] vertices = new float[polygon.length/3*MeshPoly.STRIDE];
        for(int i=0;i<polygon.length/3;i++) {
            int o=i*MeshPoly.STRIDE;
            vertices[o]=polygon[i*3];
            vertices[o+1]=polygon[i*3+1];
            vertices[o+2]=polygon[i*3+2];
            vertices[o+3]=(i%2==0 ? uv[0] : uv[2]);
            vertices[o+4]=(i<2 ? uv[1] : uv[3]);
        }
        double nx=0,ny=0,nz=0;
        for(int i=0;i<polygon.length/3;i++) {
            int a=i*3,b=(i+1)%(polygon.length/3)*3;
            nx+=(polygon[a+1]-polygon[b+1])*(polygon[a+2]+polygon[b+2]);
            ny+=(polygon[a+2]-polygon[b+2])*(polygon[a]+polygon[b]);
            nz+=(polygon[a]-polygon[b])*(polygon[a+1]+polygon[b+1]);
        }
        if(nx*plane.nx()+ny*plane.ny()+nz*plane.nz()<0) {
            float[] reversed=vertices.clone();
            for(int i=0;i<polygon.length/3;i++)
                System.arraycopy(reversed,(polygon.length/3-1-i)*MeshPoly.STRIDE,vertices,i*MeshPoly.STRIDE,MeshPoly.STRIDE);
        }
        faces.add(new MeshPoly(vertices,(float)plane.nx(),(float)plane.ny(),(float)plane.nz(),-1,15,
                0x99FFFFFF,sprite,-1,false));
    }
    private CoarseGlow() {}
}
