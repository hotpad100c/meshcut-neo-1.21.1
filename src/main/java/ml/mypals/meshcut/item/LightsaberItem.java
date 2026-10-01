package ml.mypals.meshcut.item;

import ml.mypals.meshcut.MeshCut;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A hilt that can be switched on with right-click. While it is on and held in the main hand, a blade extends from
 * it (see {@code Blade}) and sweeping it through blocks cuts them ({@code LightsaberHandler}).
 */
public class LightsaberItem extends Item {
    public LightsaberItem(Properties properties) {
        super(properties);
    }

    public static boolean isActive(ItemStack stack) {
        return stack.getOrDefault(MeshCut.SABER_ACTIVE.get(), false);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        boolean on = !isActive(stack);
        stack.set(MeshCut.SABER_ACTIVE.get(), on);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                on ? SoundEvents.BEACON_ACTIVATE : SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.6f, on ? 1.7f : 1.3f);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** Swinging it at blocks should not also mine them. */
    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return false;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return isActive(stack);
    }
}
