package ml.mypals.meshcut;

import com.mojang.serialization.Codec;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.command.MeshCutCommand;
import ml.mypals.meshcut.cut.SlashService;
import ml.mypals.meshcut.item.LightsaberItem;
import ml.mypals.meshcut.item.LightsaberMotion;
import ml.mypals.meshcut.physics.MimicColliders;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import java.util.function.Supplier;

@Mod(MeshCut.MODID)
public class MeshCut {
    public static final String MODID = "meshcut";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    private static final DeferredRegister<net.minecraft.world.item.CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    public static final DeferredBlock<MimicBlock> MIMIC_BLOCK = BLOCKS.registerBlock("mimic",
            MimicBlock::new, BlockBehaviour.Properties.of().strength(1.5f).noOcclusion()
                    // glows like what it imitates (magma, for instance, is drawn at full brightness)
                    .emissiveRendering((state, level, pos) -> level.getBlockEntity(pos) instanceof ml.mypals.meshcut.block.MimicBlockEntity be
                            && !(be.getTargetState().getBlock() instanceof MimicBlock) && be.getTargetState().emissiveRendering(level, pos))
                    // the shape depends on the block entity; without this vanilla caches one shape per state
                    .dynamicShape());

    /**
     * What the parts of a cut-up entity imitate: never placed in the world, it gives them their weight (see its
     * physics properties) and the texture of their cut faces.
     */
    public static final DeferredBlock<net.minecraft.world.level.block.Block> ENTITY_BODY = BLOCKS.registerSimpleBlock("entity_body",
            BlockBehaviour.Properties.of().strength(0.5f).noLootTable().sound(net.minecraft.world.level.block.SoundType.SLIME_BLOCK));

    public static final Supplier<BlockEntityType<MimicBlockEntity>> MIMIC_BE = BLOCK_ENTITIES.register("mimic",
            () -> BlockEntityType.Builder.of(MimicBlockEntity::new, MIMIC_BLOCK.get()).build(null));

    /** Whether a lightsaber is switched on. */
    public static final Supplier<DataComponentType<Boolean>> SABER_ACTIVE = COMPONENTS.registerComponentType("active",
            builder -> builder.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL));

    public static final Supplier<DataComponentType<Float>> SABER_LENGTH = COMPONENTS.registerComponentType("blade_length",
            builder -> builder.persistent(Codec.floatRange(0.25f, 2f)).networkSynchronized(ByteBufCodecs.FLOAT));

    public static final DeferredItem<LightsaberItem> LIGHTSABER = ITEMS.register("lightsaber",
            () -> new LightsaberItem(new Item.Properties().stacksTo(1)));

    public static final Supplier<DataComponentType<Integer>> LASER_RADIUS = COMPONENTS.registerComponentType("laser_radius",
            builder -> builder.persistent(Codec.intRange(1, 4)).networkSynchronized(ByteBufCodecs.VAR_INT));

    public static final DeferredItem<ml.mypals.meshcut.item.LaserGunItem> LASER_GUN = ITEMS.register("laser_gun",
            () -> new ml.mypals.meshcut.item.LaserGunItem(new Item.Properties().stacksTo(1)));

    public static final Supplier<net.minecraft.world.item.CreativeModeTab> CREATIVE_TAB = TABS.register("weapons", () ->
            net.minecraft.world.item.CreativeModeTab.builder()
                    .title(net.minecraft.network.chat.Component.translatable("itemGroup.meshcut"))
                    .icon(() -> LIGHTSABER.get().getDefaultInstance())
                    .displayItems((parameters, output) -> { output.accept(LIGHTSABER.get()); output.accept(LASER_GUN.get()); })
                    .build());

    public MeshCut(IEventBus modEventBus, net.neoforged.fml.ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, MeshCutConfig.SPEC);
        BLOCKS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        ITEMS.register(modEventBus);
        TABS.register(modEventBus);
        COMPONENTS.register(modEventBus);
        LightsaberMotion.register(modEventBus);
        modEventBus.addListener(ml.mypals.meshcut.network.EntityPartsNet::register);
        modEventBus.addListener(ml.mypals.meshcut.network.GrabNet::register);
        ml.mypals.meshcut.physics.FragmentGrab.register();
        ml.mypals.meshcut.physics.MimicFloating.register();
        ml.mypals.meshcut.physics.RagdollJoints.register();
        ml.mypals.meshcut.physics.Debris.register();
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) ->
                MeshCutCommand.register(e.getDispatcher(), e.getBuildContext()));
        NeoForge.EVENT_BUS.addListener(MimicColliders::onServerTick);
        NeoForge.EVENT_BUS.addListener(SlashService::onServerTick);
        NeoForge.EVENT_BUS.addListener(ml.mypals.meshcut.cut.EntityCutting::onServerTick);
        NeoForge.EVENT_BUS.addListener(ml.mypals.meshcut.cut.EntityCutting::onInvulnerabilityCheck);
        // what is queued refers to the levels of the server that is stopping (in single player, the next world
        // would run it against them)
        NeoForge.EVENT_BUS.addListener(ml.mypals.meshcut.cut.EntityCutting::onServerStopping);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e) -> {
            SlashService.clear();
            MimicColliders.clear();
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Unload e) -> {
            if (e.getLevel() instanceof net.minecraft.server.level.ServerLevel server) {
                ml.mypals.meshcut.cut.graph.WorldGraphs.onChunkUnload(server, e.getChunk().getPos().x, e.getChunk().getPos().z);
            }
        });
    }
}
