package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class VoxelFragments {
    public static List<List<CutPlane>> excluded(CellGrid grid, Set<Integer> groups) {
        boolean[] removed = new boolean[CellGrid.CELLS];
        for (int i = 0; i < removed.length; i++) removed[i] = !groups.contains(grid.component(i));
        return boxes(removed).stream().map(VoxelFragments::planes).toList();
    }

    public static List<List<CutPlane>> compact(List<List<CutPlane>> excluded) {
        boolean[] mask=mask(excluded);
        if(mask==null) return excluded;
        for(int i=0;i<mask.length;i++) mask[i]=!mask[i];
        return boxes(mask).stream().map(VoxelFragments::planes).toList();
    }

    public static List<List<CutPlane>> edgeBurns(List<Patch> patches) {
        List<List<CutPlane>> burnt=new ArrayList<>();
        for(Patch patch:patches) {
            CutPlane p=patch.plane();
            double low=Math.min(0,p.nx())+Math.min(0,p.ny())+Math.min(0,p.nz());
            double high=Math.max(0,p.nx())+Math.max(0,p.ny())+Math.max(0,p.nz());
            if(p.d()<=low+1e-7 || p.d()>=high-1e-7 || Math.min(p.d()-low,high-p.d())>0.0625) continue;
            List<CutPlane> region=new ArrayList<>();
            region.add(p.d()-low<high-p.d()?p:p.flipped());
            float[] v=patch.polygon();
            double orientation=0;
            for(int i=0;i<patch.vertexCount();i++) {
                int a=i*3,b=((i+1)%patch.vertexCount())*3;
                orientation+=(v[a+1]*v[b+2]-v[a+2]*v[b+1])*p.nx()
                        +(v[a+2]*v[b]-v[a]*v[b+2])*p.ny()+(v[a]*v[b+1]-v[a+1]*v[b])*p.nz();
            }
            double sign=orientation>=0?1:-1;
            for(int i=0;i<patch.vertexCount();i++) {
                int a=i*3,b=((i+1)%patch.vertexCount())*3;
                double ex=v[b]-v[a],ey=v[b+1]-v[a+1],ez=v[b+2]-v[a+2];
                double nx=sign*(ey*p.nz()-ez*p.ny()),ny=sign*(ez*p.nx()-ex*p.nz()),nz=sign*(ex*p.ny()-ey*p.nx());
                CutPlane edge=CutPlane.of(nx,ny,nz,nx*v[a]+ny*v[a+1]+nz*v[a+2]);
                if(edge!=null) region.add(edge);
            }
            if(region.size()>=4 && !burnt.contains(region)) burnt.add(List.copyOf(region));
        }
        return burnt;
    }

    public static List<double[]> boxes(boolean[] occupied) {
        boolean[] left = occupied.clone();
        List<double[]> out = new ArrayList<>();
        int n = CellGrid.N;
        for (int x = 0; x < n; x++) for (int y = 0; y < n; y++) for (int z = 0; z < n; z++) {
            if (!left[index(x,y,z)]) continue;
            int ze = z + 1, ye = y + 1, xe = x + 1;
            while (ze < n && left[index(x,y,ze)]) ze++;
            while (ye < n && full(left,x,x+1,ye,ye+1,z,ze)) ye++;
            while (xe < n && full(left,xe,xe+1,y,ye,z,ze)) xe++;
            for (int a=x;a<xe;a++) for(int b=y;b<ye;b++) for(int c=z;c<ze;c++) left[index(a,b,c)]=false;
            out.add(new double[]{x/(double)n,y/(double)n,z/(double)n,xe/(double)n,ye/(double)n,ze/(double)n});
        }
        return out;
    }

    private static boolean full(boolean[] bits,int x,int xe,int y,int ye,int z,int ze) {
        for(int a=x;a<xe;a++) for(int b=y;b<ye;b++) for(int c=z;c<ze;c++) if(!bits[index(a,b,c)]) return false;
        return true;
    }

    private static int index(int x,int y,int z) { return (x*CellGrid.N+y)*CellGrid.N+z; }

    public static List<CutPlane> planes(double[] b) {
        return List.of(new CutPlane(-1,0,0,-b[0]),new CutPlane(1,0,0,b[3]),
                new CutPlane(0,-1,0,-b[1]),new CutPlane(0,1,0,b[4]),
                new CutPlane(0,0,-1,-b[2]),new CutPlane(0,0,1,b[5]));
    }

    private static double[] latticeBox(List<CutPlane> region) {
        if (region.size() != 6) return null;
        double[] bounds = {0, 0, 0, 1, 1, 1};
        int seen = 0;
        for (CutPlane p : region) {
            double[] normal = {p.nx(), p.ny(), p.nz()};
            int axis = -1;
            for (int a = 0; a < 3; a++) if (Math.abs(normal[a]) > 1e-8) {
                if (axis >= 0 || Math.abs(Math.abs(normal[a]) - 1) > 1e-8) return null;
                axis = a;
            }
            if (axis < 0) return null;
            int slot = axis + (normal[axis] > 0 ? 3 : 0);
            if ((seen & 1 << slot) != 0) return null;
            seen |= 1 << slot;
            double v = p.d() / normal[axis];
            if (v < -1e-8 || v > 1 + 1e-8 || Math.abs(v * CellGrid.N - Math.rint(v * CellGrid.N)) > 1e-6) return null;
            bounds[slot] = v;
        }
        return bounds;
    }

    public static boolean[] mask(List<List<CutPlane>> excluded) {
        if (excluded.isEmpty()) return null;
        List<double[]> boxes = new ArrayList<>(excluded.size());
        for (List<CutPlane> region : excluded) {
            double[] bounds = latticeBox(region);
            if (bounds == null) return null;
            boxes.add(bounds);
        }
        boolean[] occupied = new boolean[CellGrid.CELLS];
        java.util.Arrays.fill(occupied, true);
        for (double[] bounds : boxes) {
            for (int i = 0; i < occupied.length; i++) {
                double[] c = CellGrid.centerOf(i);
                if (c[0] > bounds[0] && c[0] < bounds[3] && c[1] > bounds[1] && c[1] < bounds[4]
                        && c[2] > bounds[2] && c[2] < bounds[5]) occupied[i] = false;
            }
        }
        return occupied;
    }

    public static List<List<CutPlane>> excluded(CellGrid grid, Set<Integer> groups, List<List<CutPlane>> existing) {
        if (smoothHoles(existing).isEmpty()) return excluded(grid, groups);
        boolean[] removed = new boolean[CellGrid.CELLS];
        for (int i = 0; i < removed.length; i++)
            removed[i] = grid.component(i) >= 0 && !groups.contains(grid.component(i));
        return boxes(removed).stream().map(VoxelFragments::planes).toList();
    }

    public static boolean[] latticeMask(List<List<CutPlane>> excluded) {
        List<List<CutPlane>> lattice=new ArrayList<>();
        for(List<CutPlane> region:excluded) if(latticeBox(region)!=null) lattice.add(region);
        return mask(lattice);
    }

    public static List<List<CutPlane>> smoothHoles(List<List<CutPlane>> excluded) {
        return excluded.stream().filter(region->latticeBox(region)==null).toList();
    }

    private VoxelFragments() {}
}
