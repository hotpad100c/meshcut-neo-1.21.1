package ml.mypals.meshcut.client;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import ml.mypals.meshcut.client.capture.ModelCapturer;
import ml.mypals.meshcut.client.render.MimicBakedModel;
import ml.mypals.meshcut.client.render.MimicMesh;
import ml.mypals.meshcut.client.render.MimicTint;
import ml.mypals.meshcut.item.LightsaberItem;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class MeshCutClient {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(MeshCut.MIMIC_BE.get(), MimicRenderer::new);
    }

    /** Swaps the (empty) baked model of every mimic block state for one that draws the cut block. */
    @SubscribeEvent
    public static void wrapMimicModels(ModelEvent.ModifyBakingResult event) {
        event.getModels().replaceAll((id, model) ->
                id.id().getNamespace().equals(MeshCut.MODID) && id.id().getPath().equals("mimic")
                        && !(model instanceof MimicBakedModel) ? new MimicBakedModel(model) : model);
    }

    /** Cut grass stays grass-coloured: tints are forwarded to the imitated block's colour handler. */
    @SubscribeEvent
    public static void registerBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> MimicTint.color(level, pos, tintIndex), MeshCut.MIMIC_BLOCK.get());
    }

    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, index) -> {
            int color = LightsaberItem.getBladeColor(stack);
            return 0xff000000 | (color == -1 ? 0x40ccff : color);
        }, MeshCut.LASER_GUN.get());
        event.register((stack, index) -> {
            int color = LightsaberItem.getBladeColor(stack);
            return 0xff000000 | (color == -1 ? 0x2ed2e7 : color);
        }, MeshCut.LIGHTSABER.get());
    }

    /** Captured meshes hold atlas UVs, so they're stale after any resource reload. */
    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) rm -> {
            ModelCapturer.clearCache();
            MimicMesh.clearCache();
            MimicVbos.clear();
            LaserGunHands.clearModels();
        });
    }

    /**
     * Holding the saber lit counts as using an item, which vanilla slows the player to a fifth for. Swinging a blade
     * should not feel like drawing a bow, so the slowdown (applied right after this event) is cancelled out.
     */
    @SubscribeEvent
    public static void keepSpeedWhileLit(MovementInputUpdateEvent event) {
        if (LightsaberItem.isActive(event.getEntity()) && !event.getEntity().isPassenger()) {
            event.getInput().leftImpulse *= 5f;
            event.getInput().forwardImpulse *= 5f;
        }
    }

    /**
     * Vanilla spawns break particles for every box of a block's shape, and a cut block's shape is made of a hundred or
     * so boxes. The particles are made for the block it imitates instead, whose shape is its own.
     */
    @SubscribeEvent
    public static void registerClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerBlock(new IClientBlockExtensions() {
            @Override
            public boolean addDestroyEffects(BlockState state, Level level, BlockPos pos, ParticleEngine manager) {
                if (level.getBlockEntity(pos) instanceof MimicBlockEntity be && !(be.getTargetState().getBlock() instanceof MimicBlock)
                        && !be.getTargetState().isAir()) {
                    manager.destroy(pos, be.getTargetState());
                }
                return true;
            }
        }, MeshCut.MIMIC_BLOCK.get());
    }

    private MeshCutClient() {}
}
