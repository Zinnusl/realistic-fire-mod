package dev.zinnusl.realisticfire.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.zinnusl.realisticfire.api.FireApi;
import dev.zinnusl.realisticfire.simulation.FireSimulationManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

public final class RealisticFireCommands {
    private RealisticFireCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("realisticfire")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("ignite")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> ignite(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "pos"), 1200.0, 0))
                                .then(Commands.argument("temperatureK", DoubleArgumentType.doubleArg(273.0, 6000.0))
                                        .executes(ctx -> ignite(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "pos"), DoubleArgumentType.getDouble(ctx, "temperatureK"), 0))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 16))
                                                .executes(ctx -> ignite(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "pos"), DoubleArgumentType.getDouble(ctx, "temperatureK"), IntegerArgumentType.getInteger(ctx, "radius")))))))
                .then(Commands.literal("extinguish")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(ctx -> extinguish(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "from"), BlockPosArgument.getLoadedBlockPos(ctx, "to"))))))
                .then(Commands.literal("debug")
                        .then(Commands.literal("stats").executes(ctx -> debug(ctx.getSource(), "stats")))
                        .then(Commands.literal("cells").executes(ctx -> debug(ctx.getSource(), "cells")))
                        .then(Commands.literal("off").executes(ctx -> debug(ctx.getSource(), "off")))));
    }

    private static int ignite(CommandSourceStack source, BlockPos pos, double temperatureK, int radius) {
        ServerLevel level = source.getLevel();
        FireApi.ignite(level, pos, temperatureK, radius);
        source.sendSuccess(() -> Component.translatable("commands.realisticfire.ignite", pos.toShortString()), true);
        return 1;
    }

    private static int extinguish(CommandSourceStack source, BlockPos from, BlockPos to) {
        ServerLevel level = source.getLevel();
        int minX = Math.min(from.getX(), to.getX());
        int minY = Math.min(from.getY(), to.getY());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxX = Math.max(from.getX(), to.getX()) + 1;
        int maxY = Math.max(from.getY(), to.getY()) + 1;
        int maxZ = Math.max(from.getZ(), to.getZ()) + 1;
        int count = FireApi.extinguish(level, new AABB(minX, minY, minZ, maxX, maxY, maxZ));
        source.sendSuccess(() -> Component.translatable("commands.realisticfire.extinguish", count), true);
        return count;
    }

    private static int debug(CommandSourceStack source, String mode) {
        FireSimulationManager.setDebugMode(mode);
        source.sendSuccess(() -> Component.translatable("commands.realisticfire.debug", mode), false);
        return 1;
    }
}
