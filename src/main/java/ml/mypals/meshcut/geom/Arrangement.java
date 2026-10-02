package ml.mypals.meshcut.geom;

import ml.mypals.meshcut.cut.CutPlane;

import java.util.ArrayList;
import java.util.List;

/**
 * The exact convex pieces a block is divided into by the cuts that go right through it: the block (within its real
 * cuts) divided by each such cut in turn, keeping every side that has a real amount of volume.
 * Each piece is a list of half-spaces that begins with the real cuts, so what a piece adds to them is what bounds it
 * against the others.
 */
public final class Arrangement {
    private static final double MIN_VOLUME = 0.003;
    private static final int MAX_PIECES = 64;

    public static List<List<CutPlane>> pieces(List<CutPlane> real, List<Patch> patches) {
        List<List<CutPlane>> pieces = new ArrayList<>();
        pieces.add(new ArrayList<>(real));
        for (Patch patch : patches) {
            if (!patch.through()) {
                continue;
            }
            List<List<CutPlane>> next = new ArrayList<>();
            for (List<CutPlane> piece : pieces) {
                List<CutPlane> keep = new ArrayList<>(piece), other = new ArrayList<>(piece);
                keep.add(patch.plane());
                other.add(patch.plane().flipped());
                boolean a = Polytope.volume(keep) >= MIN_VOLUME, b = Polytope.volume(other) >= MIN_VOLUME;
                if (a && b) {
                    next.add(keep);
                    next.add(other);
                } else {
                    next.add(piece); // this cut does not divide the piece
                }
            }
            if (next.size() > MAX_PIECES) {
                break;
            }
            pieces = next;
        }
        return pieces;
    }

    private Arrangement() {}
}
