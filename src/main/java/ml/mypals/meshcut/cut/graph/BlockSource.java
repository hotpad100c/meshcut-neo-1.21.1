package ml.mypals.meshcut.cut.graph;

public interface BlockSource {
    BlockNodes at(int x, int y, int z);

    boolean loaded(int x, int y, int z);
}
