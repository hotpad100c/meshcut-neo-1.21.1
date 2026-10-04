package ml.mypals.meshcut.block;

import ml.mypals.meshcut.cut.CutPlane;
import ml.mypals.meshcut.geom.Patch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;


final class MimicFaces {

    static int wholeFaces(BlockState target, List<CutPlane> planes, List<List<CutPlane>> excluded, List<Patch> patches,
                          boolean partOfEntity) {
        if (partOfEntity || !target.canOcclude()) {
            return 0;
        }
        int mask = 0;
        for (Direction dir : Direction.values()) {
            if (opaqueFace(target, dir) && untouched(dir, planes, excluded, patches)) {
                mask |= 1 << dir.get3DDataValue();
            }
        }
        return mask;
    }

    private static boolean opaqueFace(BlockState target, Direction dir) {
        try {
            return Block.isFaceFull(target.getFaceOcclusionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, dir), dir);
        } catch (RuntimeException e) {
            return false;// how
        }
    }

    private static boolean untouched(Direction dir, List<CutPlane> planes, List<List<CutPlane>> excluded, List<Patch> patches) {
        int axis = dir.getAxis().ordinal();
        if (!ml.mypals.meshcut.geom.FaceCoverage.whole(axis,
                dir.getAxisDirection() == Direction.AxisDirection.POSITIVE, planes, excluded)) return false;
        double at = dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
        for (Patch patch : patches) {
            float[] v = patch.polygon();
            for (int i = axis; i < v.length; i += 3) {
                if (Math.abs(v[i] - at) < 1e-4) {
                    return false;
                }
            }
        }
        return true;
    }

    private MimicFaces() {}
}
