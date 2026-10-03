package ml.mypals.meshcut.client.capture;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * While an entity is being recorded, what is drawing: every face the recording takes is labelled with it, so the
 * entity can be taken apart into limbs afterwards. Two kinds of label:
 * <ul>
 *   <li>the vanilla model part (and which of its cubes), fed by {@code ModelPartMixin} and {@code ModelPartCubeMixin};</li>
 *   <li>the level of the pose stack it was drawn at ({@link PoseNode}), fed by {@code PoseStackMixin}. Every model
 *       system draws a part the same way (push, turn to the part, draw, draw the children, pop), whatever its parts
 *       are (Citadel's, GeckoLib's, a mod's own), so the levels are its parts and their nesting its hierarchy.</li>
 * </ul>
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

    /** One level of the pose stack, pushed while recording. */
    public static final class PoseNode {
        @Nullable
        final PoseNode parent;
        /** In the order the levels were pushed: parents before their children. */
        final int index;
        private final PoseStack.Pose live;
        /** Where it drew, taken when it drew its first face (the level is turned after it is pushed); null if it drew none. */
        @Nullable
        Matrix4f pose;
        /** The model part that pushed it, if a model system told (see {@code CitadelModelBoxMixin}). */
        @Nullable
        Object owner;
        @Nullable
        String name;

        PoseNode(@Nullable PoseNode parent, int index, PoseStack.Pose live) {
            this.parent = parent;
            this.index = index;
            this.live = live;
        }
    }

    /** What drew a face: the vanilla part, if any, and the level of the pose stack. */
    public record Tag(@Nullable DrawnPart part, @Nullable ModelPart.Cube cube, @Nullable PoseNode node) {}

    private static final Deque<DrawnPart> STACK = new ArrayDeque<>();
    private static final Deque<PoseNode> NODES = new ArrayDeque<>();
    private static List<DrawnPart> drawn = new ArrayList<>();
    private static int nodes;
    @Nullable
    private static ModelPart.Cube cube;
    /** Only an entity being taken apart is labelled (block entity renderers are recorded too). */
    private static boolean active;

    static void begin() {
        active = true;
        STACK.clear();
        NODES.clear();
        drawn = new ArrayList<>();
        nodes = 0;
        cube = null;
    }

    /** The parts drawn since {@link #begin}, in the order they were drawn (parents before their children). */
    static List<DrawnPart> end() {
        active = false;
        STACK.clear();
        NODES.clear();
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

    /** A level was pushed onto the pose stack. */
    public static void pushed(PoseStack.Pose top) {
        if (active) {
            NODES.push(new PoseNode(NODES.peek(), nodes++, top));
        }
    }

    /** A level was popped off the pose stack. */
    public static void popped() {
        if (active) {
            NODES.poll();
        }
    }

    /** The model part that pushed the current level, and its name (a model system that names its parts tells). */
    public static void own(Object owner, @Nullable String name) {
        PoseNode top = active ? NODES.peek() : null;
        if (top != null) {
            top.owner = owner;
            top.name = name;
        }
    }

    @Nullable
    static Tag current() {
        if (!active) {
            return null;
        }
        DrawnPart top = STACK.peek();
        PoseNode node = NODES.peek();
        if (top == null && node == null) {
            return null;
        }
        if (node != null && node.pose == null) {
            node.pose = new Matrix4f(node.live.pose());
        }
        return new Tag(top, cube, node);
    }

    private PartTags() {}
}
