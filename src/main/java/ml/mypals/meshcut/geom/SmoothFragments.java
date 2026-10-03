package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reconstructs visual boundaries from swept surfaces. Cell occupancy labels fragments, not rendered vertices. */
public final class SmoothFragments {
    private record Region(List<CutPlane> planes,List<MeshPoly> boundary) {}
    private final List<Region> regions=new ArrayList<>();
    private final boolean[] mask;
    private final List<Patch> patches;
    private final List<CutPlane> real;

    public SmoothFragments(boolean[] mask,List<Patch> patches,List<CutPlane> real) {
        this.mask=mask;
        this.patches=patches;
        this.real=real;
        int n=CellGrid.N;
        for(int cell=0;cell<CellGrid.CELLS;cell++) {
            int x=cell/(n*n),y=cell/n%n,z=cell%n;
            double[] bounds={x/(double)n,y/(double)n,z/(double)n,(x+1)/(double)n,(y+1)/(double)n,(z+1)/(double)n};
            List<CutPlane> box=VoxelFragments.planes(bounds);
            List<CutPlane> local=new ArrayList<>();
            for(Patch patch:patches) {
                if(!touches(patch,bounds)) continue;
                CutPlane p=patch.plane();
                // Only local tangent planes are combined; a bend elsewhere is never projected onto this plane.
                if(local.stream().noneMatch(q->Math.abs(q.nx()*p.nx()+q.ny()*p.ny()+q.nz()*p.nz())>0.9999
                        && Math.min(Math.abs(q.d()-p.d()),Math.abs(q.d()+p.d()))<1e-4)) local.add(p);
            }
            // Dense repeated strokes can contain hundreds of nearly identical tangent planes inside one cell.
            // Reduce only this cell's tangent samples; its occupancy and all server cuts remain untouched.
            if(local.size()>8) local=representatives(local,bounds,8);
            if(local.isEmpty() && !mask[cell]) continue;
            List<CutPlane> initial=new ArrayList<>(box);initial.addAll(real);
            List<MeshPoly> boundary=Polytope.faceMeshes(initial);
            if(boundary.isEmpty()) continue;
            List<Region> parts=new ArrayList<>();parts.add(new Region(initial,boundary));
            for(CutPlane p:local) {
                List<Region> next=new ArrayList<>();
                for(Region part:parts) {
                    double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;
                    for(MeshPoly face:part.boundary()) for(int i=0;i<face.verts().length;i+=MeshPoly.STRIDE) {
                        double d=p.signedDistance(face.verts()[i],face.verts()[i+1],face.verts()[i+2]);
                        min=Math.min(min,d);max=Math.max(max,d);
                    }
                    if(min>=-1e-7 || max<=1e-7) { next.add(part);continue; }
                    next.add(split(part,p));next.add(split(part,p.flipped()));
                }
                parts=next;
            }
            for(Region part:parts) {
                double[] c=center(part.boundary());
                if(belongs(c)) regions.add(part);
            }
        }
    }

    private static Region split(Region region,CutPlane plane) {
        List<CutPlane> planes=new ArrayList<>(region.planes());planes.add(plane);
        List<MeshPoly> mesh=new ArrayList<>(MeshClipper.clip(region.boundary(),List.of(plane)));
        mesh.addAll(CapBuilder.face(region.boundary(),plane,List.of(),new float[]{0,0,1,1},null));
        return new Region(planes,mesh);
    }

    private static List<CutPlane> representatives(List<CutPlane> input,double[] box,int limit) {
        List<CutPlane> out=new ArrayList<>();out.add(input.getFirst());
        double x=(box[0]+box[3])/2,y=(box[1]+box[4])/2,z=(box[2]+box[5])/2;
        while(out.size()<limit) {
            CutPlane best=null;double distance=-1;
            for(CutPlane p:input) {
                double nearest=Double.MAX_VALUE;
                for(CutPlane q:out) {
                    double dot=p.nx()*q.nx()+p.ny()*q.ny()+p.nz()*q.nz(),sign=dot>=0?1:-1;
                    double delta=p.signedDistance(x,y,z)-sign*q.signedDistance(x,y,z);
                    nearest=Math.min(nearest,1-Math.abs(dot)+delta*delta*64);
                }
                if(nearest>distance) {distance=nearest;best=p;}
            }
            if(best==null || distance<1e-12) break;
            out.add(best);
        }
        return out;
    }

