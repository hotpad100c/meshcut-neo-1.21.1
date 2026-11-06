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
import ml.mypals.meshcut.block.MimicShapes;
import ml.mypals.meshcut.cut.CutPlane;
import ml.mypals.meshcut.geom.Arrangement;
import ml.mypals.meshcut.geom.CapBuilder;
import ml.mypals.meshcut.geom.CellGrid;
import ml.mypals.meshcut.geom.MeshClipper;
import ml.mypals.meshcut.geom.MeshPoly;
import ml.mypals.meshcut.geom.FragmentPick;
import ml.mypals.meshcut.geom.Polytope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
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

@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class FragmentDebugRenderer {
    private static final int RADIUS = 6;
    private static final double SUB_LEVEL_RANGE = 48;
    private static final int REFRESH_TICKS = 10;
    private static final float FRAGMENT_ALPHA = 0.38f;
    private static final float LIFT = 0.006f;
    private static final float SLIT = 0.08f;

    private static final float[][] PALETTE = {
            {1f, 0.25f, 0.25f}, {0.25f, 0.9f, 0.3f}, {0.3f, 0.5f, 1f}, {1f, 0.9f, 0.2f},
            {1f, 0.45f, 0.95f}, {0.2f, 0.95f, 0.95f}, {1f, 0.6f, 0.2f}, {0.7f, 0.5f, 1f}};

    private static final Field CHUNK_BORDER = findChunkBorderFlag();

    private record Shape(BlockPos pos, ClientSubLevel sub, List<float[]> quads, List<float[]> colors) {}

    private record Box(ClientSubLevel sub, double x0, double y0, double z0, double x1, double y1, double z1, float[] color) {}

    private record Link(ClientSubLevel sub, double x0, double y0, double z0, double x1, double y1, double z1) {}

    private static final List<Shape> SHAPES = new ArrayList<>();
    private static final List<Box> BOXES = new ArrayList<>();
    private static final List<Link> LINKS = new ArrayList<>();
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

    private static BlockSource sourceOf(ClientLevel level) {
        return new BlockSource() {
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
    }

    private record Highlight(ClientSubLevel sub, List<int[]> boxes, List<double[]> exact, float[] color, float alpha) {}

    private static final List<Highlight> HIGHLIGHTS = new ArrayList<>();
    private static long highlightUntil;
    private static final long HIGHLIGHT_MS = 20_000;

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!event.getLevel().isClientSide() || event.getHand() != InteractionHand.MAIN_HAND
                || !(event.getLevel() instanceof ClientLevel level) || !enabled(minecraft)) {
            return;
        }
        boolean fragmentMode = net.minecraft.client.gui.screens.Screen.hasControlDown();
        if (!fragmentMode && !event.getItemStack().isEmpty()) {
            return;
        }
        event.setCanceled(true);
        BlockSource source = sourceOf(level);
        if (fragmentMode) {
            showFragment(level, source, event.getPos(), minecraft);
            return;
        }
        java.util.Map<Long, SectionIndex> sections = new java.util.HashMap<>();
        java.util.function.Function<int[], SectionIndex> lookup = at -> {
            long key = ((long) at[0] & 0x3FFFFF) << 42 | ((long) at[2] & 0x3FFFFF) << 20 | (at[1] & 0xFFFFF);
            return sections.computeIfAbsent(key, k -> source.loaded(at[0] * 16, at[1] * 16, at[2] * 16)
                    ? new SectionIndex(at[0], at[1], at[2], source) : null);
        };
        BlockPos pos = event.getPos();
        SectionIndex home = lookup.apply(new int[]{pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4});
        HIGHLIGHTS.clear();
        if (home == null) {
            return;
        }
        ClientSubLevel sub = null;
        var container = SubLevelContainer.getContainer(level);
        if (container != null && container.inBounds(pos)) {
            for (var candidate : container.getAllSubLevels()) {
                if (candidate instanceof ClientSubLevel c && c.getPlot().getBoundingBox().contains(pos.getX(), pos.getY(), pos.getZ())) {
                    sub = c;
                }
            }
        }
        int cell = SectionIndex.cell(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        List<SectionIndex.Group> mine = home.groupsAt(cell);
        if (level.getBlockEntity(pos) instanceof MimicBlockEntity mimic) {
            Vec3 eye = minecraft.player.getEyePosition();
            Vec3 look = minecraft.player.getLookAngle();
            if (sub != null) {
                eye = sub.renderPose(1f).transformPositionInverse(eye);
                look = sub.renderPose(1f).transformNormalInverse(look);
            }
            int fragment = FragmentPick.pick(mimic.grid(), new double[]{eye.x - pos.getX(), eye.y - pos.getY(), eye.z - pos.getZ()},
                    new double[]{look.x, look.y, look.z});
            SectionIndex.Group g = fragment < 0 ? null : home.groupOf(cell, fragment);
            if (g != null) {
                mine = List.of(g);
            }
        }
        List<SectionIndex.Group> others = new ArrayList<>();
        for (SectionIndex.Group g : mine) {
            for (SectionIndex.Group n : g.section().neighbourGroups(g, lookup)) {
                if (!mine.contains(n) && !others.contains(n)) {
                    others.add(n);
                }
            }
        }
        for (SectionIndex.Group g : mine) {
            HIGHLIGHTS.add(new Highlight(sub, g.section().boxes(g), List.of(), new float[]{1f, 1f, 1f}, 0.35f));
        }
        for (SectionIndex.Group g : others) {
            HIGHLIGHTS.add(new Highlight(sub, g.section().boxes(g), List.of(), new float[]{1f, 0.85f, 0.1f}, 0.3f));
        }
        highlightUntil = System.currentTimeMillis() + HIGHLIGHT_MS;
        minecraft.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "group: " + mine.size() + " here, " + others.size() + " linked across sections"), true);
    }

    private static final int[][] DIRECTIONS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private static void showFragment(ClientLevel level, BlockSource source, BlockPos pos, Minecraft minecraft) {
        HIGHLIGHTS.clear();
        if (!(level.getBlockEntity(pos) instanceof MimicBlockEntity be)) {
            return;
        }
        CellGrid grid = be.grid();
        ClientSubLevel sub = null;
        var container = SubLevelContainer.getContainer(level);
        if (container != null && container.inBounds(pos)) {
            for (var candidate : container.getAllSubLevels()) {
                if (candidate instanceof ClientSubLevel c && c.getPlot().getBoundingBox().contains(pos.getX(), pos.getY(), pos.getZ())) {
                    sub = c;
                }
            }
        }
        Vec3 eye = minecraft.player.getEyePosition();
        Vec3 look = minecraft.player.getLookAngle();
        if (sub != null) {
            eye = sub.renderPose(1f).transformPositionInverse(eye);
            look = sub.renderPose(1f).transformNormalInverse(look);
        }
        int fragment = FragmentPick.pick(grid, new double[]{eye.x - pos.getX(), eye.y - pos.getY(), eye.z - pos.getZ()},
                new double[]{look.x, look.y, look.z});
        if (fragment < 0) {
            return;
        }
        BlockNodes mine = be.nodes();
        List<double[]> own = cellBoxes(pos, grid, fragment);
        HIGHLIGHTS.add(new Highlight(sub, List.of(), own, new float[]{1f, 1f, 1f}, 0.5f));
        int touching = 0;
        for (int face = 0; face < 6; face++) {
            BlockPos other = pos.offset(DIRECTIONS[face][0], DIRECTIONS[face][1], DIRECTIONS[face][2]);
            BlockNodes there = source.at(other.getX(), other.getY(), other.getZ());
            if (there == null || fragment >= mine.count()) {
                continue;
            }
            for (int g = 0; g < there.count(); g++) {
                if ((mine.mask(fragment, face) & there.mask(g, face ^ 1)) == 0) {
                    continue;
                }
                touching++;
                List<double[]> boxes = level.getBlockEntity(other) instanceof MimicBlockEntity otherBe
                        ? cellBoxes(other, otherBe.grid(), g)
                        : List.of(new double[]{other.getX(), other.getY(), other.getZ(), other.getX() + 1, other.getY() + 1, other.getZ() + 1});
                HIGHLIGHTS.add(new Highlight(sub, List.of(), boxes, new float[]{1f, 0.85f, 0.1f}, 0.4f));
            }
        }
        highlightUntil = System.currentTimeMillis() + HIGHLIGHT_MS;
        minecraft.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "fragment " + fragment + " of " + mine.count() + ": touches " + touching + " piece(s) of the neighbouring blocks"), true);
    }

    private static List<double[]> cellBoxes(BlockPos pos, CellGrid grid, int fragment) {
        List<double[]> boxes = new ArrayList<>();
        int n = CellGrid.N;
        double s = 1.0 / n;
        for (int i = 0; i < CellGrid.CELLS; i++) {
            if (grid.component(i) == fragment) {
                double x = pos.getX() + (i / (n * n)) * s, y = pos.getY() + (i / n % n) * s, z = pos.getZ() + (i % n) * s;
                boxes.add(new double[]{x, y, z, x + s, y + s, z + s});
            }
        }
        return boxes;
    }

    private static void fillBox(VertexConsumer c, Matrix4f matrix, Vec3 camera, ClientSubLevel sub, float partial,
                                int[] box, float[] color, float alpha) {
        double e = 0.02;
        fillBox(c, matrix, camera, sub, partial, new double[]{box[0] - e, box[1] - e, box[2] - e, box[3] + 1 + e, box[4] + 1 + e, box[5] + 1 + e},
                color, alpha);
    }

    private static void fillBox(VertexConsumer c, Matrix4f matrix, Vec3 camera, ClientSubLevel sub, float partial,
                                double[] box, float[] color, float alpha) {
        double x0 = box[0], y0 = box[1], z0 = box[2], x1 = box[3], y1 = box[4], z1 = box[5];
        double[][][] faces = {
                {{x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}}, {{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}},
                {{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}}, {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}},
                {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}}, {{x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}, {x1, y0, z0}}};
        for (double[][] face : faces) {
            for (double[] v : face) {
                Vec3 p = new Vec3(v[0], v[1], v[2]);
                if (sub != null) {
                    p = sub.renderPose(partial).transformPosition(p);
                }
                c.addVertex(matrix, (float) (p.x - camera.x), (float) (p.y - camera.y), (float) (p.z - camera.z))
                        .setColor(color[0], color[1], color[2], alpha);
            }
        }
    }

    private static boolean enabled(Minecraft minecraft) {
        try {
            return CHUNK_BORDER != null && CHUNK_BORDER.getBoolean(minecraft.debugRenderer);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !enabled(minecraft)) {
            SHAPES.clear();
            BOXES.clear();
            LINKS.clear();
            return;
        }
        if (ticks++ % REFRESH_TICKS != 0) {
            return;
        }
        ClientLevel level = minecraft.level;
        SHAPES.clear();
        BOXES.clear();
        LINKS.clear();
        BlockPos center = minecraft.player.blockPosition();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = center.getX() - RADIUS; x <= center.getX() + RADIUS; x++) {
            for (int y = center.getY() - RADIUS; y <= center.getY() + RADIUS; y++) {
                for (int z = center.getZ() - RADIUS; z <= center.getZ() + RADIUS; z++) {
                    pos.set(x, y, z);
                    if (level.getBlockState(pos).getBlock() instanceof MimicBlock
                            && level.getBlockEntity(pos) instanceof MimicBlockEntity be) {
                        SHAPES.add(shapeOf(pos.immutable(), null, be));
                    }
                }
            }
        }
        BlockSource source = sourceOf(level);
        regionsAndLinks(source, null, center.getX() - RADIUS, center.getY() - RADIUS, center.getZ() - RADIUS,
                center.getX() + RADIUS, center.getY() + RADIUS, center.getZ() + RADIUS);

        var container = SubLevelContainer.getContainer(level);
        if (container != null) {
            Vec3 eye = minecraft.player.position();
            for (var sub : container.getAllSubLevels()) {
                if (!(sub instanceof ClientSubLevel client)) {
                    continue;
                }
                var box = client.getPlot().getBoundingBox();
                Vec3 middle = client.renderPose(1f).transformPosition(new Vec3((box.minX() + box.maxX() + 1) / 2.0,
                        (box.minY() + box.maxY() + 1) / 2.0, (box.minZ() + box.maxZ() + 1) / 2.0));
                if (middle.distanceTo(eye) > SUB_LEVEL_RANGE) {
                    continue;
                }
                Vec3 inPlot = client.renderPose(1f).transformPositionInverse(eye);
                int cx = (int) Math.floor(inPlot.x), cy = (int) Math.floor(inPlot.y), cz = (int) Math.floor(inPlot.z);
                int x0 = Math.max(box.minX(), cx - RADIUS), x1 = Math.min(box.maxX(), cx + RADIUS);
                int y0 = Math.max(box.minY(), cy - RADIUS), y1 = Math.min(box.maxY(), cy + RADIUS);
                int z0 = Math.max(box.minZ(), cz - RADIUS), z1 = Math.min(box.maxZ(), cz + RADIUS);
                if (x0 > x1 || y0 > y1 || z0 > z1) {
                    continue;
                }
                regionsAndLinks(source, client, x0, y0, z0, x1, y1, z1);
                for (int x = x0; x <= x1; x++) {
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            pos.set(x, y, z);
                            if (level.getBlockState(pos).getBlock() instanceof MimicBlock
                                    && level.getBlockEntity(pos) instanceof MimicBlockEntity be) {
                                SHAPES.add(shapeOf(pos.immutable(), client, be));
                            }
                        }
                    }
                }
            }
        }
    }

    private static void regionsAndLinks(BlockSource source, ClientSubLevel sub, int minX, int minY, int minZ,
                                        int maxX, int maxY, int maxZ) {
        java.util.Map<Long, SectionIndex> sections = new java.util.HashMap<>();
        java.util.function.Function<int[], SectionIndex> lookup = at -> {
            long key = ((long) at[0] & 0x3FFFFF) << 42 | ((long) at[2] & 0x3FFFFF) << 20 | (at[1] & 0xFFFFF);
            return sections.computeIfAbsent(key, k -> source.loaded(at[0] * 16, at[1] * 16, at[2] * 16)
                    ? new SectionIndex(at[0], at[1], at[2], source) : null);
        };
        for (int sx = minX >> 4; sx <= maxX >> 4; sx++) {
            for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
                for (int sz = minZ >> 4; sz <= maxZ >> 4; sz++) {
                    SectionIndex index = lookup.apply(new int[]{sx, sy, sz});
                    if (index == null) {
                        continue;
                    }
                    int salt = (sx * 31 + sy) * 31 + sz;
                    for (SectionIndex.DebugNode node : index.debugNodes()) {
                        if (!node.region()) {
                            continue;
                        }
                        double x0 = Math.max(node.x0(), minX), x1 = Math.min(node.x1() + 1, maxX + 1);
                        double y0 = Math.max(node.y0(), minY), y1 = Math.min(node.y1() + 1, maxY + 1);
                        double z0 = Math.max(node.z0(), minZ), z1 = Math.min(node.z1() + 1, maxZ + 1);
                        if (x0 < x1 && y0 < y1 && z0 < z1) {
                            BOXES.add(new Box(sub, x0, y0, z0, x1, y1, z1, PALETTE[Math.floorMod(salt + node.group(), PALETTE.length)]));
                        }
                    }
                    for (SectionIndex.DebugLink l : index.debugLinks(lookup)) {
                        LINKS.add(new Link(sub, l.x0(), l.y0(), l.z0(), l.x1(), l.y1(), l.z1()));
                    }
                }
            }
        }
    }

    private static Shape shapeOf(BlockPos pos, ClientSubLevel sub, MimicBlockEntity be) {
        CellGrid grid = be.grid();
        Shape exact = exactShapeOf(pos, sub, be, grid);
        return exact != null ? exact : cellShapeOf(pos, sub, grid);
    }

    private static Shape exactShapeOf(BlockPos pos, ClientSubLevel sub, MimicBlockEntity be, CellGrid grid) {
        var boxes = be.cutBoxes();
        boolean whole = false;
        for (double[] b : boxes) {
            whole |= b[0] <= 1e-6 && b[1] <= 1e-6 && b[2] <= 1e-6 && b[3] >= 1 - 1e-6 && b[4] >= 1 - 1e-6 && b[5] >= 1 - 1e-6;
        }
        if (!whole) {
            return null;
        }
        List<List<CutPlane>> pieces = Arrangement.pieces(be.getPlanes(), be.getPatches());
        List<List<CutPlane>> holes = be.getExcluded();
        int[] fragmentOf = new int[pieces.size()];
        boolean[] taken = new boolean[Math.max(1, grid.componentCount())];
        for (int p = 0; p < pieces.size(); p++) {
            int[] votes = new int[Math.max(1, grid.componentCount())];
            for (int i = 0; i < CellGrid.CELLS; i++) {
                int comp = grid.component(i);
                if (comp < 0) {
                    continue;
                }
                double[] c = CellGrid.centerOf(i);
                boolean inside = true;
                for (CutPlane plane : pieces.get(p)) {
                    if (plane.signedDistance(c[0], c[1], c[2]) < 0.06) {
                        inside = false;
                        break;
                    }
                }
                if (inside) {
                    votes[comp]++;
                }
            }
            int best = 0;
            for (int c = 1; c < votes.length; c++) {
                if (votes[c] > votes[best]) {
                    best = c;
                }
            }
            if (votes[best] == 0) {
                fragmentOf[p] = -1;
                continue;
            }
            if (taken[best]) {
                return null;
            }
            taken[best] = true;
            fragmentOf[p] = best;
        }
        List<float[]> quads = new ArrayList<>();
        List<float[]> colors = new ArrayList<>();
        int real = be.getPlanes().size();
        float[] uv = {0, 0, 1, 1};
        for (int p = 0; p < pieces.size(); p++) {
            if (fragmentOf[p] < 0) {
                continue;
            }
            float[] color = PALETTE[fragmentOf[p] % PALETTE.length];
            List<CutPlane> shrunk = new ArrayList<>(pieces.get(p).subList(0, real));
            for (CutPlane cut : pieces.get(p).subList(real, pieces.get(p).size())) {
                shrunk.add(new CutPlane(cut.nx(), cut.ny(), cut.nz(), cut.d() - SLIT / 2));
            }
            List<MeshPoly> faces = Polytope.faceMeshes(shrunk);
            for (List<CutPlane> hole : holes) {
                faces = MeshClipper.subtract(faces, hole);
            }
            for (List<CutPlane> hole : holes) {
                for (int j = 0; j < hole.size(); j++) {
                    List<CutPlane> others = new ArrayList<>(shrunk);
                    for (int k = 0; k < hole.size(); k++) {
                        if (k != j) {
                            others.add(hole.get(k));
                        }
                    }
                    List<MeshPoly> wall = CapBuilder.face(Polytope.cube(), hole.get(j).flipped(), others, uv, null);
                    for (List<CutPlane> other : holes) {
                        if (other != hole && !wall.isEmpty()) {
                            wall = MeshClipper.subtract(wall, other);
                        }
                    }
                    faces.addAll(wall);
                }
            }
            for (MeshPoly poly : faces) {
                float[] v = poly.verts();
                int n = poly.vertexCount();
                for (int i = 1; i + 1 < n; i++) {
                    int[] corner = {0, i, i + 1, i + 1};
                    float[] q = new float[12];
                    for (int k = 0; k < 4; k++) {
                        q[k * 3] = v[corner[k] * MeshPoly.STRIDE];
                        q[k * 3 + 1] = v[corner[k] * MeshPoly.STRIDE + 1];
                        q[k * 3 + 2] = v[corner[k] * MeshPoly.STRIDE + 2];
                    }
                    quads.add(q);
                    colors.add(color);
                }
            }
        }
        return new Shape(pos, sub, quads, colors);
    }

    private static Shape cellShapeOf(BlockPos pos, ClientSubLevel sub, CellGrid grid) {
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
                for (int k = 0; k < 4; k++) {
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

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (System.currentTimeMillis() > highlightUntil) {
            HIGHLIGHTS.clear();
        }
        if (minecraft.level == null || !enabled(minecraft) || (SHAPES.isEmpty() && BOXES.isEmpty() && HIGHLIGHTS.isEmpty())) {
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
                    Vec3 p = new Vec3(shape.pos().getX() + (double) quad[k * 3], shape.pos().getY() + (double) quad[k * 3 + 1], shape.pos().getZ() + (double) quad[k * 3 + 2]);
                    if (shape.sub() != null) {
                        Pose3dc pose = shape.sub().renderPose(partial);
                        p = pose.transformPosition(p);
                    }
                    Vec3 toEye = camera.subtract(p);
                    double dist = toEye.length();
                    if (dist > 1e-6) {
                        p = p.add(toEye.scale((0.012 + 0.004 * dist) / dist));
                    }
                    filled.addVertex(matrix, (float) (p.x - camera.x), (float) (p.y - camera.y), (float) (p.z - camera.z))
                            .setColor(c[0], c[1], c[2], FRAGMENT_ALPHA);
                }
            }
        }
        for (Highlight h : HIGHLIGHTS) {
            for (int[] box : h.boxes()) {
                fillBox(filled, matrix, camera, h.sub(), partial, box, h.color(), h.alpha());
            }
            for (double[] box : h.exact()) {
                fillBox(filled, matrix, camera, h.sub(), partial, box, h.color(), h.alpha());
            }
        }
        buffers.endBatch(RenderType.debugQuads());

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (Box box : BOXES) {
            double[][] c = new double[8][];
            for (int i = 0; i < 8; i++) {
                c[i] = new double[]{(i & 1) == 0 ? box.x0() : box.x1(), (i & 2) == 0 ? box.y0() : box.y1(), (i & 4) == 0 ? box.z0() : box.z1()};
            }
            for (int i = 0; i < 8; i++) {
                for (int bit = 1; bit <= 4; bit <<= 1) {
                    if ((i & bit) == 0) {
                        line(lines, poseStack, camera, box.sub(), partial, c[i], c[i | bit], box.color()[0], box.color()[1], box.color()[2]);
                    }
                }
            }
        }
        for (Link link : LINKS) {
            line(lines, poseStack, camera, link.sub(), partial, new double[]{link.x0(), link.y0(), link.z0()},
                    new double[]{link.x1(), link.y1(), link.z1()}, 1f, 1f, 1f);
        }
        buffers.endBatch(RenderType.lines());
    }

    private static void line(VertexConsumer lines, PoseStack poseStack, Vec3 camera, ClientSubLevel sub, float partial,
                             double[] from, double[] to, float r, float g, float b) {
        Vec3 a = new Vec3(from[0], from[1], from[2]), c = new Vec3(to[0], to[1], to[2]);
        if (sub != null) {
            Pose3dc pose = sub.renderPose(partial);
            a = pose.transformPosition(a);
            c = pose.transformPosition(c);
        }
        a = towardsEye(a, camera);
        c = towardsEye(c, camera);
        Vec3 d = c.subtract(a);
        if (d.lengthSqr() < 1e-12) {
            return;
        }
        Vec3 n = d.normalize();
        PoseStack.Pose pose = poseStack.last();
        lines.addVertex(pose, (float) (a.x - camera.x), (float) (a.y - camera.y), (float) (a.z - camera.z))
                .setColor(r, g, b, 1f).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
        lines.addVertex(pose, (float) (c.x - camera.x), (float) (c.y - camera.y), (float) (c.z - camera.z))
                .setColor(r, g, b, 1f).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
    }

    private static Vec3 towardsEye(Vec3 p, Vec3 camera) {
        Vec3 toEye = camera.subtract(p);
        double dist = toEye.length();
        return dist > 1e-6 ? p.add(toEye.scale((0.012 + 0.004 * dist) / dist)) : p;
    }

    private FragmentDebugRenderer() {}
}
