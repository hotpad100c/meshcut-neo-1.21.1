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
    /** Cells nearer than this to a plane (more than half a cell) may be on either side of it. */
    private static final double AMBIGUOUS_DISTANCE = 0.1;
    /** At most this fraction of the group's size may be cells the fitted shape gets wrong. */
    private static final double MAX_WRONG_FRACTION = 0.01;
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

        // a piece made only by cuts that go right through is known exactly
        Set<Integer> members = new java.util.HashSet<>(cells);
        for (List<CutPlane> piece : Arrangement.pieces(real, patches)) {
            if (piece.size() > real.size() && wrongCells(grid, members, piece) <= Math.max(1, MAX_WRONG_FRACTION * cells.size())) {
                return piece;
            }
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

        // Does the shape the half-spaces make agree with the cells? Cells close to one of the planes may fall on
        // either side (a plane through cell centres puts them anywhere), so they say nothing; the others must agree.
        int wrong = wrongCells(grid, new java.util.HashSet<>(cells), planes);
        return wrong > Math.max(2, MAX_WRONG_FRACTION * cells.size()) ? null : planes;
    }

    /** How many cells the shape made by the half-spaces gets wrong, leaving out those near a plane. */
    private static int wrongCells(CellGrid grid, Set<Integer> members, List<CutPlane> planes) {
        int wrong = 0;
        for (int cell = 0; cell < CellGrid.CELLS; cell++) {
            if (grid.component(cell) < 0) {
                continue;
            }
            double[] c = CellGrid.centerOf(cell);
            boolean inside = true;
            boolean ambiguous = false;
            for (CutPlane p : planes) {
                double dist = p.signedDistance(c[0], c[1], c[2]);
                if (Math.abs(dist) < AMBIGUOUS_DISTANCE) {
                    ambiguous = true;
                }
                if (dist < 0) {
                    inside = false;
                }
            }
            if (!ambiguous && inside != members.contains(cell)) {
                wrong++;
            }
        }
        return wrong;
    }

    /**
     * The scratches a piece keeps: those that still touch it. For a piece that leaves, the scratches that bounded it
     * became its faces ({@code bounding}); for a piece that stays, nothing is passed here: the scratches that bounded
     * what left still divide what is left, and without them its fragments would join up.
     *
     * @param bounding the extra half-spaces {@link #fit} added to the block's real cuts, or empty
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
