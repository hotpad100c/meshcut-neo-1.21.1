package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/** What is drawn into the glow buffer: unlit, added up, tested against the scene depth, which it never writes. */
final class BloomRenderTypes extends RenderStateShard {
    private static final OutputStateShard GLOW_OUTPUT = new OutputStateShard("meshcut_glow",
            Bloom::bindGlow, Bloom::bindMain);

    static final RenderType QUADS = type("meshcut_glow_quads", VertexFormat.Mode.QUADS);
    static final RenderType TRIANGLES = type("meshcut_glow_triangles", VertexFormat.Mode.TRIANGLES);

    private static RenderType type(String name, VertexFormat.Mode mode) {
        return RenderType.create(name, DefaultVertexFormat.POSITION_COLOR, mode, 1536, false, true,
                RenderType.CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(ADDITIVE_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(COLOR_WRITE)
                        .setOutputState(GLOW_OUTPUT)
                        .createCompositeState(false));
    }

    private BloomRenderTypes() {
        super("meshcut_bloom", () -> {}, () -> {});
    }
}
