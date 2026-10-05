package ml.mypals.meshcut.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.FastColor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class LightsaberItem extends Item {
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, java.util.List<net.minecraft.network.chat.Component> tooltip,
                                net.minecraft.world.item.TooltipFlag flag) {
        tooltip.add(net.minecraft.network.chat.Component.translatable("tooltip.meshcut.saber_length", Math.round(lengthScale(stack)*100)));
        tooltip.add(net.minecraft.network.chat.Component.translatable(
                ml.mypals.meshcut.MeshCutClientConfig.toggleSaber()
                        ? "tooltip.meshcut.saber_controls" : "tooltip.meshcut.saber_controls_hold"));
        tooltip.add(net.minecraft.network.chat.Component.translatable("tooltip.meshcut.saber_connections",
                net.minecraft.network.chat.Component.keybind("key.meshcut.connections")));
        tooltip.add(net.minecraft.network.chat.Component.translatable("tooltip.meshcut.saber_gestures",
                net.minecraft.network.chat.Component.keybind("key.meshcut.saber_swing"),
                net.minecraft.network.chat.Component.keybind("key.meshcut.saber_reposition")));
        tooltip.add(net.minecraft.network.chat.Component.translatable("tooltip.meshcut.saber_dye"));
    }
    public static float lengthScale(ItemStack stack) {
        float value = stack.getOrDefault(ml.mypals.meshcut.MeshCut.SABER_LENGTH.get(), 1f);
        return ml.mypals.meshcut.InteractionLimits.bladeLength(value);
    }

    public static void setLengthScale(ItemStack stack, float value) {
        if (Float.isFinite(value) && stack.getItem() instanceof LightsaberItem)
            stack.set(ml.mypals.meshcut.MeshCut.SABER_LENGTH.get(), ml.mypals.meshcut.InteractionLimits.bladeLength(value));
    }

    public LightsaberItem(Properties properties) {
        super(properties);
    }

    public static boolean isActive(Player player) {
        return player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && player.getUseItem().getItem() instanceof LightsaberItem;
    }

    public static int getBladeColor(ItemStack stack) {
        DyedItemColor dyed = stack.get(DataComponents.DYED_COLOR);
        return dyed == null ? -1 : dyed.rgb() & 0xFFFFFF;
    }

    public static void mixDye(ItemStack stack, DyeColor dye) {
        int[] colors;
        int current = getBladeColor(stack);
        int added = dye.getTextureDiffuseColor();
        colors = current == -1 ? new int[] { added } : new int[] { current, added };
        int r = 0, g = 0, b = 0, brightness = 0;
        for (int color : colors) {
            int cr = FastColor.ARGB32.red(color), cg = FastColor.ARGB32.green(color), cb = FastColor.ARGB32.blue(color);
            brightness += Math.max(cr, Math.max(cg, cb));
            r += cr;
            g += cg;
            b += cb;
        }
        int n = colors.length;
        r /= n;
        g /= n;
        b /= n;
        float averageBrightness = (float) brightness / n;
        float max = Math.max(r, Math.max(g, b));
        if (max > 0) {
            r = (int) (r * averageBrightness / max);
            g = (int) (g * averageBrightness / max);
            b = (int) (b * averageBrightness / max);
        }
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor((r << 16) | (g << 8) | b, true));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) {
            ItemStack dye = player.getMainHandItem();
            if (dye.getItem() instanceof DyeItem dyeItem) {
                if (!level.isClientSide()) {
                    mixDye(stack, dyeItem.getDyeColor());
                    dye.consume(1, player);
                    level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.DYE_USE,
                            SoundSource.PLAYERS, 1f, 1f);
                }
                return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
            }
            return InteractionResultHolder.pass(stack);
        }
        if (isActive(player)) {
            player.releaseUsingItem();
            return InteractionResultHolder.consume(stack);
        }
        player.startUsingItem(hand);
        LightsaberMotion.begin(player);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.NONE;
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (entity instanceof Player player) {
            LightsaberMotion.end(player);
            LightsaberHandler.release(player);
        }
    }

    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return false;
    }
}
