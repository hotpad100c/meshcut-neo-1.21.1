package ml.mypals.meshcut.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.Blade;
import ml.mypals.meshcut.item.LightsaberItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Scorches the surface where a lightsaber blade passes through it.
 * <p>
 * Cuts are applied when a swing is over, so while swinging this is the feedback: wherever the moving blade meets a
 * block, a line is burnt into its surface — a charred streak that lingers, with a hot ember in the middle that cools
 * and goes out quickly. On a sub-level the line is remembered in the sub-level's own coordinates and moves with it.
 */
@EventBusSubscriber(modid = MeshCut.MODID, value = Dist.CLIENT)
public final class LightsaberBurns {
    /** Ticks the charring stays visible, fading slowly. */
    private static final double CHAR_LIFETIME = 200;
    /** Ticks the glowing ember in it lasts. */
    private static final double EMBER_LIFETIME = 50;
    /** Tip movement per tick below which the blade is held still and burns nothing. */
    private static final double MIN_TIP_MOVEMENT = 0.02;
    /** Hits closer together than this add nothing new to the line. */
    private static final double MIN_SPACING = 0.1;
    /** Hits further apart in time, in space, or on different faces are separate lines. */
    private static final long MAX_GAP_TICKS = 3;
    private static final double MAX_JOIN_DISTANCE = 3.0;
    /** How far a mark sits off the surface, to keep it from fighting the block's own face. */
    private static final double LIFT = 0.012;
    private static final double CHAR_HALF_WIDTH = 0.075;
    private static final double EMBER_HALF_WIDTH = 0.035;
    private static final int MAX_SEGMENTS = 800;

    /**
     * A short stretch of burnt line on a face, from {@code from} to {@code to}. For a sub-level these are in its plot
     * space and {@code subLevel} says which one; otherwise they are world coordinates.
     */
    private record Segment(long time, Vec3 from, Vec3 to, Direction face, @Nullable UUID subLevel) {}

    private record Hit(long time, Vec3 pos, Direction face, @Nullable UUID subLevel) {}

