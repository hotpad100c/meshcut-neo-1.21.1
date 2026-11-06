package ml.mypals.meshcut.cut.graph;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.cut.SlashService;
import ml.mypals.meshcut.geom.CellGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.WeakHashMap;

public final class WorldGraphs {
    private static final int MAX_RECHECKS = 64;

    private static final Map<ServerLevel, StructureGraph> GRAPHS = new WeakHashMap<>();
    private static final Map<ServerLevel, Batch> BATCHES = new WeakHashMap<>();

    public static final class Batch implements AutoCloseable {
        private final ServerLevel level;
        private final java.util.Set<BlockPos> pending = new java.util.LinkedHashSet<>();
        private int depth;
        private Batch(ServerLevel level) { this.level = level; }
        @Override public void close() {
            if (--depth == 0) {
                flush(level);
                BATCHES.remove(level);
            }
        }
    }

    public static Batch batch(ServerLevel level) {
        Batch batch = BATCHES.computeIfAbsent(level, Batch::new);
        batch.depth++;
        return batch;
    }

    public static StructureGraph of(ServerLevel level) {
        StructureGraph graph = GRAPHS.computeIfAbsent(level, l -> new StructureGraph(new LevelSource(l)));
        flush(level); // resolve/closures must always see completed writes, even inside an outer cut batch
        return graph;
    }

    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState old, BlockState now) {
        ml.mypals.meshcut.network.CutSyncNet.changed(level,pos);
        if (old.getBlock() instanceof MimicBlock && now.getBlock() instanceof MimicBlock) {
            return;
        }
        update(level, pos);
    }

    public static void onNodesChanged(ServerLevel level, BlockPos pos) {
        ml.mypals.meshcut.network.CutSyncNet.changed(level,pos);
        update(level, pos);
    }

    public static void onChunkUnload(ServerLevel level, int chunkX, int chunkZ) {
        StructureGraph graph = GRAPHS.get(level);
        if (graph != null) {
            graph.unloadChunk(chunkX, chunkZ);
        }
    }

    private static void update(ServerLevel level, BlockPos pos) {
        Batch batch = BATCHES.get(level);
        if (batch != null) { batch.pending.add(pos.immutable()); return; }
        StructureGraph current = GRAPHS.get(level);
        if (current != null && current.unchanged(pos.getX(), pos.getY(), pos.getZ())) return;
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            BlockPos next = pos.relative(dir);
            if (level.isLoaded(next) && level.getBlockState(next).getBlock() instanceof MimicBlock) {
                SlashService.queueLocalRecheck(level, next);
            }
        }
        StructureGraph graph = GRAPHS.get(level);
        if (graph == null) {
            return;
        }
        SectionIndex split = graph.update(pos.getX(), pos.getY(), pos.getZ());
        if (split != null) {
            int n = 0;
            for (int cell : split.mimicCells()) {
                if (n++ >= MAX_RECHECKS) {
                    break;
                }
                SlashService.queueRecheck(level, new BlockPos(split.sx * 16 + cell / 256, split.sy * 16 + cell / 16 % 16, split.sz * 16 + cell % 16));
            }
        }
    }

    private static void flush(ServerLevel level) {
        Batch batch = BATCHES.get(level);
        if (batch == null || batch.pending.isEmpty()) return;
        java.util.List<BlockPos> positions = new java.util.ArrayList<>(batch.pending);
        batch.pending.clear();
        StructureGraph graph = GRAPHS.get(level);
        if (graph != null) positions.removeIf(p -> graph.unchanged(p.getX(), p.getY(), p.getZ()));
        java.util.Set<BlockPos> rechecks = new java.util.LinkedHashSet<>();
        for (BlockPos pos : positions) for (var direction : net.minecraft.core.Direction.values()) {
            BlockPos next = pos.relative(direction);
            if (level.isLoaded(next) && level.getBlockState(next).getBlock() instanceof MimicBlock) rechecks.add(next);
        }
        if (graph != null && !positions.isEmpty()) {
            var rebuilt = graph.updateBatch(positions.stream().map(p -> new StructureGraph.NodeRef(p.getX(),p.getY(),p.getZ(),0)).toList());
            for (SectionIndex section : rebuilt) {
                int count = 0;
                for (int c : section.mimicCells()) {
                    if (count++ >= MAX_RECHECKS) break;
                    rechecks.add(new BlockPos(section.sx*16+c/256,section.sy*16+c/16%16,section.sz*16+c%16));
                }
            }
        }
        rechecks.forEach(p -> SlashService.queueRecheck(level, p));
    }

    private record LevelSource(ServerLevel level) implements BlockSource {
        @Override
        public BlockNodes at(int x, int y, int z) {
            if (level.isOutsideBuildHeight(y)) {
                return null;
            }
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
                return null;
            }
            if (state.getBlock() instanceof MimicBlock) {
                return level.getBlockEntity(pos) instanceof MimicBlockEntity be ? be.nodes() : BlockNodes.of(CellGrid.FULL, false);
            }
            return BlockNodes.PLAIN;
        }

        @Override
        public boolean loaded(int x, int y, int z) {
            var container = SubLevelContainer.getContainer(level);
            return level.hasChunk(x >> 4, z >> 4) || (container != null && container.inBounds(new BlockPos(x, y, z)));
        }
    }

    private WorldGraphs() {}
}
