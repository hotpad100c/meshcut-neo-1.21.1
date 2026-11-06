package ml.mypals.meshcut.cut;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.block.MimicShapes;
import ml.mypals.meshcut.geom.CellGrid;
import ml.mypals.meshcut.geom.HoleReducer;
import ml.mypals.meshcut.geom.PolygonCapsule;
import ml.mypals.meshcut.geom.Polytope;
import ml.mypals.meshcut.physics.RagdollJoints;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.List;

public final class LaserCutting {
    private record Query(long pos,List<CutPlane> capsule){}
    private record Seen(long epoch,long revision){}
    private static final java.util.Map<ServerLevel,java.util.Map<Query,Seen>> REPEATS=new java.util.WeakHashMap<>();
    private static java.util.Map<Query,Seen> repeats(ServerLevel level){
        return REPEATS.computeIfAbsent(level,l->new java.util.LinkedHashMap<>(128,.75f,true){
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<Query,Seen> entry){return size()>1024;}
        });
    }
    private static boolean repeated(ServerLevel level,BlockPos pos,List<CutPlane> capsule){
        if(!(level.getBlockEntity(pos) instanceof MimicBlockEntity be))return false;
        Seen seen=repeats(level).get(new Query(pos.asLong(),capsule));
        return seen!=null&&seen.epoch==be.syncEpoch()&&seen.revision==be.syncRevision();
    }
    private static void remember(ServerLevel level,BlockPos pos,List<CutPlane> capsule){
        if(level.getBlockEntity(pos) instanceof MimicBlockEntity be)
            repeats(level).put(new Query(pos.asLong(),capsule),new Seen(be.syncEpoch(),be.syncRevision()));
    }
    public static void vaporizeImpact(ServerLevel level, SubLevel primary, Vec3 a, Vec3 b, int radius) {
        double r=PolygonCapsule.radius(radius)*1.1;
        var bounds=new BoundingBox3d(
                new Vec3(Math.min(a.x,b.x)-r,Math.min(a.y,b.y)-r,Math.min(a.z,b.z)-r),
                new Vec3(Math.max(a.x,b.x)+r,Math.max(a.y,b.y)+r,Math.max(a.z,b.z)+r));
        List<SubLevel> subs=new ArrayList<>();
        for(var body:Sable.HELPER.getAllIntersecting(level,bounds)) subs.add(body);
        vaporize(level,null,a,b,radius);
        if(primary!=null && !subs.contains(primary)) subs.add(primary);
        for(var body:subs) vaporize(level,body,a,b,radius);
    }
    public static void vaporize(ServerLevel level, SubLevel sub, Vec3 worldA, Vec3 worldB, int radius) {
        vaporize(level, sub, worldA, worldB, PolygonCapsule.radius(radius));
    }

    public static void vaporize(ServerLevel level, SubLevel sub, Vec3 worldA, Vec3 worldB, double r) {
        try (var timing = ml.mypals.meshcut.util.CutTiming.begin(); var batch = ml.mypals.meshcut.cut.graph.WorldGraphs.batch(level)) {
            vaporizeBlocks(level, sub, worldA, worldB, r);
        }
    }

    private static void vaporizeBlocks(ServerLevel level, SubLevel sub, Vec3 worldA, Vec3 worldB, double r) {
        Vector3d a = new Vector3d(worldA.x,worldA.y,worldA.z), b = new Vector3d(worldB.x,worldB.y,worldB.z);
        if (sub != null) {
            if (sub.isRemoved()) return;
            sub.logicalPose().transformPositionInverse(a);
            sub.logicalPose().transformPositionInverse(b);
        }
        var capsule = PolygonCapsule.planes(a,b,r);
        if (capsule.isEmpty()) return;
        double reach=r*1.1;
        int minX=(int)Math.floor(Math.min(a.x,b.x)-reach), maxX=(int)Math.floor(Math.max(a.x,b.x)+reach);
        int minY=(int)Math.floor(Math.min(a.y,b.y)-reach), maxY=(int)Math.floor(Math.max(a.y,b.y)+reach);
        int minZ=(int)Math.floor(Math.min(a.z,b.z)-reach), maxZ=(int)Math.floor(Math.max(a.z,b.z)+reach);
        if (sub != null) {
            var bounds=sub.getPlot().getBoundingBox();
            minX=Math.max(minX,bounds.minX()); maxX=Math.min(maxX,bounds.maxX());
            minY=Math.max(minY,bounds.minY()); maxY=Math.min(maxY,bounds.maxY());
            minZ=Math.max(minZ,bounds.minZ()); maxZ=Math.min(maxZ,bounds.maxZ());
        }
        List<BlockPos> affected=new ArrayList<>();
        for(int x=minX;x<=maxX;x++) for(int y=minY;y<=maxY;y++) for(int z=minZ;z<=maxZ;z++) {
            double near=r*1.072+0.8661;
            if(distanceSquared(a,b,x+0.5,y+0.5,z+0.5)>near*near) continue;
            var pos=new BlockPos(x,y,z);
            if(!level.hasChunkAt(pos) || Sable.HELPER.getContaining(level,pos)!=sub) continue;
            if(repeated(level,pos,capsule))continue;
            if(CutService.pieceAt(level,pos,CapMode.LAVA)!=null) affected.add(pos);
        }
        if(affected.isEmpty()) return;
        if(sub instanceof ServerSubLevel server) RagdollJoints.noteSplit(level,server,null);
        List<BlockPos> changed=new ArrayList<>();
        for(var pos:affected) {
            if(sub!=null && sub.isRemoved()) break;
            if(vaporizeBlock(level,pos,PolygonCapsule.local(capsule,pos.getX(),pos.getY(),pos.getZ()))) changed.add(pos);
            remember(level,pos,capsule);
        }
        Vector3d axis=new Vector3d(b).sub(a);
        if(axis.lengthSquared()<1e-12) axis.set(0,0,1); else axis.normalize();
        Vector3d side=axis.cross(Math.abs(axis.y)>0.9 ? new Vector3d(1,0,0) : new Vector3d(0,1,0),new Vector3d()).normalize();
        SlashService.settleCut(level,sub instanceof ServerSubLevel server ? server : null,changed,side,side.dot(a));
    }

    private static boolean vaporizeBlock(ServerLevel level, BlockPos pos, List<CutPlane> hole) {
        var reduced=HoleReducer.reduce(hole);
        if(reduced==null) return false;
        var piece=CutService.pieceAt(level,pos,CapMode.LAVA);
        if(piece==null) return false;
        var shape=level.getBlockEntity(pos) instanceof MimicBlockEntity be ? be.cutBoxes() : MimicShapes.cutBoxes(piece.target());
        var prepared=ml.mypals.meshcut.geom.CapsuleCut.prepare(piece.planes(),piece.excluded(),piece.patches(),shape,reduced);
        if(prepared==null)return false;
        var holes=prepared.holes();
        var grid=prepared.grid();
        piece=CutService.detachBlockEntity(level,pos,piece);
        if(grid.filledCells()==0) {
            level.setBlock(pos,level.getFluidState(pos).createLegacyBlock(),3);
        } else {
            boolean glow=MimicBlock.glowingCuts();
            MimicBlock.setGlowingCuts(true);
            try {
                MimicBlockEntity.presetGrid(grid);
                MimicBlock.place(level,pos,piece.target(),piece.planes(),holes,piece.patches(),piece.mode(),piece.beTag(),piece.entity(),piece.glow());
            } finally { MimicBlockEntity.presetGrid(null); MimicBlock.setGlowingCuts(glow); }
        }
        return true;
    }
    private static double distanceSquared(Vector3d a, Vector3d b, double x, double y, double z) {
        double dx=b.x-a.x, dy=b.y-a.y, dz=b.z-a.z;
        double length=dx*dx+dy*dy+dz*dz;
        double t=length<1e-12 ? 0 : Math.max(0,Math.min(1,((x-a.x)*dx+(y-a.y)*dy+(z-a.z)*dz)/length));
        double px=a.x+dx*t-x, py=a.y+dy*t-y, pz=a.z+dz*t-z;
        return px*px+py*py+pz*pz;
    }
    private LaserCutting() {}
}
