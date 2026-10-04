package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.Blade;
import ml.mypals.meshcut.geom.SaberScorch;
import ml.mypals.meshcut.item.LightsaberItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class LightsaberBurns {
    private static final double CHAR_LIFETIME = 100;
    private static final double EMBER_LIFETIME = 50;
    private static final double CHAR_HALF_WIDTH = 0.040;
    private static final double EMBER_HALF_WIDTH = 0.014;
    private static final double LIFT = 0.0025;
    private static final double SUB_LEVEL_LIFT = 4;
    private static final int MAX_MARKS = 1800;
    private static final int MAX_CANDIDATE_BLOCKS = 1024;
    private static final int[][] SWEEP_TRIANGLES = {{0, 1, 2}, {0, 2, 3}};
    private static final ArrayDeque<Mark> MARKS = new ArrayDeque<>();
    private static final Map<UUID, Blade> LAST_BLADE = new HashMap<>();
    @Nullable private static ClientLevel burnLevel;

    private static final long CLOSING_TICKS = 100;

    private static final class Mark {
        final long time;
        final float[] charred, ember;
        final Vec3 normal;
        final BlockPos block;
        final Object version;
        @Nullable final UUID subLevel;
        /** When the block changed under the mark (-1 while it has not), and how hot the mark was then. */
        long closedAt = -1;
        float closedHeat;

        Mark(long time, float[] charred, float[] ember, Vec3 normal, BlockPos block, Object version, @Nullable UUID subLevel) {
            this.time = time;
            this.charred = charred;
            this.ember = ember;
            this.normal = normal;
            this.block = block;
            this.version = version;
            this.subLevel = subLevel;
        }

        float heat(double now) {
            double age = now - time;
            float heat = (float) Math.max(0, 1 - age / EMBER_LIFETIME);
            return closedAt < 0 ? heat : (float) (closedHeat * Math.max(0, 1 - (now - closedAt) / CLOSING_TICKS));
        }

        /** 1 while open; falls to 0 as the mark closes. */
        float open(double now) {
            return closedAt < 0 ? 1 : (float) Math.max(0, 1 - (now - closedAt) / CLOSING_TICKS);
        }
    }
    private record Contact(Vec3 position, Vec3 normal) {}

    static void clear() {
        MARKS.clear();
        LAST_BLADE.clear();
        LightsaberSurfaces.clear();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != burnLevel) {
            clear();
            burnLevel = level;
        }
        if (level == null) return;
        long now = level.getGameTime();
        while (!MARKS.isEmpty() && now - MARKS.peekFirst().time > CHAR_LIFETIME) MARKS.pollFirst();
        // Geometry invalidation needs a tick check, not thousands of block-entity lookups per render frame.
        MARKS.removeIf(mark -> {
            if (LightsaberSurfaces.version(level, mark.block) == mark.version) return false;
            if (level.getBlockState(mark.block).isAir()) return true; // the block went with a fragment
            if (mark.closedAt < 0) { // cut: the mark cools and darkens for a few seconds instead of vanishing at once
                mark.closedHeat = mark.heat(now);
                mark.closedAt = now;
            }
            return now - mark.closedAt > CLOSING_TICKS;
        });
        LAST_BLADE.keySet().removeIf(id -> level.getPlayerByUUID(id) == null);
        var container = SubLevelContainer.getContainer(level);
        for (Player player : level.players()) {
            if (!LightsaberItem.isActive(player)) {
                LAST_BLADE.remove(player.getUUID());
                continue;
            }
            Blade blade = Blade.of(player, 1f);
            Blade before = LAST_BLADE.put(player.getUUID(), blade);
            if (player.distanceToSqr(minecraft.gameRenderer.getMainCamera().getPosition()) > 48 * 48
                    || blade.direction().lengthSqr() < 1e-4) continue;
            double movement = before == null ? 0 : Math.max(before.tip().distanceTo(blade.tip()), before.base().distanceTo(blade.base()));
            if (movement > 8) continue;
            Vec3[] sweep;
            if (movement < 0.01) {
                // A stationary contact still chars the surface. Refresh it at 5 Hz without accumulating every tick.
                if (before != null && now % 4 != 0) continue;
                Vec3 direction = blade.direction().normalize();
                Vec3 helper = Math.abs(direction.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
                Vec3 side = direction.cross(helper).normalize().scale(0.008);
                sweep = new Vec3[]{blade.base().subtract(side), blade.tip().subtract(side),
                        blade.tip().add(side), blade.base().add(side)};
            } else {
                sweep = new Vec3[]{before.base(), before.tip(), blade.tip(), blade.base()};
            }
            Contact contact = collect(level, container, sweep, now, null, null);
            BoundingBox3d bounds = bounds(sweep);
            for (SubLevel sub : Sable.HELPER.getAllIntersecting(level, bounds)) {
                if (!(sub instanceof ClientSubLevel client) || client.isRemoved()) continue;
                Pose3dc pose = client.renderPose(1f);
                Vec3[] inPlot = new Vec3[4];
                for (int i = 0; i < 4; i++) inPlot[i] = pose.transformPositionInverse(sweep[i]);
                Contact next = collect(level, container, inPlot, now, client.getUniqueId(), pose);
                if (next != null && (contact == null || next.position.distanceToSqr(blade.base())
                        < contact.position.distanceToSqr(blade.base()))) contact = next;
            }
            if (contact != null) emitContactParticles(level, contact, before == null ? Vec3.ZERO : blade.tip().subtract(before.tip()));
        }
    }

    @Nullable
    private static Contact collect(ClientLevel level, @Nullable SubLevelContainer container, Vec3[] sweep,
                                    long time, @Nullable UUID subLevel, @Nullable Pose3dc pose) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (Vec3 p : sweep) {
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z);
        }
        BlockPos min = BlockPos.containing(minX - CHAR_HALF_WIDTH, minY - CHAR_HALF_WIDTH, minZ - CHAR_HALF_WIDTH);
        BlockPos max = BlockPos.containing(maxX + CHAR_HALF_WIDTH, maxY + CHAR_HALF_WIDTH, maxZ + CHAR_HALF_WIDTH);
        long cells = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        if (cells > MAX_CANDIDATE_BLOCKS) return null;
        Contact closest = null;
        Vec3 origin = pose == null ? sweep[0] : pose.transformPosition(sweep[0]);
        for (BlockPos cursor : BlockPos.betweenClosed(min, max)) {
            boolean inPlot = container != null && container.inBounds(cursor);
            if (inPlot != (subLevel != null) || !level.hasChunkAt(cursor) || level.getBlockState(cursor).isAir()) continue;
            if (subLevel != null) {
                SubLevel owner = Sable.HELPER.getContaining(level, cursor);
                if (owner == null || !subLevel.equals(owner.getUniqueId())) continue;
            }
            BlockPos block = cursor.immutable();
            Vector3d[] quad = new Vector3d[4];
            for (int i = 0; i < 4; i++) quad[i] = new Vector3d(sweep[i].x - block.getX(),
                    sweep[i].y - block.getY(), sweep[i].z - block.getZ());
            Object version = LightsaberSurfaces.version(level, block);
            for (var face : LightsaberSurfaces.at(level, block)) {
                for (int[] indices : SWEEP_TRIANGLES) {
                    Vector3d[] triangle = {quad[indices[0]], quad[indices[1]], quad[indices[2]]};
                    float[] charred = SaberScorch.project(face, triangle, CHAR_HALF_WIDTH);
                    if (charred.length < 9) continue;
                    float[] ember = SaberScorch.project(face, triangle, EMBER_HALF_WIDTH);
                    Vec3 normal = new Vec3(face.nx(), face.ny(), face.nz()).normalize();
                    MARKS.addLast(new Mark(time, charred, ember, normal, block, version, subLevel));
                    while (MARKS.size() > MAX_MARKS) MARKS.pollFirst();
                    Vec3 position = centroid(charred).add(block.getX(), block.getY(), block.getZ());
                    if (pose != null) {
                        position = pose.transformPosition(position);
                        normal = pose.transformNormal(normal).normalize();
                    }
                    if (closest == null || position.distanceToSqr(origin) < closest.position.distanceToSqr(origin))
                        closest = new Contact(position, normal);
                }
            }
        }
        return closest;
    }

    private static BoundingBox3d bounds(Vec3[] points) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (Vec3 p : points) {
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z);
        }
        return new BoundingBox3d(minX - CHAR_HALF_WIDTH, minY - CHAR_HALF_WIDTH, minZ - CHAR_HALF_WIDTH,
                maxX + CHAR_HALF_WIDTH, maxY + CHAR_HALF_WIDTH, maxZ + CHAR_HALF_WIDTH);
    }

    private static Vec3 centroid(float[] vertices) {
        Vec3 sum = Vec3.ZERO;
        for (int i = 0; i < vertices.length; i += 3) sum = sum.add(vertices[i], vertices[i + 1], vertices[i + 2]);
        return sum.scale(3.0 / vertices.length);
    }

    private static void emitContactParticles(ClientLevel level, Contact contact, Vec3 swing) {
        Vec3 normal = contact.normal;
        Vec3 position = contact.position.add(normal.scale(0.06));
        Vec3 drag = swing.subtract(normal.scale(swing.dot(normal))).normalize().scale(0.035);
        var random = level.random;
        int sparks = 4 + random.nextInt(3);
        for (int i = 0; i < sparks; i++) {
            Vec3 scatter = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
            Vec3 tangent = scatter.subtract(normal.scale(scatter.dot(normal))).scale(0.055);
            Vec3 velocity = normal.scale(0.08 + random.nextDouble() * 0.10).add(tangent).add(drag);
            // Vanilla electric sparks multiply the supplied velocity by 0.25.
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, position.x, position.y, position.z,
                    velocity.x * 4, velocity.y * 4, velocity.z * 4);
        }
        if (random.nextFloat() < 0.4f)
            level.addParticle(ParticleTypes.LAVA, position.x, position.y, position.z, 0, 0, 0);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || MARKS.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        double time = level.getGameTime() + partial;
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f matrix = event.getPoseStack().last().pose();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        var container = SubLevelContainer.getContainer(level);
        VertexConsumer consumer = buffers.getBuffer(LightsaberRenderTypes.SCORCH);
        VertexConsumer glow = Bloom.usable() ? Bloom.consumer(BloomRenderTypes.TRIANGLES) : null; // the embers also glow
        for (Mark mark : MARKS) {
            Pose3dc pose = null;
            if (mark.subLevel != null) {
                SubLevel sub = container == null ? null : container.getSubLevel(mark.subLevel);
                if (!(sub instanceof ClientSubLevel client) || client.isRemoved()) continue;
                pose = client.renderPose(partial);
            }
            float fade = (float) Math.pow(Math.max(0, 1 - (time - mark.time) / CHAR_LIFETIME), 1.5);
            polygon(consumer, matrix, camera, pose, mark, mark.charred, LIFT, 0.05f, 0.03f, 0.025f, fade * 0.85f * mark.open(time));
            float cooling = mark.heat(time);
            if (cooling > 0) {
                float g = 0.25f + 0.55f * cooling, b = 0.05f + 0.35f * cooling * cooling, a = cooling * cooling * 0.9f;
                polygon(consumer, matrix, camera, pose, mark, mark.ember, LIFT * 1.5, 1, g, b, a);
                if (glow != null) polygon(glow, matrix, camera, pose, mark, mark.ember, LIFT * 1.5, 1, g, b, a);
            }
        }
        buffers.endBatch(LightsaberRenderTypes.SCORCH);
        if (glow != null) Bloom.flush(BloomRenderTypes.TRIANGLES);
    }

    private static void polygon(VertexConsumer consumer, Matrix4f matrix, Vec3 camera, @Nullable Pose3dc pose,
                                 Mark mark, float[] vertices, double lift, float r, float g, float b, float alpha) {
        for (int i = 1; i + 1 < vertices.length / 3; i++) {
            for (int j : new int[]{0, i, i + 1}) {
                // in double: a sub-level's plot lies some twenty million blocks out, where a float misses by whole blocks
                Vec3 p = new Vec3((double) vertices[j * 3] + mark.block.getX(), (double) vertices[j * 3 + 1] + mark.block.getY(),
                        (double) vertices[j * 3 + 2] + mark.block.getZ()).add(mark.normal.scale(pose == null ? lift : lift * SUB_LEVEL_LIFT));
                if (pose != null) p = pose.transformPosition(p);
                p = p.subtract(camera);
                consumer.addVertex(matrix, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, alpha);
            }
        }
    }

    private LightsaberBurns() {}
}
