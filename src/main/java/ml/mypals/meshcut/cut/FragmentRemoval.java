package ml.mypals.meshcut.cut;

import ml.mypals.meshcut.geom.CellGrid;
import ml.mypals.meshcut.geom.Patch;
import ml.mypals.meshcut.geom.VoxelFragments;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What a cut block becomes when one of its fragments is taken out. Pure geometry, no world access. */
public final class FragmentRemoval {
    /** The block's cut-out parts and scratches after the fragment is gone (its real cuts do not change). */
    public record Result(List<List<CutPlane>> excluded, List<Patch> patches) {}

    /**
     * @return {@code null} if the fragment does not exist
     */
    public static Result remove(CellGrid grid, List<CutPlane> real, List<List<CutPlane>> excluded, List<Patch> patches,
                                int fragment) {
        if(fragment<0 || fragment>=grid.componentCount()) return null;
        List<List<CutPlane>> holes=new ArrayList<>(excluded);
        Set<Integer> staying = new HashSet<>();
        for (int c = 0; c < grid.componentCount(); c++) {
            if (c != fragment) {
                staying.add(c);
            }
        }
        // the scratches that bounded the fragment stay if they still divide what is left
        holes.addAll(VoxelFragments.excluded(grid,staying,excluded));
        return new Result(VoxelFragments.compact(holes),List.copyOf(patches));
    }

    private FragmentRemoval() {}
}
