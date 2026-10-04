package ml.mypals.meshcut.physics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.physics.floating_block.FloatingBlockController;
import dev.ryanhcode.sable.physics.floating_block.FloatingClusterContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ml.mypals.meshcut.mixin.FloatingBlockControllerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;


public final class MimicFloating {
    private static final class Entry {
        BlockState state;
        double fraction;
        double applied = Double.NaN;
        boolean gone;

        Entry(BlockState state, double fraction) {
            this.state = state;
            this.fraction = fraction;
        }
    }

    private record Readd(FloatingBlockController controller, FloatingClusterContainer container, BlockPos pos,
                         BlockState state, long at) {}

    private static final Map<FloatingClusterContainer, Map<Long, Entry>> ENTRIES = new WeakHashMap<>();
    private static final List<Readd> READD = new ArrayList<>();
    private static final ThreadLocal<Double> SCALE = new ThreadLocal<>();

    public static void register() {
        NeoForge.EVENT_BUS.addListener(MimicFloating::serverTick);
    }

    public static void update(@Nullable Level level, BlockPos pos, @Nullable BlockState target, double fraction) {
        if (!(level instanceof ServerLevel server)
                || !(Sable.HELPER.getContaining(server, pos) instanceof ServerSubLevel sub) || sub.isRemoved()) {
            return;
        }
        FloatingBlockController controller = sub.getFloatingBlockController();
        if (controller == null) {
            return;
        }
        FloatingClusterContainer container = ((FloatingBlockControllerAccessor) controller).meshcut$container();
        Map<Long, Entry> entries = ENTRIES.computeIfAbsent(container, k -> new HashMap<>());
        long key = pos.asLong();
        Entry entry = entries.get(key);
        BlockState now = target != null && PhysicsBlockPropertyHelper.getFloatingMaterial(target) != null ? target : null;
        if (entry == null || entry.gone) {
            if (now != null) {
                Entry fresh = new Entry(now, fraction);
                if (entry != null) {
                    fresh.applied = entry.applied; // still being let go: that is done with what it was taken in with
                }
                entries.put(key, fresh);
                controller.queueAddFloatingBlock(now, pos);
            }
            return;
        }
        if (now == null) {
            entry.gone = true;
            controller.queueRemoveFloatingBlock(entry.state, pos);
        } else if (!now.equals(entry.state)) {
            BlockState old = entry.state;
            entry.state = now;
            entry.fraction = fraction;
            controller.queueRemoveFloatingBlock(old, pos);
            controller.queueAddFloatingBlock(now, pos);
        } else if (Math.abs(fraction - entry.fraction) > 1e-6) {
            entry.fraction = fraction;
            if (!Double.isNaN(entry.applied)) { // taken in already: let go, and taken in again with the new fraction
                controller.queueRemoveFloatingBlock(now, pos);
                READD.add(new Readd(controller, container, pos.immutable(), now, server.getGameTime() + 2));
            }
        }
    }

    private static void serverTick(ServerTickEvent.Post event) {
        if (READD.isEmpty()) {
            return;
        }
        long now = event.getServer().overworld().getGameTime();
        READD.removeIf(r -> {
            if (r.at() > now) {
                return false;
            }
            Entry entry = entry(r.container(), r.pos().asLong());
            if (entry != null && !entry.gone && entry.state.equals(r.state())) { // still there, still the same
                r.controller().queueAddFloatingBlock(r.state(), r.pos());
            }
            return true;
        });
    }

    public static void takingIn(FloatingClusterContainer container, long pos) {
        Entry entry = entry(container, pos);
        if (entry != null && !entry.gone) {
            entry.applied = entry.fraction;
            SCALE.set(entry.fraction);
        } else {
            SCALE.remove();
        }
    }

    public static void lettingGo(FloatingClusterContainer container, long pos) {
        Entry entry = entry(container, pos);
        if (entry != null && !Double.isNaN(entry.applied)) {
            SCALE.set(entry.applied);
            entry.applied = Double.NaN;
            if (entry.gone) {
                ENTRIES.get(container).remove(pos);
            }
        } else {
            SCALE.remove();
        }
    }

    public static void done() {
        SCALE.remove();
    }

    public static double scale(double scale) {
        Double fraction = SCALE.get();
        return fraction == null ? scale : scale * fraction;
    }

    @Nullable
    private static Entry entry(FloatingClusterContainer container, long pos) {
        Map<Long, Entry> entries = ENTRIES.get(container);
        return entries == null ? null : entries.get(pos);
    }

    private MimicFloating() {}
}
