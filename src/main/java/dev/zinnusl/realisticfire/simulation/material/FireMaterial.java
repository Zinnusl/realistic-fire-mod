package dev.zinnusl.realisticfire.simulation.material;

import net.minecraft.world.level.block.state.BlockState;

public record FireMaterial(
        int id,
        double fuel,
        double ignitionTemperatureK,
        double burnRate,
        double heatRelease,
        double smokeYield,
        double moisture,
        double insulation,
        BlockState charState,
        BlockState ashState) {
    public static final FireMaterial INERT = new FireMaterial(
            0, 0.0, Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0, 0.0, 0.0, null, null);

    public boolean flammable() {
        return id > 0 && fuel > 0.0 && burnRate > 0.0;
    }
}
