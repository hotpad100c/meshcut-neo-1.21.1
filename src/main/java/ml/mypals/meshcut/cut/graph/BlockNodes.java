package ml.mypals.meshcut.cut.graph;

import ml.mypals.meshcut.geom.CellGrid;

import java.util.Arrays;

/**
 * What one block position contributes to the connectivity graph: either an ordinary solid block ({@link #PLAIN}) or
 * a mimic, which is a number of <em>fragments</em> (the pieces its cuts have divided it into). Every fragment records,
 * for each of the six faces of the block, which of the 8×8 cells of that face it touches (one bit per cell, the same
 * order on opposite faces), so two neighbouring blocks are joined only where their fragments really meet.
 * <p>
 * Face numbering is {@link CellGrid}'s: {@code axis * 2 + (positive side ? 0 : 1)}.
 */
public final class BlockNodes {
    private static final long ALL = -1L;
    private static final long[] FULL_FACES = {ALL, ALL, ALL, ALL, ALL, ALL};

    /** An ordinary block: one piece that touches every neighbour over the whole of the shared face. */
    public static final BlockNodes PLAIN = new BlockNodes(false, new long[][]{FULL_FACES});

    private final boolean mimic;
    private final long[][] masks;

    private BlockNodes(boolean mimic, long[][] masks) {
        this.mimic = mimic;
        this.masks = masks;
    }

    /**
     * The fragments of a cut block.
     *
     * @param whole the block cannot be split into convex pieces, so all its cell groups count as one fragment
     */
    public static BlockNodes of(CellGrid grid, boolean whole) {
        int groups = grid.componentCount();
        int count = whole ? Math.min(1, groups) : groups;
        long[][] masks = new long[count][6];
        for (int face = 0; face < 6; face++) {
            for (int i = 0; i < CellGrid.N * CellGrid.N; i++) {
                int c = grid.faceComponent(face, i);
                if (c < 0) {
                    continue;
                }
                int fragment = whole ? 0 : c;
                if (fragment < count) {
                    masks[fragment][face] |= 1L << i;
                }
            }
        }
        return new BlockNodes(true, masks);
    }

    /** A mimic with the given fragments (for tests). */
    public static BlockNodes mimic(long[][] masks) {
        return new BlockNodes(true, masks);
    }

    public boolean isMimic() {
        return mimic;
    }

    /** Number of pieces this block is made of. */
    public int count() {
        return masks.length;
    }

    public long mask(int fragment, int face) {
        return masks[fragment][face];
    }

    public boolean sameAs(BlockNodes other) {
        return this == other || (other != null && mimic == other.mimic && Arrays.deepEquals(masks, other.masks));
    }

    @Override
    public String toString() {
        return mimic ? "Mimic" + masks.length : "Plain";
    }
}
