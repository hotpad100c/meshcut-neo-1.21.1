package ml.mypals.meshcut.cut;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.geom.CellGrid;
import ml.mypals.meshcut.geom.Patch;
import ml.mypals.meshcut.geom.PieceFit;
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

/** Breaking a cut block takes out only the piece of it the player is pointing at. */
public final class FragmentBreaking {
    private static final double STEP = 1.0 / 32;

    /**
     * Removes the fragment of the mimic the player is looking at, leaving the others where they are.
     *
     * @return {@code false} if the block is to be broken as a whole: it is not cut into several pieces, or the player is
     *         sneaking. A piece that cannot be cut out on its own (it is not a convex shape) is left alone, so that
     *         the other pieces are not lost with it; the player is told, and can sneak to break the whole block.
     */
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
        if (result == null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "This piece is not a convex shape and can't be broken on its own (sneak to break the whole block)"), true);
            return true; // handled: nothing is broken
        }
        MimicBlock.place(level, pos, be.getTargetState(), new ArrayList<>(be.getPlanes()), result.excluded(), result.patches(),
                be.getCapMode(), be.getTargetBeTag());
        // what is left may have lost what held it
        SlashService.queueRecheck(level, pos);
        return true;
    }

    /** The fragment the player's line of sight first meets inside the block, or -1. */
    private static int pointedFragment(ServerLevel level, BlockPos pos, CellGrid grid, Player player) {
        Vector3d eye = new Vector3d(player.getEyePosition().x, player.getEyePosition().y, player.getEyePosition().z);
        Vec3 lookVec = player.getLookAngle();
        Vector3d look = new Vector3d(lookVec.x, lookVec.y, lookVec.z);
        var container = SubLevelContainer.getContainer(level);
        if (container != null && container.inBounds(pos) && Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel sub) {
            eye = sub.logicalPose().transformPositionInverse(eye);
            look = sub.logicalPose().transformNormalInverse(look);
        }
        // into the block's own frame
        double ox = eye.x - pos.getX(), oy = eye.y - pos.getY(), oz = eye.z - pos.getZ();
        double enter = 0, exit = 16;
        double[] o = {ox, oy, oz}, d = {look.x, look.y, look.z};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1e-9) {
                if (o[axis] < 0 || o[axis] > 1) {
                    return nearest(grid, o, d, 0, 16);
                }
                continue;
            }
            double t0 = (0 - o[axis]) / d[axis], t1 = (1 - o[axis]) / d[axis];
            enter = Math.max(enter, Math.min(t0, t1));
            exit = Math.min(exit, Math.max(t0, t1));
        }
        if (enter > exit) {
            return nearest(grid, o, d, 0, 16);
        }
        for (double t = enter + 1e-4; t <= exit; t += STEP) {
            int x = (int) Math.floor((o[0] + d[0] * t) * CellGrid.N);
            int y = (int) Math.floor((o[1] + d[1] * t) * CellGrid.N);
            int z = (int) Math.floor((o[2] + d[2] * t) * CellGrid.N);
            if (x < 0 || y < 0 || z < 0 || x >= CellGrid.N || y >= CellGrid.N || z >= CellGrid.N) {
                continue;
            }
            int comp = grid.component((x * CellGrid.N + y) * CellGrid.N + z);
            if (comp >= 0) {
                return comp;
            }
        }
        return nearest(grid, o, d, enter, exit); // the line passes through gaps only: the closest piece
    }

    /** The fragment with a cell closest to the line between the two parameters. */
    private static int nearest(CellGrid grid, double[] o, double[] d, double from, double to) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < CellGrid.CELLS; i++) {
            int comp = grid.component(i);
            if (comp < 0) {
                continue;
            }
            double[] c = CellGrid.centerOf(i);
            double t = Math.max(from, Math.min(to, (c[0] - o[0]) * d[0] + (c[1] - o[1]) * d[1] + (c[2] - o[2]) * d[2]));
            double dx = o[0] + d[0] * t - c[0], dy = o[1] + d[1] * t - c[1], dz = o[2] + d[2] * t - c[2];
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDistance) {
                bestDistance = dist;
                best = comp;
            }
        }
        return best;
    }

    private FragmentBreaking() {}
}
