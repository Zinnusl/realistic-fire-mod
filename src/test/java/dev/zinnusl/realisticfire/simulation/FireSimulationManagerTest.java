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

    @Test
    void destructiveMutationsForceVisualEvidenceAndSmokeExposure() throws IOException {
        String manager = Files.readString(Path.of("src/main/java/dev/zinnusl/realisticfire/simulation/FireSimulationManager.java"));
        String bridge = Files.readString(Path.of("src/main/java/dev/zinnusl/realisticfire/nativebridge/RealisticFireNativeSolver.java"));

        assertTrue(manager.contains("appendMutationVisual"), "terrain burn mutations should emit mandatory visual evidence");
        assertTrue(manager.contains("sendVisuals(result, mutationVisuals)"), "mandatory mutation visuals should share the normal visual packet path");
        assertTrue(manager.contains("applySmokeExposure"), "server tick should apply smoke exposure independently of client particles");
        assertTrue(manager.contains("MobEffects.MOVEMENT_SLOWDOWN"), "smoke exposure should visibly choke entities before damage");
        assertTrue(bridge.contains("querySmoke"), "smoke exposure should query authoritative native smoke state");
    }
}
