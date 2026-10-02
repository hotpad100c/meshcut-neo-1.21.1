package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.cut.graph.BlockNodes;
import ml.mypals.meshcut.cut.graph.BlockSource;
import ml.mypals.meshcut.cut.graph.SectionIndex;
import ml.mypals.meshcut.geom.CellGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * With the chunk border display on (F3+G), draws what the cutting system sees around the player: every fragment of
 * every cut block as a coloured shape (the cells it is made of), and the regions the ordinary blocks are merged into
 * as boxes, coloured by the group they belong to.
 */
@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class FragmentDebugRenderer {
    private static final int RADIUS = 6;
    private static final int REFRESH_TICKS = 10;
    private static final float FRAGMENT_ALPHA = 0.38f;
    /** How far (in blocks) the drawn faces stand off the surface they lie on. */
    private static final float LIFT = 0.006f;

    /** The colours fragments (and groups) take, in turn. */
    private static final float[][] PALETTE = {
            {1f, 0.25f, 0.25f}, {0.25f, 0.9f, 0.3f}, {0.3f, 0.5f, 1f}, {1f, 0.9f, 0.2f},
            {1f, 0.45f, 0.95f}, {0.2f, 0.95f, 0.95f}, {1f, 0.6f, 0.2f}, {0.7f, 0.5f, 1f}};

    private static final Field CHUNK_BORDER = findChunkBorderFlag();

    /** One cut block's fragment faces, ready to draw: x y z per vertex, four vertices a quad, in block-local space. */
    private record Shape(BlockPos pos, ClientSubLevel sub, List<float[]> quads, List<float[]> colors) {}

    private record Box(double x0, double y0, double z0, double x1, double y1, double z1, float[] color) {}

    private static final List<Shape> SHAPES = new ArrayList<>();
    private static final List<Box> BOXES = new ArrayList<>();
    private static int ticks;

    private static Field findChunkBorderFlag() {
        try {
            Field f = net.minecraft.client.renderer.debug.DebugRenderer.class.getDeclaredField("renderChunkborder");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            MeshCut.LOGGER.warn("Can't read the chunk border flag; the fragment debug view is off", e);
            return null;
        }
    }

    private static boolean enabled(Minecraft minecraft) {
        try {
            return CHUNK_BORDER != null && CHUNK_BORDER.getBoolean(minecraft.debugRenderer);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- snapshot

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !enabled(minecraft)) {
            SHAPES.clear();
            BOXES.clear();
            return;
        }
        if (ticks++ % REFRESH_TICKS != 0) {
            return;
        }
        ClientLevel level = minecraft.level;
        SHAPES.clear();
        BOXES.clear();
        BlockPos center = minecraft.player.blockPosition();

        // the static world: fragments and regions
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = center.getX() - RADIUS; x <= center.getX() + RADIUS; x++) {
            for (int y = center.getY() - RADIUS; y <= center.getY() + RADIUS; y++) {
                for (int z = center.getZ() - RADIUS; z <= center.getZ() + RADIUS; z++) {
                    pos.set(x, y, z);
                    if (level.getBlockState(pos).getBlock() instanceof MimicBlock
                            && level.getBlockEntity(pos) instanceof MimicBlockEntity be) {
                        SHAPES.add(shapeOf(pos.immutable(), null, be.grid()));
                    }
                }
            }
        }
        BlockSource source = new BlockSource() {
            @Override
            public BlockNodes at(int x, int y, int z) {
                if (level.isOutsideBuildHeight(y)) {
                    return null;
                }
                BlockPos p = new BlockPos(x, y, z);
                BlockState state = level.getBlockState(p);
                if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
                    return null;
                }
                if (state.getBlock() instanceof MimicBlock) {
                    return level.getBlockEntity(p) instanceof MimicBlockEntity be ? be.nodes() : BlockNodes.PLAIN;
                }
                return BlockNodes.PLAIN;
            }

            @Override
            public boolean loaded(int x, int y, int z) {
                return level.hasChunk(x >> 4, z >> 4);
            }
        };
        int minX = center.getX() - RADIUS, maxX = center.getX() + RADIUS;
        int minY = center.getY() - RADIUS, maxY = center.getY() + RADIUS;
        int minZ = center.getZ() - RADIUS, maxZ = center.getZ() + RADIUS;
        for (int sx = minX >> 4; sx <= maxX >> 4; sx++) {
            for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
                for (int sz = minZ >> 4; sz <= maxZ >> 4; sz++) {
                    if (!source.loaded(sx * 16, sy * 16, sz * 16)) {
                        continue;
                    }
                    int salt = (sx * 31 + sy) * 31 + sz;
                    for (SectionIndex.DebugNode node : new SectionIndex(sx, sy, sz, source).debugNodes()) {
                        if (!node.region()) {
                            continue;
                        }
                        // only the part of the box near the player, so a big floor stays readable
                        double x0 = Math.max(node.x0(), minX), x1 = Math.min(node.x1() + 1, maxX + 1);
                        double y0 = Math.max(node.y0(), minY), y1 = Math.min(node.y1() + 1, maxY + 1);
                        double z0 = Math.max(node.z0(), minZ), z1 = Math.min(node.z1() + 1, maxZ + 1);
                        if (x0 < x1 && y0 < y1 && z0 < z1) {
                            float[] color = PALETTE[Math.floorMod(salt + node.group(), PALETTE.length)];
                            BOXES.add(new Box(x0, y0, z0, x1, y1, z1, color));
                        }
                    }
                }
            }
        }

        // cut blocks inside sub-levels near the player
        var container = SubLevelContainer.getContainer(level);
        if (container != null) {
            Vec3 eye = minecraft.player.position();
            for (var sub : container.getAllSubLevels()) {
                if (!(sub instanceof ClientSubLevel client)
                        || client.logicalPose().position().distance(eye.x, eye.y, eye.z) > RADIUS * 3) {
                    continue;
                }
                var box = client.getPlot().getBoundingBox();
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    for (int y = box.minY(); y <= box.maxY(); y++) {
                        for (int z = box.minZ(); z <= box.maxZ(); z++) {
                            pos.set(x, y, z);
                            if (level.getBlockState(pos).getBlock() instanceof MimicBlock
                                    && level.getBlockEntity(pos) instanceof MimicBlockEntity be) {
                                SHAPES.add(shapeOf(pos.immutable(), client, be.grid()));
                            }
                        }
                    }
                }
            }
        }
    }

    /** The faces of the fragment cells that are not shared with a cell of the same fragment. */
    private static Shape shapeOf(BlockPos pos, ClientSubLevel sub, CellGrid grid) {
        List<float[]> quads = new ArrayList<>();
        List<float[]> colors = new ArrayList<>();
        int n = CellGrid.N;
        float s = 1f / n;
        for (int i = 0; i < CellGrid.CELLS; i++) {
            int comp = grid.component(i);
            if (comp < 0) {
                continue;
            }
            int x = i / (n * n), y = i / n % n, z = i % n;
            float[] color = PALETTE[comp % PALETTE.length];
            for (int face = 0; face < 6; face++) {
                int axis = face / 2, dir = face % 2 == 0 ? 1 : -1;
                int nx = x + (axis == 0 ? dir : 0), ny = y + (axis == 1 ? dir : 0), nz = z + (axis == 2 ? dir : 0);
                boolean outside = nx < 0 || ny < 0 || nz < 0 || nx >= n || ny >= n || nz >= n;
                if (!outside && grid.component((nx * n + ny) * n + nz) == comp) {
                    continue;
                }
                float[] q = new float[12];
                float x0 = x * s, y0 = y * s, z0 = z * s, x1 = x0 + s, y1 = y0 + s, z1 = z0 + s;
                float[][] corners = switch (face) {
                    case 0 -> new float[][]{{x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}};
                    case 1 -> new float[][]{{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
                    case 2 -> new float[][]{{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
                    case 3 -> new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
                    case 4 -> new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
                    default -> new float[][]{{x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}, {x1, y0, z0}};
                };
                for (int k = 0; k < 4; k++) { // stand a little off the surface, or it fights with the block's own faces
                    q[k * 3] = corners[k][0] + (axis == 0 ? dir * LIFT : 0);
                    q[k * 3 + 1] = corners[k][1] + (axis == 1 ? dir * LIFT : 0);
                    q[k * 3 + 2] = corners[k][2] + (axis == 2 ? dir * LIFT : 0);
                }
                quads.add(q);
                colors.add(color);
            }
        }
        return new Shape(pos, sub, quads, colors);
    }

    // ---------------------------------------------------------------- drawing

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !enabled(minecraft) || (SHAPES.isEmpty() && BOXES.isEmpty())) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        Matrix4f matrix = poseStack.last().pose();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        VertexConsumer filled = buffers.getBuffer(RenderType.debugQuads());
        for (Shape shape : SHAPES) {
            for (int q = 0; q < shape.quads().size(); q++) {
                float[] quad = shape.quads().get(q);
                float[] c = shape.colors().get(q);
                for (int k = 0; k < 4; k++) {
                    Vec3 p = new Vec3(shape.pos().getX() + quad[k * 3], shape.pos().getY() + quad[k * 3 + 1], shape.pos().getZ() + quad[k * 3 + 2]);
                    if (shape.sub() != null) {
                        Pose3dc pose = shape.sub().renderPose(partial);
                        p = pose.transformPosition(p);
                    }
                    filled.addVertex(matrix, (float) (p.x - camera.x), (float) (p.y - camera.y), (float) (p.z - camera.z))
                            .setColor(c[0], c[1], c[2], FRAGMENT_ALPHA);
                }
            }
        }
        buffers.endBatch(RenderType.debugQuads());

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        for (Box box : BOXES) {
            LevelRenderer.renderLineBox(poseStack, lines, box.x0(), box.y0(), box.z0(), box.x1(), box.y1(), box.z1(),
                    box.color()[0], box.color()[1], box.color()[2], 1f);
        }
        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
    }

    private FragmentDebugRenderer() {}
}
