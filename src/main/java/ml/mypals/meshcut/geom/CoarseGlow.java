package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;
import java.util.ArrayList;
import java.util.List;

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
        List<List<CutPlane>> bases = shape.stream().map(VoxelFragments::planes).toList();

        double[] centroid = null;
        List<CutPlane> keptBoxPlanes = null;
        if (mask != null) {
            double cx = 0, cy = 0, cz = 0;
            int count = 0;
            int minX = CellGrid.N, maxX = 0;
            int minY = CellGrid.N, maxY = 0;
            int minZ = CellGrid.N, maxZ = 0;
            for (int x = 0; x < CellGrid.N; x++) {
                for (int y = 0; y < CellGrid.N; y++) {
                    for (int z = 0; z < CellGrid.N; z++) {
                        int idx = (x * CellGrid.N + y) * CellGrid.N + z;
                        if (mask[idx]) {
                            cx += (x + 0.5) / CellGrid.N;
                            cy += (y + 0.5) / CellGrid.N;
                            cz += (z + 0.5) / CellGrid.N;
                            count++;
                            if (x < minX) minX = x;
                            if (x + 1 > maxX) maxX = x + 1;
                            if (y < minY) minY = y;
                            if (y + 1 > maxY) maxY = y + 1;
                            if (z < minZ) minZ = z;
                            if (z + 1 > maxZ) maxZ = z + 1;
                        }
                    }
                }
            }
            if (count > 0) {
                centroid = new double[]{cx / count, cy / count, cz / count};
                keptBoxPlanes = VoxelFragments.planes(new double[]{
                        minX / (double) CellGrid.N, minY / (double) CellGrid.N, minZ / (double) CellGrid.N,
                        maxX / (double) CellGrid.N, maxY / (double) CellGrid.N, maxZ / (double) CellGrid.N
                });
            }
        }

        List<List<CutPlane>> smoothHoles = VoxelFragments.smoothHoles(excluded);
        List<List<CutPlane>> holes = new ArrayList<>();
        if (mask != null) {
            holes.addAll(smoothHoles);
        } else if (!excluded.isEmpty()) {
            holes.addAll(excluded);
        }

        PlaneIndex<Object[]> wallOf = new PlaneIndex<>();
        for (List<CutPlane> hole : smoothHoles) {
            for (int j = 0; j < hole.size(); j++) wallOf.put(hole.get(j), new Object[]{hole, j});
        }
        PlaneIndex<List<CutPlane>> holesWith = new PlaneIndex<>();
        for (List<CutPlane> hole : holes) {
            for (CutPlane p : hole) holesWith.put(p, hole);
        }
        java.util.Map<Long, List<CutPlane>> done = new java.util.HashMap<>();

        for (CutPlane cut : cuts) {
            long sig = Math.round(cut.nx() * 100) * 1000003L + Math.round(cut.ny() * 100) * 1009L + Math.round(cut.nz() * 100);
            List<CutPlane> same = done.computeIfAbsent(sig, k -> new ArrayList<>(2));
            if (same.stream().anyMatch(c -> identical(c, cut))) continue;
            same.add(cut);

            List<CutPlane> holeBounds = null;
            CutPlane holeOutward = null;
            Object[] wall = wallOf.find(cut);
            if (wall != null) {
                @SuppressWarnings("unchecked") List<CutPlane> hole = (List<CutPlane>) wall[0];
                int j = (Integer) wall[1];
                holeOutward = hole.get(j).flipped();
                holeBounds = new ArrayList<>(hole);
                holeBounds.remove(j);
            }

            float[] polygon = null;
            if (holeBounds == null) {
                for (Patch patch : patches) if (cut.sameAs(patch.plane())) { polygon=patch.polygon(); break; }
            }
            if (polygon==null) polygon=Patch.full(cut).polygon();
            if (polygon==null || polygon.length<9) continue;

            CutPlane outward = holeOutward;
            if (outward == null) {
                for (CutPlane plane : material) {
                    if (cut.sameAs(plane)) {
                        outward = plane;
                        break;
                    }
                }
            }
            if (outward == null) {
                if (centroid != null) {
                    double dist = cut.signedDistance(centroid[0], centroid[1], centroid[2]);
                    outward = dist >= 0 ? cut : cut.flipped();
                } else {
                    outward = cut;
                }
            }

            List<MeshPoly> section = new ArrayList<>();
            add(section, polygon, outward, uv, sprite);

            for (List<CutPlane> base : bases) {
                section = MeshClipper.clip(section, base);
            }

            if (holeBounds != null) {
                section = MeshClipper.clip(section, holeBounds);
            }

            if (keptBoxPlanes != null) {
                section = MeshClipper.clip(section, keptBoxPlanes);
            }

            CutPlane finalOutward = outward;
            List<CutPlane> others = material.stream().filter(p -> !finalOutward.sameAs(p)).toList();
            if (!others.isEmpty()) {
                section = MeshClipper.clip(section, others);
            }

            List<List<CutPlane>> ownHoles = holeBounds != null ? holesWith.findAll(cut) : List.of();
            for (List<CutPlane> hole : holes) {
                if (section.isEmpty()) break;
                if (ownHoles.stream().anyMatch(own -> own == hole)) continue;
                section = MeshClipper.subtract(section, hole);
            }

            faces.addAll(section);
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

    private static boolean identical(CutPlane a, CutPlane b) {
        return a.nx() * b.nx() + a.ny() * b.ny() + a.nz() * b.nz() > 0.9999 && Math.abs(a.d() - b.d()) < 1e-4;
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
