package ml.mypals.meshcut.item;

import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.cut.Blade;
import ml.mypals.meshcut.cut.CapMode;
import ml.mypals.meshcut.cut.SlashService;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


@EventBusSubscriber(modid = MeshCut.MODID)
public final class LightsaberHandler {
    private static final double MAX_TIP_SPEED = 30;
    private static final double MIN_TIP_SPEED = 0.01;
    private static final int MAX_SWIPES = 72000;

    private static final class Swing {
        final ResourceKey<Level> dimension;
        final Object session;
        int elapsed;
        final List<SlashService.Swipe> swipes = new ArrayList<>();
        final List<Blade> pending = new ArrayList<>();

        void add(Blade from, Blade to) {
            if (!pending.isEmpty() && !pending.getLast().equals(from)) flush();
            if (pending.isEmpty()) pending.add(from);
            pending.add(to);
            if (pending.size() >= 33) flush();
        }

        void flush() {
            if (pending.size() < 2) { pending.clear(); return; }
            List<double[]> points = pending.stream().map(b -> new double[]{b.base().x, b.base().y, b.base().z,
                    b.tip().x, b.tip().y, b.tip().z}).toList();
            List<Integer> retained = ml.mypals.meshcut.cut.StrokeSimplifier.retain(points);
            for (int i = 1; i < retained.size() && swipes.size() < MAX_SWIPES; i++)
                swipes.add(new SlashService.Swipe(pending.get(retained.get(i - 1)), pending.get(retained.get(i))));
            pending.clear();
        }

        Swing(ResourceKey<Level> dimension, Object session) {
            this.dimension = dimension;
            this.session = session;
        }
    }

    private static final double MAX_BASE_DISTANCE = 3.5;
    private static final int MAX_INBOX = 8;
    private static final int SILENT_TICKS = 5;

    private static final Map<UUID, Blade> LAST = new HashMap<>();
    private static final Map<UUID, List<Blade>> INBOX = new HashMap<>();
    private static final Map<UUID, Integer> HEARD = new HashMap<>();

    public static void receive(ServerPlayer player, ml.mypals.meshcut.network.LightsaberNet.Swing swing) {
        if (!LightsaberItem.isActive(player)) {
            return;
        }
        float[] values = {swing.bx(), swing.by(), swing.bz(), swing.dx(), swing.dy(), swing.dz()};
        for (float v : values) {
            if (!Float.isFinite(v)) {
                return;
            }
        }
        net.minecraft.world.phys.Vec3 base = player.position().add(swing.bx(), swing.by(), swing.bz());
        net.minecraft.world.phys.Vec3 direction = new net.minecraft.world.phys.Vec3(swing.dx(), swing.dy(), swing.dz());
        double length = direction.length();
        if (length < 0.05 || length > Blade.LENGTH * LightsaberItem.lengthScale(player.getMainHandItem()) * 1.05 || base.distanceTo(player.getEyePosition()) > MAX_BASE_DISTANCE) {
            return;
        }
        List<Blade> inbox = INBOX.computeIfAbsent(player.getUUID(), k -> new ArrayList<>());
        if (inbox.size() < MAX_INBOX) {
            inbox.add(new Blade(base, base.add(direction)));
        }
        HEARD.put(player.getUUID(), player.tickCount);
    }
    private static final Map<UUID, Swing> SWINGS = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID id = player.getUUID();
        if (!LightsaberItem.isActive(player)) {
            LAST.remove(id);
            INBOX.remove(id);
            HEARD.remove(id);
            finish(player);
            return;
        }
        List<Blade> blades = INBOX.remove(id);
        if (blades == null || blades.isEmpty()) {
            boolean silent = player.tickCount - HEARD.getOrDefault(id, Integer.MIN_VALUE / 2) > SILENT_TICKS;
            blades = silent ? List.of(Blade.of(player, 1f)) : List.of();
        }
        for (Blade now : blades) {
            step(player, id, now);
        }
        Swing swing = SWINGS.get(id);
        if (swing != null && ++swing.elapsed >= ml.mypals.meshcut.MeshCutConfig.SETTLEMENT_TICKS.get()) {
            finish(player);
        }
    }

    private static void step(ServerPlayer player, UUID id, Blade now) {
        Blade before = LAST.put(id, now);
        Swing swing = SWINGS.get(id);
        if (swing != null && (swing.dimension != player.level().dimension() || swing.session != LightsaberMotion.session(player))) {
            SWINGS.remove(id);
            swing = null;
            before = null;
        }
        if (swing == null) {
            swing = new Swing(player.level().dimension(), LightsaberMotion.session(player));
            SWINGS.put(id, swing);
        }
        if (before != null && now.tip().distanceTo(before.tip()) > MAX_TIP_SPEED) return;
        SlashService.touchEntities(player.serverLevel(), before, now, player);
        if (before == null) return;
        double speed = now.tip().distanceTo(before.tip());
        if (speed >= MIN_TIP_SPEED && swing.swipes.size() < MAX_SWIPES) {
            swing.add(before, now);
        }
    }

    public static void release(net.minecraft.world.entity.player.Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            LAST.remove(player.getUUID());
            finish(serverPlayer);
        }
    }

    private static void finish(ServerPlayer player) {
        Swing swing = SWINGS.remove(player.getUUID());
        if (swing == null || swing.dimension != player.level().dimension()) {
            return;
        }
        try {
            swing.flush();
            long start = System.nanoTime();
            var result=SlashService.slashBlocks(player.serverLevel(), swing.swipes, CapMode.LAVA, player);
            if(result!=null && ml.mypals.meshcut.MeshCutSettings.diagnostics()) {
                MeshCut.LOGGER.debug("Applied saber stroke: {} swipes, {} static blocks, {} sub-levels cut, {} sub-levels created",
                        swing.swipes.size(),result.staticBlocks(),result.subLevelsCut(),result.subLevelsCreated());
                if (ml.mypals.meshcut.MeshCutSettings.diagnostics())
                    MeshCut.LOGGER.debug("[meshcut diag] saber settlement: {} ms", (System.nanoTime()-start)/1e6);
            }
        } catch (RuntimeException e) {
            MeshCut.LOGGER.error("Applying a lightsaber swing failed", e);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            finish(player);
        }
        LAST.remove(event.getEntity().getUUID());
        SWINGS.remove(event.getEntity().getUUID());
        INBOX.remove(event.getEntity().getUUID());
        HEARD.remove(event.getEntity().getUUID());
    }

    private LightsaberHandler() {}
}
