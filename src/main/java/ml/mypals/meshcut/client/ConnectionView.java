package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.cut.graph.BlockNodes;
import ml.mypals.meshcut.geom.CellGrid;
import ml.mypals.meshcut.geom.FragmentPick;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class ConnectionView extends RenderStateShard {
    private static final RenderType FACES = RenderType.create("meshcut_connections", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 4096, false, true, RenderType.CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER).setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL).setDepthTestState(NO_DEPTH_TEST).setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));
    private record Face(int axis, double depth, double a, double b, double size, boolean contact) {}
    private static final List<Face> faces = new ArrayList<>();
    private static MimicBlockEntity cached;
    private static int version, fragment, refreshTick, lastDrawTick = Integer.MIN_VALUE / 2, neighbours, unknown;

    @SubscribeEvent
    public static void highlight(RenderHighlightEvent.Block event) {
        Minecraft mc = Minecraft.getInstance();
        if (!LightsaberKeys.CONNECTIONS.isDown() || mc.options.hideGui || mc.level == null || mc.player == null
                || mc.screen != null || !(mc.player.getMainHandItem().getItem() instanceof ml.mypals.meshcut.item.LightsaberItem)
                || !(mc.level.getBlockEntity(event.getTarget().getBlockPos()) instanceof MimicBlockEntity be)) return;
        BlockPos pos = be.getBlockPos();
        Vec3 eye = event.getCamera().getPosition();
        Vec3 target = event.getTarget().getLocation();
        Vec3 direction = target.subtract(eye).normalize();
        int selected = FragmentPick.pick(be.grid(), new double[]{eye.x - pos.getX(), eye.y - pos.getY(), eye.z - pos.getZ()},
                new double[]{direction.x, direction.y, direction.z});
        if (selected < 0) return;
        if (cached != be || version != be.getVersion() || fragment != selected || mc.player.tickCount - refreshTick >= 5) {
            rebuild(be, selected);
            cached = be; version = be.getVersion(); fragment = selected; refreshTick = mc.player.tickCount;
        }
        lastDrawTick = mc.player.tickCount;
        VertexConsumer consumer = event.getMultiBufferSource().getBuffer(FACES);
        var pose = event.getPoseStack().last();
        for (Face face : faces) {
            int first = face.axis == 0 ? 1 : 0, second = face.axis == 2 ? 1 : 2;
            for (int corner : new int[]{0, 1, 3, 2}) {
                double[] p = new double[3];
                p[face.axis] = face.depth;
                p[first] = face.a + ((corner & 1) != 0 ? face.size : 0);
                p[second] = face.b + ((corner & 2) != 0 ? face.size : 0);
                consumer.addVertex(pose, (float) (pos.getX() + p[0] - eye.x),
                        (float) (pos.getY() + p[1] - eye.y), (float) (pos.getZ() + p[2] - eye.z))
                        .setColor(face.contact ? 1f : 0.1f, face.contact ? 0.65f : 0.8f,
                                face.contact ? 0.1f : 1f, face.contact ? 0.42f : 0.09f);
            }
        }
    }

    private static void rebuild(MimicBlockEntity be, int selected) {
        faces.clear(); neighbours = unknown = 0;
        CellGrid grid = be.grid();
        for (int i = 0; i < CellGrid.CELLS; i++) {
            if (grid.component(i) != selected) continue;
            int[] xyz = {i / 64, i / 8 % 8, i % 8};
            for (int f = 0; f < 6; f++) {
                int axis = f / 2, sign = f % 2 == 0 ? 1 : -1;
                int[] next = xyz.clone(); next[axis] += sign;
                if (next[axis] >= 0 && next[axis] < 8
                        && grid.component(next[0] * 64 + next[1] * 8 + next[2]) == selected) continue;
                int first = axis == 0 ? 1 : 0, second = axis == 2 ? 1 : 2;
                faces.add(new Face(axis, (xyz[axis] + (sign > 0 ? 1 : 0)) / 8.0,
                        xyz[first] / 8.0, xyz[second] / 8.0, 0.125, false));
            }
        }
        BlockNodes mine = be.nodes();
        for (int f = 0; f < 6; f++) {
            int axis = f / 2, sign = f % 2 == 0 ? 1 : -1;
            BlockPos next = be.getBlockPos().offset(axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0);
            if (!be.getLevel().hasChunkAt(next)) { unknown++; continue; }
            if (dev.ryanhcode.sable.Sable.HELPER.getContaining(be.getLevel(), next)
                    != dev.ryanhcode.sable.Sable.HELPER.getContaining(be.getLevel(), be.getBlockPos())) continue;
            var state = be.getLevel().getBlockState(next);
            if (state.isAir() || state.getBlock() instanceof LiquidBlock) continue;
            BlockNodes other = be.getLevel().getBlockEntity(next) instanceof MimicBlockEntity mimic ? mimic.nodes() : BlockNodes.PLAIN;
            long mask = 0;
            for (int part = 0; part < other.count(); part++) mask |= mine.mask(selected, f) & other.mask(part, f ^ 1);
            if (mask == 0) continue;
            neighbours++;
            for (int cell = 0; cell < 64; cell++) if ((mask & (1L << cell)) != 0)
                faces.add(new Face(axis, sign > 0 ? 1.002 : -0.002, (cell / 8) / 8.0, (cell % 8) / 8.0, 0.125, true));
        }
    }

    @SubscribeEvent
    public static void gui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui || mc.screen != null
                || !(mc.player.getMainHandItem().getItem() instanceof ml.mypals.meshcut.item.LightsaberItem)) {
            cached = null; faces.clear(); lastDrawTick = Integer.MIN_VALUE / 2; return;
        }
        boolean looking = mc.hitResult instanceof BlockHitResult hit
                && mc.level.getBlockEntity(hit.getBlockPos()) instanceof MimicBlockEntity;
        if (!looking) return;
        if (!LightsaberKeys.CONNECTIONS.isDown() || mc.player.tickCount - lastDrawTick > 2) return;
        Component label = Component.translatable(unknown > 0 ? "message.meshcut.connections.unknown" : "message.meshcut.connections.result", neighbours);
        var graphics = event.getGuiGraphics();
        int width = mc.font.width(label), x = (graphics.guiWidth() - width) / 2, y = graphics.guiHeight() - 65;
        graphics.fill(x - 5, y - 4, x + width + 5, y + 13, 0xB0000000);
        graphics.drawString(mc.font, label, x, y, 0xFFFFFF);
    }

    private ConnectionView() { super("meshcut_connections", () -> {}, () -> {}); }
}
