package ml.mypals.meshcut.cut;

/** How the flat face exposed by a cut is rendered. */
public enum CapMode {
    /** Textured with the imitated block's own material. */
    PLAIN,
    /** Block material with a glowing lava layer on top. */
    LAVA;

    public static CapMode byOrdinal(int ordinal) {
        CapMode[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : LAVA;
    }
}
