package ml.mypals.meshcut.block;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * The part of a cut-up entity one block holds: what the entity looked like ({@link #look}) and where in that block it
 * was. The body of a creature that was cut becomes a few blocks; each draws the whole entity, moved so that it is where
 * it was, and keeps only what is inside it.
 *
 * @param origin where the entity's own origin (its feet) was, relative to the lowest corner of this block
 * @param bounds the part of the entity's box inside this block, {minX, minY, minZ, maxX, maxY, maxZ} in block space:
 *               what there is of it to cut, and what it collides with
 */
public record EntityAppearance(Look look, double[] origin, double[] bounds) {
    /**
     * Everything that decides how the entity is drawn; the same for all blocks of one body, so one recording serves
     * them all.
     *
     * @param type    the entity type's id
     * @param data    the entity's saved data (equipment, variant, armour stand pose, ...)
     * @param profile for a player, whose skin to show; else null
     */
    public record Look(String type, CompoundTag data, float bodyYaw, float headYaw, float pitch,
                       @Nullable UUID profileId, @Nullable String profileName) {}

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", look.type());
        tag.put("data", look.data());
        tag.putFloat("bodyYaw", look.bodyYaw());
        tag.putFloat("headYaw", look.headYaw());
        tag.putFloat("pitch", look.pitch());
        if (look.profileId() != null) {
            tag.putUUID("profileId", look.profileId());
        }
        if (look.profileName() != null) {
            tag.putString("profileName", look.profileName());
        }
        tag.put("origin", doubles(origin));
        tag.put("bounds", doubles(bounds));
        return tag;
    }

    @Nullable
    public static EntityAppearance load(CompoundTag tag) {
        if (!tag.contains("type")) {
            return null;
        }
        Look look = new Look(tag.getString("type"), tag.getCompound("data"), tag.getFloat("bodyYaw"), tag.getFloat("headYaw"),
                tag.getFloat("pitch"), tag.hasUUID("profileId") ? tag.getUUID("profileId") : null,
                tag.contains("profileName") ? tag.getString("profileName") : null);
        double[] origin = read(tag.getList("origin", Tag.TAG_DOUBLE), 3);
        double[] bounds = read(tag.getList("bounds", Tag.TAG_DOUBLE), 6);
        return origin == null || bounds == null ? null : new EntityAppearance(look, origin, bounds);
    }

    private static ListTag doubles(double[] values) {
        ListTag list = new ListTag();
        for (double v : values) {
            list.add(DoubleTag.valueOf(v));
        }
        return list;
    }

    @Nullable
    private static double[] read(ListTag list, int size) {
        if (list.size() != size) {
            return null;
        }
        double[] out = new double[size];
        for (int i = 0; i < size; i++) {
            out[i] = list.getDouble(i);
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EntityAppearance a && look.equals(a.look) && Arrays.equals(origin, a.origin) && Arrays.equals(bounds, a.bounds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(look, Arrays.hashCode(origin), Arrays.hashCode(bounds));
    }
}
