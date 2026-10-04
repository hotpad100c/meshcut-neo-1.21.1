package ml.mypals.meshcut.block;

import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Mimics whose fresh cuts still glow: their light is lowered as the lava cools (see
 * {@link MimicBlockEntity#glowLight}). Only these are looked at, a few times a second, on whichever side they are
 * (the client works its lighting out itself), until the lava has gone dark.
 */
final class GlowLight {
    /** How often (in ticks) the light of a glowing mimic is brought in line with its lava. */
    private static final int EVERY = 10;
    private static final Set<MimicBlockEntity> SERVER = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<MimicBlockEntity> CLIENT = Collections.newSetFromMap(new WeakHashMap<>());

    static {
        NeoForge.EVENT_BUS.addListener(GlowLight::tick);
    }

    static void track(MimicBlockEntity be) {
        Level level = be.getLevel();
        if (level != null) {
            (level.isClientSide ? CLIENT : SERVER).add(be);
        }
    }

    private static void tick(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (level.getGameTime() % EVERY != 0) {
            return;
        }
        Set<MimicBlockEntity> glowing = level.isClientSide ? CLIENT : SERVER;
        if (glowing.isEmpty()) {
            return;
        }
        for (MimicBlockEntity be : new ArrayList<>(glowing)) {
            if (be.isRemoved() || be.getLevel() == null) {
                glowing.remove(be);
            } else if (be.getLevel() == level) {
                be.updateLight();
                if (be.glowLight() == 0) {
                    glowing.remove(be); // dark: the light is back to what the imitated block gives off
                }
            }
        }
    }

    private GlowLight() {}
}
