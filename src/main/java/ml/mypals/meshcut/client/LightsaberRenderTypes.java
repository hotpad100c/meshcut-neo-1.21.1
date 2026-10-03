package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;
import java.util.function.Function;

/** Unlit translucent beam geometry tested against the main scene's depth, without writing glow depth. */
final class LightsaberRenderTypes extends RenderStateShard {
    static final Function<ResourceLocation, RenderType> FADED_HANDS = Util.memoize(texture -> RenderType.create(
            "meshcut_faded_hands", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536, false, true,
            RenderType.CompositeState.builder().setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                    .setTextureState(new TextureStateShard(texture, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP).setOverlayState(OVERLAY)
                    .setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_WRITE)
                    .setOutputState(MAIN_TARGET).createCompositeState(false)));
    static final RenderType SCORCH = RenderType.create("meshcut_lightsaber_scorch", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.TRIANGLES, 1536, false, false, RenderType.CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setLayeringState(POLYGON_OFFSET_LAYERING) // marks lie on surfaces: pull them towards the camera
                    .setWriteMaskState(COLOR_WRITE)
                    .setOutputState(MAIN_TARGET)
                    .createCompositeState(false));
    static final RenderType BEAM = RenderType.create("meshcut_lightsaber_beam", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 1536, false, true, RenderType.CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(COLOR_WRITE)
                    .setOutputState(MAIN_TARGET)
                    .createCompositeState(false));
    // A separate buffer lets the caller flush the trail before the bright blade.
    static final RenderType TRAIL = RenderType.create("meshcut_lightsaber_trail", DefaultVertexFormat.POSITION_COLOR,
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
