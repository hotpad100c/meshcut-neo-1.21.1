package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dubhe.anvilcraft.block.entity.BaseLaserBlockEntity;
import dev.dubhe.anvilcraft.client.init.ModRenderTypes;
import dev.dubhe.anvilcraft.client.renderer.laser.LaserCompiler;
import dev.dubhe.anvilcraft.client.renderer.laser.LaserState;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

public final class AnvilLaserDraw {
    private static final Set<BlockEntity> TRACKED = Collections.newSetFromMap(new WeakHashMap<>());

    public static void track(BlockEntity be) {
        synchronized (TRACKED) {
            TRACKED.add(be);
        }
    }

    public static boolean onSubLevel(BlockEntity be) {
        return be.getLevel() != null && Sable.HELPER.getContaining(be.getLevel(), be.getBlockPos()) != null;
    }

    public static void render(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null) return;
        List<BaseLaserBlockEntity> lasers = new ArrayList<>();
        synchronized (TRACKED) {
            for (BlockEntity be : TRACKED) {
                if (be instanceof BaseLaserBlockEntity laser && !laser.isRemoved() && laser.getLevel() == level) lasers.add(laser);
            }
        }
        if (lasers.isEmpty()) return;
        var camera = event.getCamera().getPosition();
        var buffers = minecraft.renderBuffers().bufferSource();
        PoseStack stack = event.getPoseStack();
        boolean drew = false;
        for (BaseLaserBlockEntity laser : lasers) {
            if (!(Sable.HELPER.getContaining(level, laser.getBlockPos()) instanceof ClientSubLevel sub)) continue;
            Matrix4d body = sub.renderPose().bakeIntoMatrix(new Matrix4d());
            BlockPos pos = laser.getBlockPos();
            Vector3d origin = body.transformPosition(new Vector3d(pos.getX(), pos.getY(), pos.getZ()));
            stack.pushPose();
            stack.translate(origin.x - camera.x, origin.y - camera.y, origin.z - camera.z);
            Matrix4f linear = new Matrix4f(
                    (float) body.m00(), (float) body.m01(), (float) body.m02(), 0,
                    (float) body.m10(), (float) body.m11(), (float) body.m12(), 0,
                    (float) body.m20(), (float) body.m21(), (float) body.m22(), 0,
                    0, 0, 0, 1);
            stack.mulPose(linear);
            LaserState state = LaserState.create(laser, stack);
            if (state != null) {
                state = stoppedByOtherGrids(level, sub, body, laser, state);
                LaserCompiler.compile(state, buffers::getBuffer);
                drew = true;
            }
            stack.popPose();
        }
        if (drew) {
            buffers.endBatch(RenderType.solid());
            buffers.endBatch(ModRenderTypes.LASER);
            buffers.endBatch(RenderType.translucent());
        }
    }

    private static LaserState stoppedByOtherGrids(net.minecraft.client.multiplayer.ClientLevel level, ClientSubLevel own,
                                                  Matrix4d body, BaseLaserBlockEntity laser, LaserState state) {
        var facing = laser.getFacing();
        var face = laser.getBlockPos().getCenter().add(facing.getStepX() * 0.5, facing.getStepY() * 0.5, facing.getStepZ() * 0.5);
        Vector3d from = body.transformPosition(new Vector3d(face.x, face.y, face.z));
        Vector3d dir = body.transformDirection(new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ())).normalize();
        var start = new net.minecraft.world.phys.Vec3(from.x, from.y, from.z);
        var end = start.add(dir.x * (state.length() + 0.5), dir.y * (state.length() + 0.5), dir.z * (state.length() + 0.5));
        var clip = level.clip(new net.minecraft.world.level.ClipContext(start, end, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
        if (clip.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return state;
        var other = Sable.HELPER.getContaining(level, clip.getBlockPos());
        if (other == own) return state;
        net.minecraft.world.phys.Vec3 at = clip.getLocation();
        if (other != null) {
            var p = other.logicalPose().transformPosition(new Vector3d(at.x, at.y, at.z));
            at = new net.minecraft.world.phys.Vec3(p.x, p.y, p.z);
        }
        double along = at.subtract(start).dot(new net.minecraft.world.phys.Vec3(dir.x, dir.y, dir.z));
        if (along <= 0 || along >= state.length()) return state;
        return new LaserState(state.blockEntity(), state.pos(), (float) along, state.offset(), state.laserLevel(), state.gamma(),
                state.lensType(), state.pose(), state.laserAtlasSprite(), state.concreteAtlasSprite());
    }

    private AnvilLaserDraw() {}
}
