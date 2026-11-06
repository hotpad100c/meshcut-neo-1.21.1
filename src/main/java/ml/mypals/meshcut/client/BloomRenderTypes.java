package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

final class BloomRenderTypes extends RenderStateShard {
    private static final OutputStateShard GLOW_OUTPUT = new OutputStateShard("meshcut_glow",
            Bloom::bindGlow, Bloom::bindMain);

    static final RenderType QUADS = type("meshcut_glow_quads", VertexFormat.Mode.QUADS);
    private static final LayeringStateShard CUT_LAYER = new LayeringStateShard("meshcut_cut_bloom_offset", () -> {
        com.mojang.blaze3d.systems.RenderSystem.enablePolygonOffset();
        com.mojang.blaze3d.systems.RenderSystem.polygonOffset(-1f, -20f);
    }, () -> {
        com.mojang.blaze3d.systems.RenderSystem.polygonOffset(0f, 0f);
        com.mojang.blaze3d.systems.RenderSystem.disablePolygonOffset();
    });
    static final RenderType CUT_TRIANGLES = type("meshcut_cut_glow_triangles", VertexFormat.Mode.TRIANGLES, true);
    static final RenderType TRIANGLES = type("meshcut_glow_triangles", VertexFormat.Mode.TRIANGLES);

    private static RenderType type(String name, VertexFormat.Mode mode) {
        return type(name, mode, false);
    }

    private static RenderType type(String name, VertexFormat.Mode mode, boolean cut) {
        return RenderType.create(name, DefaultVertexFormat.POSITION_COLOR, mode, 1536, false, true,
                RenderType.CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(ADDITIVE_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setLayeringState(cut ? CUT_LAYER : NO_LAYERING)
                        .setWriteMaskState(COLOR_WRITE)
                        .setOutputState(GLOW_OUTPUT)
                        .createCompositeState(false));
    }

    private BloomRenderTypes() {
        super("meshcut_bloom", () -> {}, () -> {});
    }
}
