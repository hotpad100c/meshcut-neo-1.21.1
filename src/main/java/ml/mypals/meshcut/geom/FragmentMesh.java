package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;

/** Clips captured textured surfaces to a voxel union and emits only its exposed internal boundaries. */
public final class FragmentMesh {
    /** Permanent char on captured surfaces, bounded by the actual swept triangles. */
    public static List<MeshPoly> scorches(List<MeshPoly> source,List<Patch> patches) {
        List<MeshPoly> out=new ArrayList<>();
        for(MeshPoly face:source) for(Patch patch:patches) {
            float[] polygon=patch.polygon();
            for(int t=1;t<patch.vertexCount()-1;t++) {
                int[] indices={0,t,t+1};
                Vector3d[] triangle=new Vector3d[3];
                for(int i=0;i<3;i++) { int o=indices[i]*3; triangle[i]=new Vector3d(polygon[o],polygon[o+1],polygon[o+2]); }
                float[] scorch=SaberScorch.project(face,triangle,0.025);
                if(scorch.length<9) continue;
                float[] vertices=new float[scorch.length/3*MeshPoly.STRIDE];
                for(int i=0;i<scorch.length/3;i++) {
                    int o=i*MeshPoly.STRIDE;
                    vertices[o]=scorch[i*3];
                    vertices[o+1]=scorch[i*3+1];
                    vertices[o+2]=scorch[i*3+2];
                    float[] uv=uvAt(face,scorch[i*3],scorch[i*3+1],scorch[i*3+2]);
                    vertices[o+3]=uv[0]; vertices[o+4]=uv[1];
                }
                out.add(new MeshPoly(vertices,face.nx(),face.ny(),face.nz(),-1,0,0xFF251A13,face.sprite(),face.cullFace(),true));
            }
        }
        return out;
    }

    public static List<MeshPoly> liftScars(List<MeshPoly> polys) {
        List<MeshPoly> out=new ArrayList<>(polys.size());
        for(MeshPoly p:polys) {
            if(p.argb()!=0xFF251A13) { out.add(p); continue; }
            float[] v=p.verts().clone();
            for(int i=0;i<v.length;i+=MeshPoly.STRIDE) {
                v[i]+=p.nx()*0.001f; v[i+1]+=p.ny()*0.001f; v[i+2]+=p.nz()*0.001f;
            }
            out.add(p.withVerts(v));
        }
        return out;
    }

    private static float[] uvAt(MeshPoly face,double x,double y,double z) {
        float[] v=face.verts();
        Vector3d a=new Vector3d(v[0],v[1],v[2]), b=new Vector3d(v[5],v[6],v[7]).sub(a),
                c=new Vector3d(v[10],v[11],v[12]).sub(a), p=new Vector3d(x,y,z).sub(a);
        double bb=b.dot(b),cc=c.dot(c),bc=b.dot(c),det=bb*cc-bc*bc;
        if(Math.abs(det)<1e-12) return new float[]{v[3],v[4]};
        double u=(p.dot(b)*cc-p.dot(c)*bc)/det,w=(p.dot(c)*bb-p.dot(b)*bc)/det;
        return new float[]{(float)(v[3]+u*(v[8]-v[3])+w*(v[13]-v[3])),
                (float)(v[4]+u*(v[9]-v[4])+w*(v[14]-v[4]))};
    }
    public static List<MeshPoly> clip(List<MeshPoly> source, List<CutPlane> real, boolean[] mask) {
        List<MeshPoly> clipped=MeshClipper.clip(source,real), out=new ArrayList<>();
        for(double[] box:VoxelFragments.boxes(mask)) out.addAll(MeshClipper.clip(clipped,VoxelFragments.planes(box)));
        return out;
    }

    public static List<MeshPoly> caps(List<MeshPoly> source,List<CutPlane> real,boolean[] mask,float[] uv,Object sprite) {
        List<MeshPoly> out=clip(CapBuilder.build(source,real,uv,sprite),List.of(),mask);
        int n=CellGrid.N;
        for(int axis=0;axis<3;axis++) for(int side=-1;side<=1;side+=2) for(int depth=0;depth<n;depth++) {
            int next=depth+side;
            if(next<0 || next>=n) continue; // original exterior is supplied by the captured model
            boolean[] face=new boolean[mask.length];
            for(int i=0;i<mask.length;i++) {
                if(!mask[i]) continue;
                int[] c={i/(n*n),i/n%n,i%n};
                if(c[axis]!=depth) continue;
                int j=i+side*(axis==0?n*n:axis==1?n:1);
                if(!mask[j]) face[i]=true;
            }
            List<double[]> rectangles=VoxelFragments.boxes(face);
            if(rectangles.isEmpty()) continue;
            double[] normal=new double[3]; normal[axis]=side;
            double position=(depth+(side>0?1:0))/(double)n;
            CutPlane plane=new CutPlane(normal[0],normal[1],normal[2],side*position);
            // Build each planar section only once, then bound its rectangles.
            List<MeshPoly> section=CapBuilder.face(source,plane,real,uv,sprite);
            for(double[] rect:rectangles) out.addAll(MeshClipper.clip(section,VoxelFragments.planes(rect)));
        }
        return out;
    }

    private FragmentMesh() {}
}
