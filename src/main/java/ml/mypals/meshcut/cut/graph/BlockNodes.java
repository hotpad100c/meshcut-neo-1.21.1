package ml.mypals.meshcut.cut.graph;

import ml.mypals.meshcut.geom.CellGrid;

import java.util.Arrays;

public final class BlockNodes {
    private static final long ALL = -1L;
    private static final long[] FULL_FACES = {ALL, ALL, ALL, ALL, ALL, ALL};

    public static final BlockNodes PLAIN = new BlockNodes(false, new long[][]{FULL_FACES});

    private final boolean mimic;
    private final long[][] masks;

    private BlockNodes(boolean mimic, long[][] masks) {
        this.mimic = mimic;
        this.masks = masks;
    }

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

    public static BlockNodes mimic(long[][] masks) {
        return new BlockNodes(true, masks);
    }

    public boolean isMimic() {
        return mimic;
    }

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
