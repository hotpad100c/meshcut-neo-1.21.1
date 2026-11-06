package ml.mypals.meshcut.cut;

public enum CapMode {
    PLAIN,
    LAVA;

    public static CapMode byOrdinal(int ordinal) {
        CapMode[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : LAVA;
    }
}
