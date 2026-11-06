package ml.mypals.meshcut.client.capture;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class PartTags {
    public record DrawnPart(ModelPart part, @Nullable DrawnPart parent, Matrix4f pose) {}

    public static final class PoseNode {
        @Nullable
        final PoseNode parent;
        final int index;
        private final PoseStack.Pose live;
        @Nullable
        Matrix4f pose;
        @Nullable
        Object owner;
        @Nullable
        String name;
        boolean hinged;

        PoseNode(@Nullable PoseNode parent, int index, PoseStack.Pose live) {
            this.parent = parent;
            this.index = index;
            this.live = live;
        }
    }

    public record Tag(@Nullable DrawnPart part, @Nullable ModelPart.Cube cube, @Nullable PoseNode node) {}

    private static final Deque<DrawnPart> STACK = new ArrayDeque<>();
    private static final Deque<PoseNode> NODES = new ArrayDeque<>();
    private static List<DrawnPart> drawn = new ArrayList<>();
    private static int nodes;
    @Nullable
    private static ModelPart.Cube cube;
    private static volatile boolean active;
    private static volatile Thread thread;

    private static boolean on() {
        return active && Thread.currentThread() == thread;
    }

    static void begin() {
        thread = Thread.currentThread();
        active = true;
        STACK.clear();
        NODES.clear();
        drawn = new ArrayList<>();
        nodes = 0;
        cube = null;
    }

    static List<DrawnPart> end() {
        active = false;
        thread = null;
        STACK.clear();
        NODES.clear();
        cube = null;
        List<DrawnPart> out = drawn;
        drawn = new ArrayList<>();
        return out;
    }

    public static void enter(ModelPart part, Matrix4f pose) {
        if (!on()) {
            return;
        }
        DrawnPart p = new DrawnPart(part, STACK.peek(), new Matrix4f(pose));
        STACK.push(p);
        drawn.add(p);
    }

    public static void leave() {
        if (!on()) {
            return;
        }
        STACK.poll();
    }

    public static void cube(@Nullable ModelPart.Cube c) {
        if (on()) {
            cube = c;
        }
    }

    public static void pushed(PoseStack.Pose top) {
        if (on()) {
            NODES.push(new PoseNode(NODES.peek(), nodes++, top));
        }
    }

    public static void popped() {
        if (on()) {
            NODES.poll();
        }
    }

    public static void own(Object owner, @Nullable String name) {
        PoseNode top = on() ? NODES.peek() : null;
        if (top != null) {
            top.owner = owner;
            top.name = name;
        }
    }

    public static void hinge(Object owner, @Nullable String name) {
        PoseNode top = on() ? NODES.peek() : null;
        if (top != null) {
            top.owner = owner;
            top.name = name;
            top.hinged = true;
            top.pose = new Matrix4f(top.live.pose());
        }
    }

    @Nullable
    static Tag current() {
        if (!on()) {
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
