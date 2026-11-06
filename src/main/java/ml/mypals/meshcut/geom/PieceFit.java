package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class PieceFit {
    private static final double TOUCH_DISTANCE = 0.2;
    private static final double TOUCH_MARGIN = 0.15;
    private static final double SIDE_PURITY = 0.04;
    private static final double AMBIGUOUS_DISTANCE = 0.1;
    private static final double MAX_WRONG_FRACTION = 0.01;
    private static final double ON_PLANE = 1e-9;

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
            return new ArrayList<>(real);
        }

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
        }

        int wrong = wrongCells(grid, new java.util.HashSet<>(cells), planes);
        return wrong > Math.max(2, MAX_WRONG_FRACTION * cells.size()) ? null : planes;
    }

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

    public static List<Patch> remainingStaying(CellGrid grid, Set<Integer> group, List<Patch> patches, List<CutPlane> hole) {
        List<Patch> kept = new ArrayList<>();
        for (Patch patch : remaining(grid, group, patches, List.of())) {
            if (isBounding(patch.plane(), hole)) {
                int positive = 0, negative = 0;
                for (int i = 0; i < CellGrid.CELLS; i++) {
                    int c = grid.component(i);
                    if (c >= 0 && group.contains(c)) {
                        double[] x = CellGrid.centerOf(i);
                        double d = patch.plane().signedDistance(x[0], x[1], x[2]);
                        if (d > 0.1) {
                            positive++;
                        } else if (d < -0.1) {
                            negative++;
                        }
                    }
                }
                if (positive == 0 || negative == 0) {
                    continue;
                }
            }
            kept.add(patch);
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
