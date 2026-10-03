package ml.mypals.meshcut.cut;

import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One limb of a body that is to be taken apart: a rigid piece with its own frame, made of boxes, hanging from another
 * limb by a joint. Worked out on a client (only clients have the models), see {@code EntityParts}.
 *
 * @param name     the limb's name, so every client can find its faces again; null for a body that is not taken apart
 * @param rotation the limb's orientation, a quaternion {x, y, z, w}
 * @param origin   where the limb's frame starts, relative to the entity's feet (axes as in the world)
 * @param boxes    what the limb is made of, each {minX, minY, minZ, maxX, maxY, maxZ} in the limb's frame
 * @param parent   the limb it hangs from (an index into the same list), or -1
 * @param anchor   where it hangs from it, relative to the entity's feet
 */
public record BodySpec(@Nullable String name, double[] rotation, double[] origin, List<double[]> boxes, int parent,
                       double[] anchor) {
    public static final int MAX_BODIES = 32;
    /** Boxes travel as floats: this many fit in what a client may send (32 KiB) with room to spare. */
    public static final int MAX_BOXES = 1000;

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(name != null);
        if (name != null) {
            buf.writeUtf(name, 64);
        }
        writeDoubles(buf, rotation);
        writeDoubles(buf, origin);
        buf.writeVarInt(boxes.size());
        for (double[] b : boxes) {
            for (double v : b) {
                buf.writeFloat((float) v);
            }
        }
        buf.writeVarInt(parent + 1);
        writeDoubles(buf, anchor);
    }

    public static BodySpec read(FriendlyByteBuf buf) {
        String name = buf.readBoolean() ? buf.readUtf(64) : null;
        double[] rotation = readDoubles(buf, 4);
        double[] origin = readDoubles(buf, 3);
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_BOXES) {
            throw new IllegalArgumentException("Too many boxes: " + count);
        }
        List<double[]> boxes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double[] b = new double[6];
            for (int k = 0; k < 6; k++) {
                b[k] = buf.readFloat();
            }
            boxes.add(b);
        }
        int parent = buf.readVarInt() - 1;
        return new BodySpec(name, rotation, origin, boxes, parent, readDoubles(buf, 3));
    }

    private static void writeDoubles(FriendlyByteBuf buf, double[] values) {
        for (double v : values) {
            buf.writeDouble(v);
        }
    }

    private static double[] readDoubles(FriendlyByteBuf buf, int count) {
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            out[i] = buf.readDouble();
        }
        return out;
    }
}
