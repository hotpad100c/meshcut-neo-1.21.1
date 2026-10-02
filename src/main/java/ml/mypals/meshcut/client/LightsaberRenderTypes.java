package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/** Unlit translucent beam geometry tested against the main scene's depth, without writing glow depth. */
final class LightsaberRenderTypes extends RenderStateShard {
    static final RenderType BEAM = RenderType.create("meshcut_lightsaber_beam", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 1536, false, true, RenderType.CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(COLOR_WRITE)
                    .setOutputState(MAIN_TARGET)
                    .createCompositeState(false));

    private LightsaberRenderTypes() {
        super("meshcut_lightsaber", () -> {}, () -> {});
    }
}
