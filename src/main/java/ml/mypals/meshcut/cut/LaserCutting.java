package ml.mypals.meshcut.cut;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.block.MimicShapes;
import ml.mypals.meshcut.geom.CellGrid;
import ml.mypals.meshcut.geom.PolygonCapsule;
import ml.mypals.meshcut.geom.Polytope;
import ml.mypals.meshcut.physics.RagdollJoints;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.List;

/** Removes one continuous polygon capsule across every block it overlaps in a world or body plot. */
public final class LaserCutting {
    public static void vaporizeImpact(ServerLevel level, SubLevel primary, Vec3 a, Vec3 b, int radius) {
        double r=PolygonCapsule.radius(radius)*1.1;
        var bounds=new BoundingBox3d(
                new Vec3(Math.min(a.x,b.x)-r,Math.min(a.y,b.y)-r,Math.min(a.z,b.z)-r),
                new Vec3(Math.max(a.x,b.x)+r,Math.max(a.y,b.y)+r,Math.max(a.z,b.z)+r));
        // Snapshot candidates before edits can remove or split a body.
        List<SubLevel> subs=new ArrayList<>();
        for(var body:Sable.HELPER.getAllIntersecting(level,bounds)) subs.add(body);
        vaporize(level,null,a,b,radius);
        if(primary!=null && !subs.contains(primary)) subs.add(primary);
        for(var body:subs) vaporize(level,body,a,b,radius);
    }
    public static void vaporize(ServerLevel level, SubLevel sub, Vec3 worldA, Vec3 worldB, int radius) {
        Vector3d a = new Vector3d(worldA.x,worldA.y,worldA.z), b = new Vector3d(worldB.x,worldB.y,worldB.z);
        if (sub != null) {
            if (sub.isRemoved()) return;
            sub.logicalPose().transformPositionInverse(a);
            sub.logicalPose().transformPositionInverse(b);
        }
        double r = PolygonCapsule.radius(radius);
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
            var pos=new BlockPos(x,y,z);
            if(!level.hasChunkAt(pos) || Sable.HELPER.getContaining(level,pos)!=sub) continue;
            var local=PolygonCapsule.local(capsule,x,y,z);
            if(Polytope.volume(local)>1e-8 && CutService.pieceAt(level,pos,CapMode.LAVA)!=null) affected.add(pos);
        }
        if(affected.isEmpty()) return;
        if(sub instanceof ServerSubLevel server) RagdollJoints.noteSplit(level,server,null);
        for(var pos:affected) {
            if(sub!=null && sub.isRemoved()) break;
            vaporizeBlock(level,pos,PolygonCapsule.local(capsule,pos.getX(),pos.getY(),pos.getZ()));
        }
    }

    private static void vaporizeBlock(ServerLevel level, BlockPos pos, List<CutPlane> hole) {
        var piece=CutService.pieceAt(level,pos,CapMode.LAVA);
        if(piece==null) return;
        var shape=level.getBlockEntity(pos) instanceof MimicBlockEntity be ? be.cutBoxes() : MimicShapes.cutBoxes(piece.target());
        boolean intersects=false;
        for(var box:shape) {
            var material=new ArrayList<>(piece.planes());
            material.addAll(hole);
            material.addAll(ml.mypals.meshcut.geom.VoxelFragments.planes(box));
            if(Polytope.volume(material)>1e-8) { intersects=true; break; }
        }
        if(!intersects) return;
        var holes=new ArrayList<>(piece.excluded());
        if(holes.stream().anyMatch(old->CutPlane.sameRegion(old,hole))) return;
        holes.add(hole);
        // Cells still determine support; the region cutting the visible triangles stays polygonal.
        var grid=CellGrid.of(piece.planes(),holes,piece.patches(),shape);
        piece=CutService.detachBlockEntity(level,pos,piece);
        if(grid.filledCells()==0) {
            level.setBlock(pos,level.getFluidState(pos).createLegacyBlock(),3);
        } else {
            boolean glow=MimicBlock.glowingCuts();
            MimicBlock.setGlowingCuts(true);
            try {
                MimicBlock.place(level,pos,piece.target(),piece.planes(),holes,piece.patches(),piece.mode(),piece.beTag(),piece.entity());
            } finally { MimicBlock.setGlowingCuts(glow); }
            SlashService.queueRecheck(level,pos);
        }
        for(var direction:Direction.values()) SlashService.queueRecheck(level,pos.relative(direction));
    }
    private LaserCutting() {}
}
