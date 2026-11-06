package ml.mypals.meshcut.item;

import dev.ryanhcode.sable.Sable;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.LaserCutting;
import ml.mypals.meshcut.geom.VoxelCapsule;
import ml.mypals.meshcut.network.LaserNet;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3d;

import java.util.List;

public final class LaserGunItem extends Item {
    public static final double RANGE = 64;
    public LaserGunItem(Properties properties) { super(properties); }

    public static int radius(ItemStack stack) { return VoxelCapsule.radius(stack.getOrDefault(MeshCut.LASER_RADIUS.get(), 1)); }

    public static void setRadius(ItemStack stack, int radius) {
        if (stack.getItem() instanceof LaserGunItem) stack.set(MeshCut.LASER_RADIUS.get(), VoxelCapsule.radius(radius));
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        return player == null ? InteractionResult.PASS : use(context.getLevel(), player, context.getHand()).getResult();
    }

    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) { return false; }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand == InteractionHand.OFF_HAND) {
            var dye = player.getMainHandItem();
            if (!(dye.getItem() instanceof DyeItem dyeItem)) return InteractionResultHolder.pass(stack);
            if (!level.isClientSide()) {
                LightsaberItem.mixDye(stack, dyeItem.getDyeColor());
                dye.consume(1, player);
                level.playSound(null, player.blockPosition(), SoundEvents.DYE_USE, SoundSource.PLAYERS, 1, 1);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        if (player.isSpectator() || player.getCooldowns().isOnCooldown(this)) return InteractionResultHolder.fail(stack);
        if (level.isClientSide()) {
            player.getCooldowns().addCooldown(this, 4);
            ml.mypals.meshcut.client.LaserGunHands.shoot(player, stack);
            long operation=ml.mypals.meshcut.client.CutPrediction.nextOperation();
            PacketDistributor.sendToServer(new LaserNet.RequestShot(operation));
            ml.mypals.meshcut.client.CutPrediction.shoot(player,stack,operation);
        }
        return InteractionResultHolder.consume(stack);
    }

    public static void requestShot(ServerPlayer player,long operation) {
        ItemStack stack=player.getMainHandItem();
        if(!player.isAlive() || player.isSpectator() || !(stack.getItem() instanceof LaserGunItem item)
                || player.getCooldowns().isOnCooldown(item)) {
            PacketDistributor.sendToPlayer(player,new LaserNet.Acknowledged(operation,false,List.of()));return;
        }
        player.getCooldowns().addCooldown(item,4);
        try(var changes=ml.mypals.meshcut.network.CutSyncNet.shot(player.serverLevel())) {
            shoot(player,stack);
            PacketDistributor.sendToPlayer(player,new LaserNet.Acknowledged(operation,true,changes.stamps()));
        } catch(RuntimeException exception) {
            MeshCut.LOGGER.error("Laser operation {} failed",operation,exception);
            PacketDistributor.sendToPlayer(player,new LaserNet.Acknowledged(operation,false,List.of()));
        }
    }

    private static void shoot(ServerPlayer player, ItemStack stack) {
        ServerLevel level = player.serverLevel();
        Vec3 start = player.getEyePosition(), direction = player.getLookAngle();
        Vec3 end = start.add(direction.scale(RANGE));
        for (int step = 1; step <= RANGE; step++) {
            Vec3 sample = start.add(direction.scale(step));
            if (!level.hasChunkAt(net.minecraft.core.BlockPos.containing(sample))) {
                end = start.add(direction.scale(step - 1));
                break;
            }
        }
        var hit = level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        Vec3 local = hit.getLocation(), localDirection = direction;
        var sub = hit.getType() == HitResult.Type.BLOCK ? Sable.HELPER.getContaining(level, hit.getBlockPos()) : null;
        if (hit.getType() == HitResult.Type.BLOCK) {
            if (sub != null) {
                var world = sub.logicalPose().transformPosition(new Vector3d(local.x, local.y, local.z));
                end = new Vec3(world.x, world.y, world.z);
                var normal = sub.logicalPose().transformNormalInverse(new Vector3d(direction.x, direction.y, direction.z));
                localDirection = new Vec3(normal.x, normal.y, normal.z).normalize();
            } else end = local;
        }
        net.minecraft.world.entity.Entity target = null;
        Vec3 entityPoint = null;
        double closest = start.distanceToSqr(end);
        for (var entity : level.getEntities(player, new net.minecraft.world.phys.AABB(start,end).inflate(1),
                e -> ml.mypals.meshcut.cut.EntityCutting.accepts(e instanceof net.neoforged.neoforge.entity.PartEntity<?> part ? part.getParent() : e))) {
            var point = entity.getBoundingBox().clip(start,end);
            if (point.isPresent() && start.distanceToSqr(point.get()) < closest) {
                target = entity instanceof net.neoforged.neoforge.entity.PartEntity<?> part ? part.getParent() : entity;
                entityPoint = point.get(); closest = start.distanceToSqr(entityPoint);
            }
        }
        int shotRadius = radius(stack);
        if (target != null) {
            Vec3 a = entityPoint;
            Vec3 b = a.add(direction.scale(exitDistance(a,direction,target.getBoundingBox())));
            ml.mypals.meshcut.cut.EntityCutting.cutEntity(level,target,player,
                    bodies -> bodies.forEach(body -> LaserCutting.vaporize(level,body,a,b,shotRadius)));
            end = a;
        } else
        if (hit.getType() == HitResult.Type.BLOCK) {
            var pos = hit.getBlockPos();
            Vec3 b = local.add(localDirection.scale(exitDistance(local,localDirection,new net.minecraft.world.phys.AABB(pos))));
            if (sub != null) {
                var world = sub.logicalPose().transformPosition(new Vector3d(b.x,b.y,b.z));
                b = new Vec3(world.x,world.y,world.z);
            }
            if (level.mayInteract(player, pos))
                LaserCutting.vaporizeImpact(level,sub,end,b,shotRadius);
        }
        int color = LightsaberItem.getBladeColor(stack);
        var offset = ml.mypals.meshcut.client.GunPose.thirdPerson(player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT,
                player.isCrouching(), player.yBodyRot, player.getYRot(), player.getXRot(), player.getScale(), 0)
                .transformPosition(ml.mypals.meshcut.client.GunPose.muzzle());
        Vec3 muzzle = player.position().add(offset.x, offset.y, offset.z);
        PacketDistributor.sendToPlayersNear(level, null, start.x, start.y, start.z, 128,
                new LaserNet.Shot(player.getId(), muzzle, end, color == -1 ? 0x40ccff : color, radius(stack)));
        level.playSound(null, player.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.45f, 1.8f);
    }

    public static double exitDistance(Vec3 point, Vec3 direction, net.minecraft.world.phys.AABB box) {
        double exit = RANGE;
        double[] p={point.x,point.y,point.z}, d={direction.x,direction.y,direction.z};
        double[] low={box.minX,box.minY,box.minZ}, high={box.maxX,box.maxY,box.maxZ};
        for(int i=0;i<3;i++) if(Math.abs(d[i])>1e-10)
            exit=Math.min(exit,Math.max(0,((d[i]>0?high[i]:low[i])-p[i])/d[i]));
        return exit;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.meshcut.laser_radius", radius(stack)));
        tooltip.add(Component.translatable("tooltip.meshcut.laser_controls"));
        tooltip.add(Component.translatable("tooltip.meshcut.laser_dye"));
    }
}
