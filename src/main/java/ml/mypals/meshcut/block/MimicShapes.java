package ml.mypals.meshcut.block;

import dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.CutPlane;
import ml.mypals.meshcut.geom.VoxelBoxes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MimicShapes {
    private static final int MAX_CACHED = 4096;
    private static final double QUANT = 1000.0;
    private static final double MIN_FRACTION = 0.004;
    private static final double EDGE = 1e-7;

    public record Key(BlockState target, List<CutPlane> planes, List<List<CutPlane>> excluded, @Nullable List<Double> base) {
        public static Key of(BlockState target, List<CutPlane> planes) {
            return of(target, planes, List.of());
        }

        public static Key of(BlockState target, List<CutPlane> planes, List<List<CutPlane>> excluded) {
            return of(target, planes, excluded, null);
        }

        public static Key of(BlockState target, List<CutPlane> planes, List<List<CutPlane>> excluded, @Nullable List<double[]> base) {
            List<List<CutPlane>> regions = new ArrayList<>(excluded.size());
            for (List<CutPlane> region : excluded) {
                regions.add(quantised(region));
            }
            List<Double> boxes = null;
            if (base != null) {
                boxes = new ArrayList<>(base.size() * 6);
                for (double[] b : base) {
                    for (double v : b) {
                        boxes.add(round(v));
                    }
                }
            }
            return new Key(target, quantised(planes), regions, boxes);
        }

        public List<double[]> baseBoxes() {
            if (base == null) {
                return cutBoxes(target);
            }
            List<double[]> out = new ArrayList<>(base.size() / 6);
            for (int i = 0; i + 6 <= base.size(); i += 6) {
                double[] b = new double[6];
                for (int k = 0; k < 6; k++) {
                    b[k] = base.get(i + k);
                }
                out.add(b);
            }
            return out;
        }

        private static List<CutPlane> quantised(List<CutPlane> planes) {
            List<CutPlane> q = new ArrayList<>(planes.size());
            for (CutPlane p : planes) {
                q.add(new CutPlane(round(p.nx()), round(p.ny()), round(p.nz()), round(p.d())));
            }
            return q;
        }

        private static double round(double v) {
            return Math.round(v * QUANT) / QUANT;
        }
    }

    private static final Map<Key, List<double[]>> BOXES = new HashMap<>();
    private static final Map<Key, VoxelShape> SHAPES = new HashMap<>();
    private static final Map<BlockState, List<double[]>> TARGET_BOXES = new ConcurrentHashMap<>();

    public static synchronized List<double[]> boxes(Key key) {
        List<double[]> boxes = BOXES.get(key);
        if (boxes == null) {
            if (BOXES.size() >= MAX_CACHED) {
                BOXES.clear();
            }
            boxes = VoxelBoxes.cut(key.baseBoxes(), key.planes(), key.excluded());
            BOXES.put(key, boxes);
        }
        return boxes;
    }

    public static double volumeFraction(Key key) {
        double full = key.base() != null ? 1 : VoxelBoxes.volume(cutBoxes(key.target()));
        // never zero: a body whose blocks all weigh nothing has no centre of mass, which crashes the physics engine
        return full <= 0 ? 1 : Math.max(MIN_FRACTION, Math.min(1, VoxelBoxes.volume(boxes(key)) / full));
    }

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

    public static synchronized VoxelShape shape(Key key) {
        VoxelShape shape = SHAPES.get(key);
        if (shape == null) {
            if (SHAPES.size() >= MAX_CACHED) {
                SHAPES.clear();
            }
            shape = shape(boxes(key));
            SHAPES.put(key, shape);
        }
        return shape;
    }

    public static VoxelShape shape(List<double[]> boxes) {
        List<double[]> solid = new ArrayList<>(boxes.size());
        for (double[] b : boxes) {
            if (b[3] - b[0] > EDGE && b[4] - b[1] > EDGE && b[5] - b[2] > EDGE) {
                solid.add(b);
            }
        }
        if (solid.isEmpty()) {
            return Shapes.empty();
        }
        if (solid.size() == 1) {
            double[] b = solid.getFirst();
            return Shapes.create(b[0], b[1], b[2], b[3], b[4], b[5]);
        }
        double[] xs = edges(solid, 0), ys = edges(solid, 1), zs = edges(solid, 2);
        BitSetDiscreteVoxelShape cells = new BitSetDiscreteVoxelShape(xs.length - 1, ys.length - 1, zs.length - 1);
        for (double[] b : solid) {
            int x1 = cell(xs, b[3]), y1 = cell(ys, b[4]), z1 = cell(zs, b[5]);
            for (int x = cell(xs, b[0]); x < x1; x++) {
                for (int y = cell(ys, b[1]); y < y1; y++) {
                    for (int z = cell(zs, b[2]); z < z1; z++) {
                        cells.fill(x, y, z);
                    }
                }
            }
        }
        return new GridShape(cells, xs, ys, zs);
    }

    private static double[] edges(List<double[]> boxes, int axis) {
        double[] all = new double[boxes.size() * 2];
        for (int i = 0; i < boxes.size(); i++) {
            all[i * 2] = boxes.get(i)[axis];
            all[i * 2 + 1] = boxes.get(i)[axis + 3];
        }
        Arrays.sort(all);
        int n = 1;
        for (int i = 1; i < all.length; i++) {
            if (all[i] - all[n - 1] > EDGE) {
                all[n++] = all[i];
            }
        }
        return Arrays.copyOf(all, n);
    }

    private static int cell(double[] edges, double v) {
        int lo = 0, hi = edges.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (edges[mid] < v - EDGE) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static final class GridShape extends ArrayVoxelShape {
        GridShape(DiscreteVoxelShape cells, double[] xs, double[] ys, double[] zs) {
            super(cells, xs, ys, zs);
        }
    }

    private static final Map<BlockState, List<double[]>> CUT_BOXES = new ConcurrentHashMap<>();

    public static List<double[]> cutBoxes(BlockState target) {
        return CUT_BOXES.computeIfAbsent(target, t -> {
            List<double[]> collision = targetBoxes(t);
            if (!collision.isEmpty()) {
                return collision;
            }
            VoxelShape outline;
            try {
                outline = t.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            } catch (RuntimeException e) {
                outline = Shapes.block();
            }
            List<double[]> boxes = new ArrayList<>();
            outline.forAllBoxes((x1, y1, z1, x2, y2, z2) -> boxes.add(new double[]{x1, y1, z1, x2, y2, z2}));
            return boxes.isEmpty() ? List.of(new double[]{0, 0, 0, 1, 1, 1}) : boxes;
        });
    }

    public static boolean hasCollision(BlockState target) {
        return !targetBoxes(target).isEmpty();
    }

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
            SHAPES.clear();
        }
        TARGET_BOXES.clear();
        CUT_BOXES.clear();
    }

    private MimicShapes() {}
}
