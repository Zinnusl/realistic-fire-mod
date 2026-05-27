package dev.zinnusl.realisticfire.simulation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FireSimulationManagerTest {
    @Test
    void placedFireRestoresReplacedFuelOnlyAfterSyntheticCellWasSeeded() {
        assertTrue(FireSimulationManager.shouldRestoreReplacedFuel(true, true));
        assertFalse(FireSimulationManager.shouldRestoreReplacedFuel(true, false));
        assertFalse(FireSimulationManager.shouldRestoreReplacedFuel(false, true));
    }

    @Test
    void fireBlockMixinPassesOldStateIntoPlacedFireIgnition() throws IOException {
        String mixin = Files.readString(Path.of("src/main/java/dev/zinnusl/realisticfire/mixin/FireBlockMixin.java"));
        String manager = Files.readString(Path.of("src/main/java/dev/zinnusl/realisticfire/simulation/FireSimulationManager.java"));

        assertTrue(mixin.contains("igniteFromPlacedFire(pos, oldState, 1200.0f)"));
        assertTrue(mixin.contains("serverLevel.setBlock(pos, cleanupState, Block.UPDATE_ALL)"));
        assertTrue(manager.contains("seedSyntheticFuelCell(pos, replacedState, temperatureK)"));
        assertTrue(manager.contains("return replacedState;"));
    }
}
