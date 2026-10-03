package ml.mypals.meshcut;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server settings: what becomes of the small pieces a cut leaves behind. */
public final class MeshCutConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue DEBRIS_VOLUME;
    public static final ModConfigSpec.IntValue DEBRIS_SECONDS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("debris");
        DEBRIS_VOLUME = builder
                .comment("A piece cut off with less material than this, in blocks, disappears after a while (0 keeps all).")
                .defineInRange("maxVolume", 0.5, 0.0, 64.0);
        DEBRIS_SECONDS = builder
                .comment("How long such a piece lasts, in seconds.")
                .defineInRange("lifetimeSeconds", 30, 1, 3600);
        builder.pop();
        SPEC = builder.build();
    }

    private MeshCutConfig() {}
}
