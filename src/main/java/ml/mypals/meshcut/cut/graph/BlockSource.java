package ml.mypals.meshcut.cut.graph;

/** Where the graph reads the world from. */
public interface BlockSource {
    /** What the block at the position contributes, or {@code null} if it is empty (air, fluid). */
    BlockNodes at(int x, int y, int z);

    /** Whether the block position may be looked at (its chunk is loaded). */
    boolean loaded(int x, int y, int z);
}
