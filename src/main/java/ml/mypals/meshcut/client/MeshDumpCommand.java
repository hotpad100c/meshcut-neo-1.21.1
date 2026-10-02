package ml.mypals.meshcut.client;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.client.render.MimicMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code /meshcutdump}: writes everything the mesh of the cut block being looked at is made of (see
 * {@link MimicMesh#describe}) to {@code meshcut-dumps/} in the game directory, and copies it to the clipboard.
 */
@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class MeshDumpCommand {
    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("meshcutdump").executes(c -> dump()));
    }

    private static int dump() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            message(minecraft, "Look at a cut block first");
            return 0;
        }
        BlockPos pos = hit.getBlockPos();
        if (!(minecraft.level.getBlockEntity(pos) instanceof MimicBlockEntity be)) {
            message(minecraft, "That is not a cut block: " + minecraft.level.getBlockState(pos));
            return 0;
        }
        String text = "pos " + pos.toShortString() + "\n" + MimicMesh.describe(be.renderData());
        try {
            Path dir = minecraft.gameDirectory.toPath().resolve("meshcut-dumps");
            Files.createDirectories(dir);
            Path file = dir.resolve("mesh-" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + "-" + System.currentTimeMillis() + ".txt");
            Files.writeString(file, text);
            minecraft.keyboardHandler.setClipboard(text);
            message(minecraft, "Mesh written to " + file.toAbsolutePath() + " (and copied)");
        } catch (Exception e) {
            MeshCut.LOGGER.error("Couldn't write the mesh dump", e);
            message(minecraft, "Couldn't write the dump: " + e);
        }
        return 1;
    }

    private static void message(Minecraft minecraft, String text) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(text), false);
        }
    }

    private MeshDumpCommand() {}
}
