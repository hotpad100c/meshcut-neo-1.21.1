package ml.mypals.meshcut.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.network.GrabNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class FragmentGrabClient {
    private static final Set<UUID> HOLDERS = new HashSet<>();

    @Nullable
    private static UUID body;
    private static final Vector3d point = new Vector3d();
    private static float distance;
    private static int request;
    private static boolean confirmed;
    private static int sentTick;
    private static net.minecraft.client.multiplayer.ClientLevel heldLevel;

    public static void accept(GrabNet.Result result) {
        if (body == null || !body.equals(result.body()) || request != result.request()) return;
        boolean wasConfirmed = confirmed;
        confirmed = result.reason() == 0;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && (!wasConfirmed || !confirmed)) player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "message.meshcut.grab." + result.reason()), true);
        if (!confirmed) body = null;
    }

    public static void setHolders(Collection<UUID> players) {
        HOLDERS.clear();
        HOLDERS.addAll(players);
    }

    public static boolean holding() {
        return body != null;
    }

    @SubscribeEvent
    public static void onUse(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND || player == null || minecraft.level == null
                || !player.getMainHandItem().isEmpty() || player.isSpectator()) {
            return;
        }
        if (holding()) {
            event.setCanceled(true);
            event.setSwingHand(false);
            return;
        }
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        SubLevel sub = Sable.HELPER.getContaining(minecraft.level, pos);
        if (sub == null || !(minecraft.level.getBlockState(pos).getBlock() instanceof MimicBlock)) {
            return;
        }
        Vec3 location = hit.getLocation();
        Vector3d local = new Vector3d(location.x, location.y, location.z);
        if (local.distanceSquared(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 4) {
            local = sub.logicalPose().transformPositionInverse(local);
        }
        Vector3d world = sub.logicalPose().transformPosition(new Vector3d(local));
        Vec3 eye = player.getEyePosition();
        body = sub.getUniqueId();
        heldLevel = minecraft.level;
        confirmed = false;
        point.set(local);
        distance = (float) Math.min(world.distance(eye.x, eye.y, eye.z), reach(player));
        send(true);
        event.setCanceled(true);
        event.setSwingHand(true);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (!holding()) {
            return;
        }
        if (player == null || minecraft.level == null || !minecraft.options.keyUse.isDown() || player.isDeadOrDying()
                || minecraft.level != heldLevel || !player.getMainHandItem().isEmpty() || minecraft.screen != null
                || (!confirmed && player.tickCount - sentTick > 60)
                || Sable.HELPER.getContaining(minecraft.level, BlockPos.containing(point.x, point.y, point.z)) == null) {
            send(false);
            body = null;
            confirmed = false;
        }
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!holding() || player == null) {
            return;
        }
        distance = (float) Math.max(1, Math.min(reach(player), distance + event.getScrollDeltaY() * 0.5));
        send(true);
        event.setCanceled(true);
    }

    public static void pose(net.minecraft.world.entity.LivingEntity entity, HumanoidModel<?> model) {
        if (!HOLDERS.contains(entity.getUUID()) || Minecraft.getInstance().isPaused()) {
            return;
        }
        model.leftArm.zRot = 0;
        model.rightArm.zRot = 0;
        model.leftArm.xRot = (float) Math.toRadians(-80) + model.head.xRot;
        model.rightArm.xRot = (float) Math.toRadians(-80) + model.head.xRot;
        model.rightArm.yRot = (float) Math.toRadians(-15);
        model.leftArm.yRot = (float) Math.toRadians(15);
    }

    private static void send(boolean holding) {
        if (body != null && Minecraft.getInstance().getConnection() != null) {
            request++;
            LocalPlayer player = Minecraft.getInstance().player;
            sentTick = player == null ? 0 : player.tickCount;
            PacketDistributor.sendToServer(new GrabNet.Hold(holding, body, point.x, point.y, point.z, distance, request));
        }
    }

    private static double reach(LocalPlayer player) {
        return Math.min(5, player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE));
    }

    private FragmentGrabClient() {}
}
