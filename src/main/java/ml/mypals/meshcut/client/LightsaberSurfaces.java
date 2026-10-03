package ml.mypals.meshcut.client;

import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.block.MimicRenderData;
import ml.mypals.meshcut.client.capture.ModelCapturer;
import ml.mypals.meshcut.client.render.MimicMesh;
import ml.mypals.meshcut.geom.MeshPoly;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Uses the same captured/clipped geometry as the mod's visible block models. */
final class LightsaberSurfaces {
    private static final Map<MimicRenderData, List<MeshPoly>> CUT_MODELS = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<MimicRenderData, List<MeshPoly>> eldest) {
            return size() > 256;
        }
    };

    static List<MeshPoly> at(ClientLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (state.isAir()) return List.of();
        if (level.getBlockEntity(pos) instanceof MimicBlockEntity mimic) {
            var data = mimic.renderData();
            return CUT_MODELS.computeIfAbsent(data, LightsaberSurfaces::cutModel);
        }
        if (ModelCapturer.hasBlockEntityRenderer(state)) {
            var be = level.getBlockEntity(pos);
            return ModelCapturer.capture(state, be == null ? null : be.saveWithoutMetadata(level.registryAccess()))
                    .byType().values().stream().flatMap(List::stream).toList();
        }
        return ModelCapturer.captureModel(state).byChunkType().values().stream().flatMap(List::stream).toList();
    }

    static Object version(ClientLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof MimicBlockEntity mimic ? mimic.renderData() : level.getBlockState(pos);
    }

    private static List<MeshPoly> cutModel(MimicRenderData data) {
        ModelCapturer.CapturedMesh source = data.entity() == null
                ? ModelCapturer.capture(data.target(), data.beTag()) : MimicMesh.entityPart(data.entity());
        return MimicMesh.cutModel(source.byType(), source.opaque(), source.capUv(), null, data, RenderType.solid())
                .values().stream().flatMap(List::stream).toList();
    }

    static void clear() { CUT_MODELS.clear(); }

    private LightsaberSurfaces() {}
}