    public List<MeshPoly> clip(List<MeshPoly> source) {
        List<MeshPoly> out=new ArrayList<>();
        for(Region region:regions) out.addAll(MeshClipper.clip(source,region.planes()));
        return out;
    }

    public List<MeshPoly> caps(List<MeshPoly> source,float[] uv,Object sprite) {
        Map<CutPlane,List<MeshPoly>> sections=new HashMap<>();
        List<MeshPoly> out=new ArrayList<>();
        for(Region region:regions) for(MeshPoly face:region.boundary()) {
            double[] c=center(List.of(face));
            if(c[0]<1e-7 || c[0]>1-1e-7 || c[1]<1e-7 || c[1]>1-1e-7 || c[2]<1e-7 || c[2]>1-1e-7) continue;
            double[] inside={c[0]-face.nx()*1e-4,c[1]-face.ny()*1e-4,c[2]-face.nz()*1e-4};
            double[] outside={c[0]+face.nx()*1e-4,c[1]+face.ny()*1e-4,c[2]+face.nz()*1e-4};
            if(!belongs(inside) || belongs(outside)) continue; // neither cell partitions nor internal fragment faces are visible
            CutPlane p=new CutPlane(face.nx(),face.ny(),face.nz(),face.nx()*c[0]+face.ny()*c[1]+face.nz()*c[2]);
            List<MeshPoly> section=sections.computeIfAbsent(p,k->CapBuilder.face(source,k,List.of(),uv,sprite));
            out.addAll(MeshClipper.clip(section,region.planes()));
        }
        return out;
    }

    private boolean belongs(double[] p) {
        for(CutPlane plane:real) if(plane.signedDistance(p[0],p[1],p[2]) < -1e-7) return false;
        int n=CellGrid.N;
        int x=(int)Math.floor(p[0]*n),y=(int)Math.floor(p[1]*n),z=(int)Math.floor(p[2]*n);
        int nearest=(Math.clamp(x,0,n-1)*n+Math.clamp(y,0,n-1))*n+Math.clamp(z,0,n-1);
        if(!blocked(p,CellGrid.centerOf(nearest))) return mask[nearest];
        double best=Double.MAX_VALUE;boolean owned=false;
        for(int a=Math.max(0,x-2);a<=Math.min(n-1,x+2);a++)
            for(int b=Math.max(0,y-2);b<=Math.min(n-1,y+2);b++)
                for(int c=Math.max(0,z-2);c<=Math.min(n-1,z+2);c++) {
                    int i=(a*n+b)*n+c;double[] q=CellGrid.centerOf(i);
                    double distance=(q[0]-p[0])*(q[0]-p[0])+(q[1]-p[1])*(q[1]-p[1])+(q[2]-p[2])*(q[2]-p[2]);
                    if(distance>=best || blocked(p,q)) continue;
                    best=distance;owned=mask[i];
                }
        return owned;
    }

    private boolean blocked(double[] a,double[] b) {
        for(Patch patch:patches) {
            CutPlane p=patch.plane();double da=p.signedDistance(a[0],a[1],a[2]),db=p.signedDistance(b[0],b[1],b[2]);
            if((da>0)==(db>0) || Math.abs(da-db)<1e-12) continue;
            double t=da/(da-db);
            if(patch.through() || patch.containsProjected(a[0]+t*(b[0]-a[0]),a[1]+t*(b[1]-a[1]),a[2]+t*(b[2]-a[2]),1e-5)) return true;
        }
        return false;
    }

    private static boolean touches(Patch patch,double[] box) {
        float[] v=patch.polygon();
        for(int axis=0;axis<3;axis++) {
            float low=Float.POSITIVE_INFINITY,high=Float.NEGATIVE_INFINITY;
            for(int i=axis;i<v.length;i+=3) {low=Math.min(low,v[i]);high=Math.max(high,v[i]);}
            if(high<box[axis]-1e-7 || low>box[axis+3]+1e-7) return false;
        }
        return true;
    }

    private static double[] center(List<MeshPoly> faces) {
        double[] center=new double[3];int count=0;
        for(MeshPoly face:faces) for(int i=0;i<face.verts().length;i+=MeshPoly.STRIDE) {
            for(int a=0;a<3;a++) center[a]+=face.verts()[i+a];count++;
        }
        if(count>0) for(int a=0;a<3;a++) center[a]/=count;
        return center;
    }
}
