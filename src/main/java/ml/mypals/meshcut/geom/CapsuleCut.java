package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;
import java.util.ArrayList;
import java.util.List;

public final class CapsuleCut {
    public record Prepared(List<CutPlane> hole, List<List<CutPlane>> holes, CellGrid grid) {}

    public static Prepared prepare(List<CutPlane> real, List<List<CutPlane>> excluded, List<Patch> patches,
                                   List<double[]> shape, HoleReducer.Reduced reduced) {
        if(reduced==null)return null;
        List<CutPlane> hole=reduced.planes();
        for(var old:excluded) if(CutPlane.sameRegion(old,hole)||HoleReducer.contains(old,reduced.vertices()))return null;
        boolean intersects=false;
        for(var box:shape) {
            var material=new ArrayList<>(real);
            material.addAll(hole);material.addAll(VoxelFragments.planes(box));
            if(Polytope.volume(material)>1e-8){intersects=true;break;}
        }
        if(!intersects)return null;
        var holes=new ArrayList<>(excluded);holes.add(List.copyOf(hole));
        List<List<CutPlane>> frozen=List.copyOf(holes);
        return new Prepared(hole,frozen,CellGrid.of(real,frozen,patches,shape));
    }
    private CapsuleCut(){}
}
