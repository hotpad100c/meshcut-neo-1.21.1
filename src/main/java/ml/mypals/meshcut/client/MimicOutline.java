package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.block.MimicShapes;
import ml.mypals.meshcut.client.render.MimicMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class MimicOutline {
    private record Cached(int version, List<double[]> edges) {}

    private static final Map<MimicBlockEntity, Cached> CACHE = new WeakHashMap<>();

    @SubscribeEvent
    public static void onHighlight(RenderHighlightEvent.Block event) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockPos pos = event.getTarget().getBlockPos();
        if (minecraft.level == null || !(minecraft.level.getBlockEntity(pos) instanceof MimicBlockEntity be)) {
            return;
        }
        Cached cached = CACHE.get(be);
        if (cached == null || cached.version() != be.getVersion()) {
            List<double[]> edges = MimicMesh.outline(be.renderData(), be.cutBoxes());
            cached = new Cached(be.getVersion(), edges);
            CACHE.put(be, cached);
        }
        event.setCanceled(true);
        Vec3 camera = event.getCamera().getPosition();
        double ox = pos.getX() - camera.x, oy = pos.getY() - camera.y, oz = pos.getZ() - camera.z;
        VertexConsumer lines = event.getMultiBufferSource().getBuffer(RenderType.lines());
        PoseStack.Pose pose = event.getPoseStack().last();
        for (double[] e : cached.edges()) {
            float dx = (float) (e[3] - e[0]), dy = (float) (e[4] - e[1]), dz = (float) (e[5] - e[2]);
            float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= l;
            dy /= l;
            dz /= l;
            lines.addVertex(pose, (float) (ox + e[0]), (float) (oy + e[1]), (float) (oz + e[2]))
                    .setColor(0f, 0f, 0f, 0.4f).setNormal(pose, dx, dy, dz);
            lines.addVertex(pose, (float) (ox + e[3]), (float) (oy + e[4]), (float) (oz + e[5]))
                    .setColor(0f, 0f, 0f, 0.4f).setNormal(pose, dx, dy, dz);
        }
    }

    private MimicOutline() {}
}
