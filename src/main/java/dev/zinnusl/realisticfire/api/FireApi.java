package dev.zinnusl.realisticfire.api;

import dev.zinnusl.realisticfire.simulation.FireSimulationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

public final class FireApi {
    private FireApi() {
    }

    public static void ignite(ServerLevel level, BlockPos pos, double temperatureK, int radiusBlocks) {
        FireSimulationManager.forLevel(level).ignite(pos, (float) temperatureK, radiusBlocks);
    }

    public static int extinguish(ServerLevel level, AABB bounds) {
        return FireSimulationManager.forLevel(level).extinguish(bounds);
    }

    public static double queryHeat(ServerLevel level, BlockPos pos) {
        return FireSimulationManager.forLevel(level).queryTemperature(pos);
    }
}
