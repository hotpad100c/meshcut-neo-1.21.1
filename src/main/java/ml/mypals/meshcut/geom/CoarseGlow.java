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
        List<List<CutPlane>> bases = shape.stream().map(VoxelFragments::planes).toList();

        // Calculate centroid and bounding box of kept voxels
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
        // Holes that could potentially carve into the cut plane from inside the kept material
        List<List<CutPlane>> holes = new ArrayList<>();
        if (mask != null) {
            holes.addAll(smoothHoles);
        } else if (!excluded.isEmpty()) {
            holes.addAll(excluded);
        }

        for (CutPlane cut : cuts) {
            if (seen.stream().anyMatch(c -> identical(c, cut))) continue;
            seen.add(cut);

            // Check if this cut is a side wall of a smooth hole (e.g. laser capsule)
            List<CutPlane> holeBounds = null;
            CutPlane holeOutward = null;
            for (List<CutPlane> hole : smoothHoles) {
                for (int j = 0; j < hole.size(); j++) {
                    CutPlane hp = hole.get(j);
                    if (cut.sameAs(hp)) {
                        holeOutward = hp.flipped();
                        holeBounds = new ArrayList<>(hole);
                        holeBounds.remove(j);
                        break;
                    }
                }
                if (holeBounds != null) break;
            }

            float[] polygon = null;
            if (holeBounds == null) {
                for (Patch patch : patches) if (cut.sameAs(patch.plane())) { polygon=patch.polygon(); break; }
            }
            if (polygon==null) polygon=Patch.full(cut).polygon();
            if (polygon==null || polygon.length<9) continue;

            // 1. Determine unique outward normal (pointing towards removed / air side)
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

            // 2. Add single outward-facing polygon (never add opposite inner face)
            List<MeshPoly> section = new ArrayList<>();
            add(section, polygon, outward, uv, sprite);

            // 3. Clip against base bounding shapes (e.g. unit cube or model boxes)
            for (List<CutPlane> base : bases) {
                section = MeshClipper.clip(section, base);
            }

            // 4. If this is a hole wall, clip against the other planes bounding that hole
            if (holeBounds != null) {
                section = MeshClipper.clip(section, holeBounds);
            }

            // 5. Clip against bounding box of remaining fragment to never spill into empty space
            if (keptBoxPlanes != null) {
                section = MeshClipper.clip(section, keptBoxPlanes);
            }

            // 6. Clip against other material planes (excluding self to prevent float self-erasure)
            CutPlane finalOutward = outward;
            List<CutPlane> others = material.stream().filter(p -> !finalOutward.sameAs(p)).toList();
            if (!others.isEmpty()) {
                section = MeshClipper.clip(section, others);
            }

            // 7. Subtract other holes that genuinely intrude into the kept material
            for (List<CutPlane> hole : holes) {
                if (section.isEmpty()) break;
                if (holeBounds != null && hole.stream().anyMatch(cut::sameAs)) continue;
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
