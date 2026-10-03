package ml.mypals.meshcut.client.capture;

import net.minecraft.client.model.geom.ModelPart;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * While an entity is being recorded, which model part (and which of its cubes) is drawing: every face the recording
 * takes is labelled with it, so the entity can be taken apart into limbs afterwards. Fed by {@code ModelPartMixin}.
 * Render thread only.
 */
public final class PartTags {
    /**
     * One time a model part was drawn.
     *
     * @param pose   where it was drawn: its pivot and its rotation, in the recording's space
     * @param parent the part it was drawn as a child of, if any
     */
    public record DrawnPart(ModelPart part, @Nullable DrawnPart parent, Matrix4f pose) {}

    /** What drew a face. */
    public record Tag(DrawnPart part, @Nullable ModelPart.Cube cube) {}

    private static final Deque<DrawnPart> STACK = new ArrayDeque<>();
    private static List<DrawnPart> drawn = new ArrayList<>();
    @Nullable
    private static ModelPart.Cube cube;
    /** Only an entity being taken apart is labelled (block entity renderers are recorded too). */
    private static boolean active;

    static void begin() {
        active = true;
        STACK.clear();
        drawn = new ArrayList<>();
        cube = null;
    }

    /** The parts drawn since {@link #begin}, in the order they were drawn (parents before their children). */
    static List<DrawnPart> end() {
        active = false;
        STACK.clear();
        cube = null;
        List<DrawnPart> out = drawn;
        drawn = new ArrayList<>();
        return out;
    }

    public static void enter(ModelPart part, Matrix4f pose) {
        if (!active) {
            return;
        }
        DrawnPart p = new DrawnPart(part, STACK.peek(), new Matrix4f(pose));
        STACK.push(p);
        drawn.add(p);
    }

    public static void leave() {
        if (!active) {
            return;
        }
        STACK.poll();
    }

    public static void cube(@Nullable ModelPart.Cube c) {
        if (active) {
            cube = c;
        }
    }

    @Nullable
    static Tag current() {
        if (!active) {
            return null;
        }
        DrawnPart top = STACK.peek();
        return top == null ? null : new Tag(top, cube);
    }

    private PartTags() {}
}
