package ml.mypals.meshcut;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class MeshCutConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue DEBRIS_VOLUME;
    public static final ModConfigSpec.IntValue DEBRIS_SECONDS;
    public static final ModConfigSpec.IntValue SURGE_PIECES;
    public static final ModConfigSpec.IntValue SURGE_SECONDS;
    public static final ModConfigSpec.DoubleValue SURGE_MAX_VOLUME;
    public static final ModConfigSpec.DoubleValue GRAB_MAX_FORCE;

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
        builder.push("surge");
        SURGE_PIECES = builder
                .comment("When more pieces than this are made within the window, the smallest are burnt up (0 keeps all).")
                .defineInRange("maxPieces", 20, 0, 100000);
        SURGE_SECONDS = builder
                .comment("The window, in seconds, in which the pieces are counted.")
                .defineInRange("windowSeconds", 5, 1, 600);
        SURGE_MAX_VOLUME = builder
                .comment("A piece with at least this much material, in blocks, is never burnt up.")
                .defineInRange("maxVolume", 8.0, 0.0, 4096.0);
        builder.pop();
        builder.push("grab");
        GRAB_MAX_FORCE = builder
                .comment("The most force a player holding a cut piece by hand can pull it with (as Create Aeronautics' handles).")
                .defineInRange("maxForce", 120.0, 0.0, 1.0E6);
        builder.pop();
        SPEC = builder.build();
    }

    private MeshCutConfig() {}
}
