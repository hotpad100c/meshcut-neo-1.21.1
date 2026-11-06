package ml.mypals.meshcut;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class MeshCutClientConfig {
    public enum SaberActivation { HOLD, TOGGLE }
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.EnumValue<SaberActivation> SABER_ACTIVATION;
    public static final ModConfigSpec.BooleanValue VBO;
    static {
        var builder = new ModConfigSpec.Builder();
        builder.translation("meshcut.config.lightsaber").push("lightsaber");
        SABER_ACTIVATION = builder.comment("HOLD: hold use to keep the blade lit. TOGGLE: click use to switch on/off.")
                .translation("meshcut.config.lightsaber.activation")
                .defineEnum("activation", SaberActivation.TOGGLE);
        builder.pop().translation("meshcut.config.rendering").push("rendering");
        VBO = builder.comment("Cache cut entity and special block-entity model geometry in GPU vertex buffers. Cut glow always uses shared batching.")
                .translation("meshcut.config.rendering.vbo")
                .define("vbo", true);
        builder.pop();
        SPEC = builder.build();
    }
    public static boolean toggleSaber() {
        return SABER_ACTIVATION.get() == SaberActivation.TOGGLE;
    }
    private MeshCutClientConfig() {}
}
