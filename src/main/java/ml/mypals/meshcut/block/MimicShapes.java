package ml.mypals.meshcut.block;

import dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.CutPlane;
import ml.mypals.meshcut.geom.VoxelBoxes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collision geometry of cut blocks: the imitated block's collision boxes intersected with the cut half-spaces,
 * approximated with axis-aligned boxes (see {@link VoxelBoxes}). Shared by vanilla collision and the physics engine.
 */
public final class MimicShapes {
    private static final int MAX_CACHED = 4096;
    private static final double QUANT = 1000.0;

    /** Imitated block plus its cuts, quantised so near-identical cuts share one cache entry. */
    public record Key(BlockState target, List<CutPlane> planes) {
        public static Key of(BlockState target, List<CutPlane> planes) {
            List<CutPlane> q = new ArrayList<>(planes.size());
            for (CutPlane p : planes) {
                q.add(new CutPlane(round(p.nx()), round(p.ny()), round(p.nz()), round(p.d())));
            }
            return new Key(target, q);
        }

        private static double round(double v) {
            return Math.round(v * QUANT) / QUANT;
        }
    }

    private static final Map<Key, List<double[]>> BOXES = new HashMap<>();
    private static final Map<BlockState, List<double[]>> TARGET_BOXES = new ConcurrentHashMap<>();

    public static synchronized List<double[]> boxes(Key key) {
        List<double[]> boxes = BOXES.get(key);
        if (boxes == null) {
            if (BOXES.size() >= MAX_CACHED) {
                BOXES.clear();
            }
            boxes = VoxelBoxes.cut(targetBoxes(key.target()), key.planes());
            BOXES.put(key, boxes);
        }
        return boxes;
    }

    /** Fraction (0..1) of the imitated block's collision volume that survives the cuts. */
    public static double volumeFraction(Key key) {
        double full = VoxelBoxes.volume(targetBoxes(key.target()));
        return full <= 0 ? 1 : Math.min(1, VoxelBoxes.volume(boxes(key)) / full);
    }

    /** Volume-weighted centre of what is left of the block, in block-local coordinates; the middle if nothing is. */
    public static Vector3d centerOfMass(Key key) {
        double volume = 0, x = 0, y = 0, z = 0;
        for (double[] b : boxes(key)) {
            double v = (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]);
            volume += v;
            x += v * (b[0] + b[3]) / 2;
            y += v * (b[1] + b[4]) / 2;
            z += v * (b[2] + b[5]) / 2;
        }
        return volume <= 0 ? new Vector3d(0.5, 0.5, 0.5) : new Vector3d(x / volume, y / volume, z / volume);
    }

    public static VoxelShape shape(List<double[]> boxes) {
        if (boxes.isEmpty()) {
            return Shapes.empty();
        }
        VoxelShape shape = Shapes.empty();
        for (double[] b : boxes) {
            shape = Shapes.joinUnoptimized(shape, Shapes.box(b[0], b[1], b[2], b[3], b[4], b[5]), BooleanOp.OR);
        }
        return shape.optimize();
    }

    /** Collision boxes of the imitated block on its own, without any cut. */
    public static List<double[]> targetBoxes(BlockState target) {
        return TARGET_BOXES.computeIfAbsent(target, MimicShapes::computeTargetBoxes);
    }

    private static List<double[]> computeTargetBoxes(BlockState target) {
        VoxelShape shape;
        try {
            shape = target.getBlock() instanceof BlockSubLevelCollisionShape custom
                    ? custom.getSubLevelCollisionShape(EmptyBlockGetter.INSTANCE, target)
                    : target.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            MeshCut.LOGGER.debug("Collision shape of {} needs a real level; using a full block", target, e);
            shape = Shapes.block();
        }
        List<double[]> boxes = new ArrayList<>();
        shape.forAllBoxes((x1, y1, z1, x2, y2, z2) -> boxes.add(new double[]{x1, y1, z1, x2, y2, z2}));
        return boxes;
    }

    public static void clearCaches() {
        synchronized (MimicShapes.class) {
            BOXES.clear();
        }
        TARGET_BOXES.clear();
    }

    private MimicShapes() {}
}
