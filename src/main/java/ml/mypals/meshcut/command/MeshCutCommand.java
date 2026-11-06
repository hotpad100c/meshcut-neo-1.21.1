package ml.mypals.meshcut.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import ml.mypals.meshcut.MeshCut;
import ml.mypals.meshcut.block.MimicBlock;
import ml.mypals.meshcut.block.MimicBlockEntity;
import ml.mypals.meshcut.cut.CapMode;
import ml.mypals.meshcut.cut.CutPlane;
import ml.mypals.meshcut.cut.CutService;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class MeshCutCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext ctx) {
        dispatcher.register(Commands.literal("meshcut").requires(s -> s.hasPermission(2))
                .then(Commands.literal("debugmimic")
                        .then(Commands.argument("block", BlockStateArgument.block(ctx))
                                .executes(c -> debugMimic(c.getSource(), blockArg(c), null))
                                .then(planeArgs(c -> debugMimic(c.getSource(), blockArg(c), planeArg(c))))))
                .then(Commands.literal("cutblock")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(planeArgs(c -> cutBlock(c.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(c, "pos"), planeArg(c))))))
                .then(Commands.literal("cut")
                        .then(Commands.argument("point", Vec3Argument.vec3())
                                .then(Commands.argument("nx", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("ny", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("nz", DoubleArgumentType.doubleArg())
                                                        .executes(c -> cutWorld(c, 3.0, CapMode.LAVA))
                                                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.5, CutService.MAX_RADIUS))
                                                                .executes(c -> cutWorld(c, DoubleArgumentType.getDouble(c, "radius"), CapMode.LAVA))
                                                                .then(Commands.literal("lava").executes(c ->
                                                                        cutWorld(c, DoubleArgumentType.getDouble(c, "radius"), CapMode.LAVA)))
                                                                .then(Commands.literal("plain").executes(c ->
                                                                        cutWorld(c, DoubleArgumentType.getDouble(c, "radius"), CapMode.PLAIN)))))))))
                .then(Commands.literal("cutlook")
                        .then(Commands.literal("vertical").executes(c -> cutLook(c.getSource(), false, 3.0))
                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.5, CutService.MAX_RADIUS))
                                        .executes(c -> cutLook(c.getSource(), false, DoubleArgumentType.getDouble(c, "radius")))))
                        .then(Commands.literal("horizontal").executes(c -> cutLook(c.getSource(), true, 3.0))
                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.5, CutService.MAX_RADIUS))
                                        .executes(c -> cutLook(c.getSource(), true, DoubleArgumentType.getDouble(c, "radius")))))));
    }

    private static int cutWorld(CommandContext<CommandSourceStack> c, double radius, CapMode mode) {
        Vec3 point = Vec3Argument.getVec3(c, "point");
        Vec3 normal = new Vec3(DoubleArgumentType.getDouble(c, "nx"), DoubleArgumentType.getDouble(c, "ny"),
                DoubleArgumentType.getDouble(c, "nz"));
        return runCut(c.getSource(), point, normal, radius, mode);
    }

    private static int cutLook(CommandSourceStack source, boolean horizontal, double radius) {
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only players can use cutlook"));
            return 0;
        }
        Vec3 look = player.getLookAngle();
        Vec3 point = player.getEyePosition().add(look.scale(4));
        Vec3 normal = horizontal ? new Vec3(0, 1, 0) : new Vec3(-look.z, 0, look.x);
        return runCut(source, point, normal, radius, CapMode.LAVA);
    }

    private static int runCut(CommandSourceStack source, Vec3 point, Vec3 normal, double radius, CapMode mode) {
        var result = CutService.cut(source.getLevel(), point, normal, radius, mode);
        if (result == null) {
            source.sendFailure(Component.literal("Normal must be non-zero"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Cut " + result.staticBlocks() + " static block(s), "
                + result.subLevelsCut() + " sub-level(s); created " + result.subLevelsCreated() + " new sub-level(s)"), true);
        return result.staticBlocks() + result.subLevelsCut();
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Double> planeArgs(Command<CommandSourceStack> executor) {
        return Commands.argument("nx", DoubleArgumentType.doubleArg())
                .then(Commands.argument("ny", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("nz", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("d", DoubleArgumentType.doubleArg()).executes(executor))));
    }

    private static BlockState blockArg(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c) {
        return c.getArgument("block", BlockInput.class).getState();
    }

    private static CutPlane planeArg(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c) {
        return CutPlane.of(DoubleArgumentType.getDouble(c, "nx"), DoubleArgumentType.getDouble(c, "ny"),
                DoubleArgumentType.getDouble(c, "nz"), DoubleArgumentType.getDouble(c, "d"));
    }

    private static int debugMimic(CommandSourceStack source, BlockState target, CutPlane plane) {
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition()).above(3);
        level.setBlock(pos, MeshCut.MIMIC_BLOCK.get().defaultBlockState(), 3);
        if (level.getBlockEntity(pos) instanceof MimicBlockEntity be) {
            be.setTargetState(target);
            if (plane != null) {
                be.addPlane(plane);
            }
        }
        var subLevel = SubLevelAssemblyHelper.assembleBlocks(level, pos, List.of(pos), new BoundingBox3i(pos, pos));
        if (subLevel == null) {
            source.sendFailure(Component.literal("Sable refused to assemble a sub-level at " + pos.toShortString()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Assembled mimic of " + target.getBlock().getName().getString()
                + " into sub-level " + subLevel.getUniqueId()), true);
        return 1;
    }

    private static int cutBlock(CommandSourceStack source, BlockPos pos, CutPlane plane) {
        if (plane == null) {
            source.sendFailure(Component.literal("Normal must be non-zero"));
            return 0;
        }
        if (source.getLevel().getBlockEntity(pos) instanceof MimicBlockEntity be) {
            List<CutPlane> planes = new java.util.ArrayList<>(be.getPlanes());
            planes.add(plane);
            MimicBlock.place(source.getLevel(), pos, be.getTargetState(), planes, be.getExcluded(), be.getPatches(), be.getCapMode(), be.getTargetBeTag(), be.getAppearance());
            return 1;
        }
        source.sendFailure(Component.literal("No mimic block at " + pos.toShortString()));
        return 0;
    }

    private MeshCutCommand() {}
}
