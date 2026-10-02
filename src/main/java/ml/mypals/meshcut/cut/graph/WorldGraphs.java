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

/** The {@link StructureGraph} of each server world, and the hooks that keep it up to date. */
public final class WorldGraphs {
    /** At most this many mimics are looked at again when a section's groups were split by an edit. */
    private static final int MAX_RECHECKS = 64;

    private static final Map<ServerLevel, StructureGraph> GRAPHS = new WeakHashMap<>();

    public static StructureGraph of(ServerLevel level) {
        return GRAPHS.computeIfAbsent(level, l -> new StructureGraph(new LevelSource(l)));
    }

    /** A block was replaced (called from the chunk, for every change of block state on the server). */
    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState old, BlockState now) {
        if (old.getBlock() instanceof MimicBlock && now.getBlock() instanceof MimicBlock) {
            return; // only the refresh flag flipped; what the block is made of is reported by its block entity
        }
        update(level, pos);
    }

    /** What a mimic is made of changed. */
    public static void onNodesChanged(ServerLevel level, BlockPos pos) {
        update(level, pos);
    }

    public static void onChunkUnload(ServerLevel level, int chunkX, int chunkZ) {
        StructureGraph graph = GRAPHS.get(level);
        if (graph != null) {
            graph.unloadChunk(chunkX, chunkZ);
        }
    }

    private static void update(ServerLevel level, BlockPos pos) {
        StructureGraph graph = GRAPHS.get(level);
        if (graph == null) {
            return;
        }
        SectionIndex split = graph.update(pos.getX(), pos.getY(), pos.getZ());
        if (split != null) {
            // some group of the section was split: cut blocks in it may have lost what held them
            int n = 0;
            for (int cell : split.mimicCells()) {
                if (n++ >= MAX_RECHECKS) {
                    break;
                }
                SlashService.queueRecheck(level, new BlockPos(split.sx * 16 + cell / 256, split.sy * 16 + cell / 16 % 16, split.sz * 16 + cell % 16));
            }
        }
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
