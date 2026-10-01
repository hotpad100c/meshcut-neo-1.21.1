package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns a group of cells of a {@link CellGrid} into a piece that can really be separated: the block clipped by some
 * half-spaces. Pieces are only ever convex, so a group is accepted only if the half-spaces of the scratches that
 * bound it, applied to the block, give (about) the same shape as the cells do.
 */
public final class PieceFit {
    /** A scratch bounds a group if some of the group's cells are this close to it... */
    private static final double TOUCH_DISTANCE = 0.2;
    /** ...in front of the part of it that was actually cut (plus this much margin). */
    private static final double TOUCH_MARGIN = 0.15;
    /** A scratch is on one side of a group if at most this fraction of the group's cells are on the other. */
    private static final double SIDE_PURITY = 0.04;
    private static final double VOLUME_TOLERANCE = 0.2;
    private static final double MIN_VOLUME_TOLERANCE = 0.03;
    /** Of the cells that are not part of the group, at most this fraction may lie inside the fitted shape. */
    private static final double MAX_FOREIGN_FRACTION = 0.1;
    /** Cells this close to a plane are on it: they belong to neither side, so they say nothing about the shape. */
    private static final double ON_PLANE = 1e-9;

    /**
     * @param group     the groups of {@code grid} the piece is made of
     * @return the half-spaces whose intersection with the block is the piece (the block's real cuts included), or
     *         {@code null} if the group does not have a convex shape
     */
    public static List<CutPlane> fit(CellGrid grid, Set<Integer> group, List<CutPlane> real, List<Patch> patches) {
        List<Integer> cells = new ArrayList<>();
        List<Integer> foreign = new ArrayList<>();
        for (int i = 0; i < CellGrid.CELLS; i++) {
            int c = grid.component(i);
            if (c >= 0) {
                (group.contains(c) ? cells : foreign).add(i);
            }
        }
        if (cells.isEmpty()) {
            return null;
        }
        if (foreign.isEmpty()) {
            return new ArrayList<>(real); // all of what is left of the block
        }

        List<CutPlane> planes = new ArrayList<>(real);
        for (Patch patch : patches) {
            CutPlane p = patch.plane();
            int positive = 0, negative = 0;
            boolean touches = false;
            for (int cell : cells) {
                double[] c = CellGrid.centerOf(cell);
                double dist = p.signedDistance(c[0], c[1], c[2]);
                if (dist > ON_PLANE) {
                    positive++;
                } else if (dist < -ON_PLANE) {
                    negative++;
                }
                if (!touches && Math.abs(dist) <= TOUCH_DISTANCE && (patch.through() || patch.containsProjected(c[0], c[1], c[2], TOUCH_MARGIN))) {
                    touches = true;
                }
            }
            if (!touches) {
                continue;
            }
            if (negative <= SIDE_PURITY * cells.size()) {
                planes.add(p);
            } else if (positive <= SIDE_PURITY * cells.size()) {
                planes.add(p.flipped());
            }
            // otherwise the scratch runs through the group: it does not bound it
        }

        double cellVolume = cells.size() / (double) CellGrid.CELLS;
        double volume = Polytope.volume(planes);
        if (Math.abs(volume - cellVolume) > Math.max(MIN_VOLUME_TOLERANCE, VOLUME_TOLERANCE * cellVolume)) {
            return null;
        }
        int strays = 0;
        for (int cell : foreign) {
            double[] c = CellGrid.centerOf(cell);
            boolean inside = true;
            for (CutPlane p : planes) {
                if (p.signedDistance(c[0], c[1], c[2]) <= ON_PLANE) { // on a plane or beyond: not inside
                    inside = false;
                    break;
                }
            }
            if (inside) {
                strays++;
            }
        }
        return strays > Math.max(2, MAX_FOREIGN_FRACTION * foreign.size()) ? null : planes;
    }

    /**
     * The scratches a piece keeps: those that did not bound it (they run through it or end inside it) and still touch
     * it. A scratch that bounded it became one of its faces.
     *
     * @param bounding the extra half-spaces {@link #fit} added to the block's real cuts
     */
    public static List<Patch> remaining(CellGrid grid, Set<Integer> group, List<Patch> patches, List<CutPlane> bounding) {
        List<Patch> kept = new ArrayList<>();
        for (Patch patch : patches) {
            if (isBounding(patch.plane(), bounding)) {
                continue;
            }
            for (int i = 0; i < CellGrid.CELLS; i++) {
                if (grid.component(i) >= 0 && group.contains(grid.component(i))) {
                    double[] c = CellGrid.centerOf(i);
                    if (Math.abs(patch.plane().signedDistance(c[0], c[1], c[2])) <= 0.25
                            && (patch.through() || patch.containsProjected(c[0], c[1], c[2], 0.2))) {
                        kept.add(patch);
                        break;
                    }
                }
            }
        }
        return kept;
    }

    private static boolean isBounding(CutPlane plane, List<CutPlane> bounding) {
        for (CutPlane b : bounding) {
            double dot = plane.nx() * b.nx() + plane.ny() * b.ny() + plane.nz() * b.nz();
            double offset = dot >= 0 ? Math.abs(plane.d() - b.d()) : Math.abs(plane.d() + b.d());
            if (Math.abs(dot) > 0.9999 && offset < 1e-6) {
                return true;
            }
        }
        return false;
    }

    private PieceFit() {}
}
