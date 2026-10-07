package ml.mypals.meshcut.client;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.MimicBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.HashSet;
import java.util.Set;

@EventBusSubscriber(modid=MeshCut.MODID,value=Dist.CLIENT)
public final class CutSyncRecovery {
    private static Object world;
    private static final Set<BlockPos> pending=new HashSet<>();
    private static void syncWorld(){var now=Minecraft.getInstance().level;if(world!=now){pending.clear();world=now;}}
    public static void track(BlockPos pos){syncWorld();pending.add(pos.immutable());}
    public static void forget(BlockPos pos){syncWorld();pending.remove(pos);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        syncWorld();var level=Minecraft.getInstance().level;if(level==null)return;
        var iterator=pending.iterator();
        while(iterator.hasNext()) {
            BlockPos pos=iterator.next();
            if(!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof MimicBlockEntity be))iterator.remove();
            else be.retrySyncSnapshot();
        }
    }
    private CutSyncRecovery(){}
}
