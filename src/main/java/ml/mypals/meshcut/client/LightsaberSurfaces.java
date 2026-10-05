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

    private static final java.util.Set<MimicRenderData> BUILDING = new java.util.HashSet<>();
    private static long generation;

    static List<MeshPoly> at(ClientLevel level, BlockPos pos) {
        return prepare(level, pos);
    }

    private static List<MeshPoly> prepare(ClientLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (state.isAir()) return List.of();
        if (level.getBlockEntity(pos) instanceof MimicBlockEntity mimic) {
            var data = mimic.renderData();
            List<MeshPoly> cached = CUT_MODELS.get(data);
            if (cached != null) return cached;
            // Model capture must stay on the client thread. Geometry work must not stall contact ticks.
            if (BUILDING.size() >= 16 || !BUILDING.add(data)) return List.of();
            long epoch = generation;
            try {
                ModelCapturer.CapturedMesh source = data.entity() == null
                        ? ModelCapturer.capture(data.target(), data.beTag()) : MimicMesh.entityPart(data.entity());
                MimicMesh.async(() -> {
                    try {
                        List<MeshPoly> done = MimicMesh.contactModel(source, data);
                        net.minecraft.client.Minecraft.getInstance().execute(() -> {
                            if (epoch != generation) return;
                            BUILDING.remove(data);
                            CUT_MODELS.put(data, done);
                        });
                    } catch (RuntimeException ex) {
                        net.minecraft.client.Minecraft.getInstance().execute(() -> {
                            if (epoch != generation) return;
                            BUILDING.remove(data);
                            CUT_MODELS.put(data, List.of());
                            ml.mypals.meshcut.MeshCut.LOGGER.error("Preparing saber contact geometry failed", ex);
                        });
                    }
                });
            } catch (RuntimeException ex) {
                BUILDING.remove(data);
                throw ex;
            }
            return List.of();
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

    static void clear() {
        generation++;
        BUILDING.clear();
        CUT_MODELS.clear();
    }

    private LightsaberSurfaces() {}
}