    private static final ArrayDeque<Segment> SEGMENTS = new ArrayDeque<>();
    private static final Map<UUID, Hit> LAST_HIT = new HashMap<>();
    private static final Map<UUID, Blade> LAST_BLADE = new HashMap<>();
    @Nullable private static ClientLevel burnLevel;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != burnLevel) {
            SEGMENTS.clear();
            LAST_HIT.clear();
            LAST_BLADE.clear();
            burnLevel = level;
        }
        if (level == null) return;
        long now = level.getGameTime();
        while (!SEGMENTS.isEmpty() && now - SEGMENTS.peekFirst().time() > CHAR_LIFETIME) {
            SEGMENTS.pollFirst();
        }
        var container = SubLevelContainer.getContainer(level);
        for (Player player : level.players()) {
            UUID id = player.getUUID();
            if (!LightsaberItem.isActive(player)) {
                LAST_BLADE.remove(id);
                LAST_HIT.remove(id);
                continue;
            }
            Blade blade = Blade.of(player, 1f);
            Blade before = LAST_BLADE.put(id, blade);
            if (before == null || before.tip().distanceTo(blade.tip()) < MIN_TIP_MOVEMENT) {
                continue; // held still
            }
            // Sable's clip also tests sub-levels; a hit on one comes back in that sub-level's plot space
            BlockHitResult hit = level.clip(new ClipContext(blade.base(), blade.tip(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK || hit.isInside()) {
                LAST_HIT.remove(id);
                continue;
            }
            UUID subLevel = null;
            if (container != null && container.inBounds(hit.getBlockPos())) {
                SubLevel sub = Sable.HELPER.getContaining(level, hit.getBlockPos());
                if (sub == null) {
                    LAST_HIT.remove(id);
                    continue;
                }
                subLevel = sub.getUniqueId();
            }
            Hit contact = new Hit(now, hit.getLocation(), hit.getDirection(), subLevel);
            if (addHit(id, contact)) {
                emitContactParticles(level, container, contact, blade.tip().subtract(before.tip()));
            }
        }
    }

    private static boolean addHit(UUID id, Hit hit) {
        Hit last = LAST_HIT.get(id);
        if (last != null && sameSurface(last, hit)
                && hit.time() - last.time() <= MAX_GAP_TICKS
                && last.pos().distanceToSqr(hit.pos()) < MIN_SPACING * MIN_SPACING) {
            return false;
        }
        if (last != null && sameSurface(last, hit) && hit.time() - last.time() <= MAX_GAP_TICKS
                && last.pos().distanceTo(hit.pos()) <= MAX_JOIN_DISTANCE) {
            SEGMENTS.addLast(new Segment(hit.time(), last.pos(), hit.pos(), hit.face(), hit.subLevel()));
            if (SEGMENTS.size() > MAX_SEGMENTS) {
                SEGMENTS.pollFirst();
            }
        }
        LAST_HIT.put(id, hit);
        return true;
    }

    /** One small burst per moving contact per tick, independent of render frame rate. */
    private static void emitContactParticles(ClientLevel level, @Nullable SubLevelContainer container,
                                             Hit hit, Vec3 swing) {
        Vec3 position = hit.pos();
        Vec3 normal = Vec3.atLowerCornerOf(hit.face().getNormal());
        if (hit.subLevel() != null) {
            SubLevel sub = container == null ? null : container.getSubLevel(hit.subLevel());
            if (!(sub instanceof ClientSubLevel client)) return;
            Pose3dc pose = client.renderPose(1f);
            position = pose.transformPosition(position);
            normal = pose.transformNormal(normal).normalize();
        }
        // Lift off the collision face so the first particle frame is visible instead of buried in the block.
        position = position.add(normal.scale(0.06));
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
        if (random.nextFloat() < 0.4f) {
            // LAVA supplies its own upward pop and gravity; its provider ignores velocity arguments.
            level.addParticle(ParticleTypes.LAVA, position.x, position.y, position.z, 0, 0, 0);
        }
    }

    private static boolean sameSurface(Hit a, Hit b) {
        return a.face() == b.face() && Objects.equals(a.subLevel(), b.subLevel());
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || SEGMENTS.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        double time = level.getGameTime() + partial;
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f matrix = event.getPoseStack().last().pose();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        var container = SubLevelContainer.getContainer(level);

        VertexConsumer charring = buffers.getBuffer(RenderType.debugQuads());
        for (Segment s : SEGMENTS) {
            float alpha = (float) Math.pow(Math.max(0, 1 - (time - s.time()) / CHAR_LIFETIME), 1.5);
            if (alpha > 0) {
                strip(charring, matrix, camera, container, partial, s, CHAR_HALF_WIDTH, 0.05f, 0.03f, 0.025f, alpha * 0.85f, false);
            }
        }
        buffers.endBatch(RenderType.debugQuads());

        VertexConsumer ember = null;
        for (Segment s : SEGMENTS) {
            float cooling = (float) Math.max(0, 1 - (time - s.time()) / EMBER_LIFETIME);
            if (cooling > 0) {
                if (ember == null) {
                    ember = buffers.getBuffer(RenderType.lightning());
                }
                // from white-hot through orange to a dull red as it cools
                strip(ember, matrix, camera, container, partial, s, EMBER_HALF_WIDTH, 1f, 0.25f + 0.55f * cooling,
                        0.05f + 0.35f * cooling * cooling, cooling * cooling * 0.9f, true);
            }
        }
        if (ember != null) {
            buffers.endBatch(RenderType.lightning());
        }
    }

    /** A flat strip lying on the face the segment is on, lifted slightly off it. */
    private static void strip(VertexConsumer c, Matrix4f m, Vec3 camera, @Nullable SubLevelContainer container, float partial,
                              Segment s, double halfWidth, float r, float g, float b, float a, boolean doubleSided) {
        Vec3 from = s.from();
        Vec3 to = s.to();
        Vec3 normal = new Vec3(s.face().getStepX(), s.face().getStepY(), s.face().getStepZ());
        if (s.subLevel() != null) {
            // follow the sub-level: from its plot space to where it is drawn right now
            SubLevel sub = container == null ? null : container.getSubLevel(s.subLevel());
            if (!(sub instanceof ClientSubLevel client)) {
                return;
            }
            Pose3dc pose = client.renderPose(partial);
            from = pose.transformPosition(from);
            to = pose.transformPosition(to);
            normal = pose.transformNormal(normal);
        }
        Vec3 along = to.subtract(from);
        if (along.lengthSqr() < 1e-8) {
            return;
        }
        Vec3 side = normal.cross(along).normalize().scale(halfWidth);
        Vec3 lift = normal.scale(LIFT).subtract(camera);
        Vec3 p0 = from.add(lift).subtract(side), p1 = from.add(lift).add(side);
        Vec3 p2 = to.add(lift).add(side), p3 = to.add(lift).subtract(side);
        vertex(c, m, p0, r, g, b, a);
        vertex(c, m, p1, r, g, b, a);
        vertex(c, m, p2, r, g, b, a);
        vertex(c, m, p3, r, g, b, a);
        if (doubleSided) {
            vertex(c, m, p3, r, g, b, a);
            vertex(c, m, p2, r, g, b, a);
            vertex(c, m, p1, r, g, b, a);
            vertex(c, m, p0, r, g, b, a);
        }
    }

    private static void vertex(VertexConsumer c, Matrix4f m, Vec3 p, float r, float g, float b, float a) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }

    private LightsaberBurns() {}
}
