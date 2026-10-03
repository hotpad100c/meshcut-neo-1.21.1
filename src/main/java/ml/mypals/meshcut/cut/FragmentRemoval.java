package ml.mypals.meshcut.cut;

import ml.mypals.meshcut.geom.CellGrid;
import ml.mypals.meshcut.geom.Patch;
import ml.mypals.meshcut.geom.PieceFit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What a cut block becomes when one of its fragments is taken out. Pure geometry, no world access. */
public final class FragmentRemoval {
    /** The block's cut-out parts and scratches after the fragment is gone (its real cuts do not change). */
    public record Result(List<List<CutPlane>> excluded, List<Patch> patches) {}

    /**
     * @return {@code null} if the fragment is not a convex shape that can be cut out on its own
     */
    public static Result remove(CellGrid grid, List<CutPlane> real, List<List<CutPlane>> excluded, List<Patch> patches,
                                int fragment) {
        List<CutPlane> fit = PieceFit.fit(grid, Set.of(fragment), real, patches);
        if (fit == null || fit.size() <= real.size()) {
            return null;
        }
        List<CutPlane> hole = new ArrayList<>(fit.subList(real.size(), fit.size()));
        List<List<CutPlane>> holes = new ArrayList<>(excluded);
        if (holes.stream().noneMatch(h -> CutPlane.sameRegion(h, hole))) {
            holes.add(hole);
        }
        Set<Integer> staying = new HashSet<>();
        for (int c = 0; c < grid.componentCount(); c++) {
            if (c != fragment) {
                staying.add(c);
            }
        }
        // the scratches that bounded the fragment stay if they still divide what is left
        return new Result(holes, PieceFit.remainingStaying(grid, staying, patches, hole));
    }

    private FragmentRemoval() {}
}
