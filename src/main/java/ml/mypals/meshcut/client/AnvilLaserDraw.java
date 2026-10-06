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

/**
 * Draws AnvilCraft's lasers that stand on sub-levels. AnvilCraft draws its lasers either into buffers cached in world
 * space (which know nothing of a body's pose) or through block entity renderers (which Sable and the chunk renderer
 * only reach in some set-ups), so a laser on a body was not seen without shaders. This draws it from the laser's own
 * state with the body's current pose, the same way in every set-up. Only loaded when AnvilCraft is.
 */
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
            // In doubles: plot coordinates are in the tens of millions, and floats cannot hold a block there.
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

    private AnvilLaserDraw() {}
}
