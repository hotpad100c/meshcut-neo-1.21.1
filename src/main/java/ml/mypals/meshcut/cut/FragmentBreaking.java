package ml.mypals.meshcut.cut;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.geom.CellGrid;
import ml.mypals.meshcut.geom.FragmentPick;
import ml.mypals.meshcut.geom.Patch;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class FragmentBreaking {
    private static final double STEP = 1.0 / 32;

    public static boolean breakPointed(ServerLevel level, BlockPos pos, MimicBlockEntity be, Player player) {
        CellGrid grid = be.grid();
        if (grid.componentCount() < 2) {
            return false;
        }
        if (player.isShiftKeyDown()) {
            return false;
        }
        int fragment = pointedFragment(level, pos, grid, player);
        if (fragment < 0) {
            return false;
        }
        FragmentRemoval.Result result = FragmentRemoval.remove(grid, be.getPlanes(), be.getExcluded(), be.getPatches(), fragment);
        if (result == null) return false;
        MimicBlock.place(level, pos, be.getTargetState(), new ArrayList<>(be.getPlanes()), result.excluded(), result.patches(),
                be.getCapMode(), be.getTargetBeTag(), be.getAppearance());
        SlashService.queueRecheck(level, pos);
        return true;
    }

    private static int pointedFragment(ServerLevel level, BlockPos pos, CellGrid grid, Player player) {
        Vector3d eye = new Vector3d(player.getEyePosition().x, player.getEyePosition().y, player.getEyePosition().z);
        Vec3 lookVec = player.getLookAngle();
        Vector3d look = new Vector3d(lookVec.x, lookVec.y, lookVec.z);
        var container = SubLevelContainer.getContainer(level);
        if (container != null && container.inBounds(pos) && Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel sub) {
            eye = sub.logicalPose().transformPositionInverse(eye);
            look = sub.logicalPose().transformNormalInverse(look);
        }
        return FragmentPick.pick(grid, new double[]{eye.x - pos.getX(), eye.y - pos.getY(), eye.z - pos.getZ()},
                new double[]{look.x, look.y, look.z});
    }

    private FragmentBreaking() {}
}
