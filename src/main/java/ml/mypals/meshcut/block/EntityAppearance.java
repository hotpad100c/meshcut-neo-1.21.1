package ml.mypals.meshcut.block;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The part of a cut-up entity one block holds: what the entity looked like ({@link #look}), which of its limbs this
 * is, and where in that block it was. The body of a creature that was cut becomes a few sub-levels, one per limb, each
 * made of blocks; each block draws its limb, moved so that it is where it was, and keeps only what is inside it.
 * <p>
 * A point {@code p} of the recorded entity (relative to its feet, axes as in the world) is at
 * {@code rotation⁻¹·p + origin} in this block.
 *
 * @param part     the limb, as the client that took the entity apart named it; null for the whole entity
 * @param rotation the limb's orientation as a quaternion {x, y, z, w}
 * @param origin   see above
 * @param boxes    what there is of the limb in this block, each {minX, minY, minZ, maxX, maxY, maxZ} in block space:
 *                 what there is of it to cut, and what it collides with
 */
public record EntityAppearance(Look look, @Nullable String part, double[] rotation, double[] origin, List<double[]> boxes) {
    public static final double[] NO_ROTATION = {0, 0, 0, 1};

    /**
     * Everything that decides how the entity is drawn; the same for all blocks of one body, so one recording serves
     * them all.
     *
     * @param type    the entity type's id
     * @param data    the entity's saved data (equipment, variant, armour stand pose, ...)
     * @param profile for a player, whose skin to show; else null
     */
    public record Look(String type, CompoundTag data, float bodyYaw, float headYaw, float pitch,
                       @Nullable UUID profileId, @Nullable String profileName) {
        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("type", type);
            tag.put("data", data);
            tag.putFloat("bodyYaw", bodyYaw);
            tag.putFloat("headYaw", headYaw);
            tag.putFloat("pitch", pitch);
            if (profileId != null) {
                tag.putUUID("profileId", profileId);
            }
            if (profileName != null) {
                tag.putString("profileName", profileName);
            }
            return tag;
        }

        public static Look load(CompoundTag tag) {
            return new Look(tag.getString("type"), tag.getCompound("data"), tag.getFloat("bodyYaw"), tag.getFloat("headYaw"),
                    tag.getFloat("pitch"), tag.hasUUID("profileId") ? tag.getUUID("profileId") : null,
                    tag.contains("profileName") ? tag.getString("profileName") : null);
        }
    }

    public CompoundTag save() {
        CompoundTag tag = look.save();
        if (part != null) {
            tag.putString("part", part);
        }
        tag.put("rotation", doubles(rotation));
        tag.put("origin", doubles(origin));
        double[] flat = new double[boxes.size() * 6];
        for (int i = 0; i < boxes.size(); i++) {
            System.arraycopy(boxes.get(i), 0, flat, i * 6, 6);
        }
        tag.put("boxes", doubles(flat));
        return tag;
    }

    @Nullable
    public static EntityAppearance load(CompoundTag tag) {
        if (!tag.contains("type")) {
            return null;
        }
        double[] origin = read(tag.getList("origin", Tag.TAG_DOUBLE));
        double[] rotation = tag.contains("rotation") ? read(tag.getList("rotation", Tag.TAG_DOUBLE)) : NO_ROTATION.clone();
        // bodies made before limbs were told apart had one box, "bounds"
        double[] flat = read(tag.getList(tag.contains("boxes") ? "boxes" : "bounds", Tag.TAG_DOUBLE));
        if (origin.length != 3 || rotation.length != 4 || flat.length % 6 != 0) {
            return null;
        }
        List<double[]> boxes = new ArrayList<>(flat.length / 6);
        for (int i = 0; i < flat.length; i += 6) {
            boxes.add(java.util.Arrays.copyOfRange(flat, i, i + 6));
        }
        return new EntityAppearance(Look.load(tag), tag.contains("part") ? tag.getString("part") : null, rotation, origin, boxes);
    }

    private static ListTag doubles(double[] values) {
        ListTag list = new ListTag();
        for (double v : values) {
            list.add(DoubleTag.valueOf(v));
        }
        return list;
    }

    private static double[] read(ListTag list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.getDouble(i);
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof EntityAppearance a) || !look.equals(a.look) || !Objects.equals(part, a.part)
                || !java.util.Arrays.equals(rotation, a.rotation) || !java.util.Arrays.equals(origin, a.origin)
                || boxes.size() != a.boxes.size()) {
            return false;
        }
        for (int i = 0; i < boxes.size(); i++) {
            if (!java.util.Arrays.equals(boxes.get(i), a.boxes.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = Objects.hash(look, part, java.util.Arrays.hashCode(rotation), java.util.Arrays.hashCode(origin));
        for (double[] b : boxes) {
            h = 31 * h + java.util.Arrays.hashCode(b);
        }
        return h;
    }
}
