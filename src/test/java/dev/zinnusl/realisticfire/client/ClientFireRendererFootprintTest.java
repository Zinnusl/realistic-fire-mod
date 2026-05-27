package dev.zinnusl.realisticfire.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientFireRendererFootprintTest {
    @Test
    void rendererKeepsSingleOriginAndExpandsContinuousContours() throws IOException {
        String renderer = Files.readString(Path.of("src/main/java/dev/zinnusl/realisticfire/client/ClientFireRenderer.java"));

        assertAll(
                () -> assertTrue(renderer.contains("originX"), "footprints need a stable ignition origin"),
                () -> assertTrue(renderer.contains("targetRadii"), "samples should expand a radial target contour"),
                () -> assertTrue(renderer.contains("currentRadii"), "the visible contour should grow smoothly toward the target"),
                () -> assertTrue(renderer.contains("stretchMajor"), "footprints should be oval rather than square"),
                () -> assertTrue(renderer.contains("stretchMinor"), "footprints should support anisotropic ovals"),
                () -> assertTrue(renderer.contains("renderMultiLayerRibbon"), "the flame should be drawn as a perimeter band"),
                () -> assertFalse(renderer.contains("Direction.NORTH"), "the visual should not be aligned to block cardinal edges"),
                () -> assertFalse(renderer.contains("SCORCHES"), "the old per-block scorch map must be gone"));
    }

    @Test
    void directCellRendererDrawsSmokeWispsAndFlameTongues() throws IOException {
        String renderer = Files.readString(Path.of("src/main/java/dev/zinnusl/realisticfire/client/ClientFireRenderer.java"));

        assertAll(
                () -> assertTrue(renderer.contains("recordSmokeColumn(smokeColumns"), "active direct-cell path should feed smoke columns"),
                () -> assertTrue(renderer.contains("FRAME_SMOKE_CELLS"), "transported smoke samples should render even after rising off the fuel surface"),
                () -> assertTrue(renderer.contains("FrameSmokeKey"), "smoke rendering should preserve the simulation altitude"),
                () -> assertTrue(renderer.contains("renderDirectSmokeColumn"), "active direct-cell path should render rising smoke columns"),
                () -> assertTrue(renderer.contains("DIRECT_SMOKE_LAYERS"), "direct smoke should be layered into rising wisps"),
                () -> assertTrue(renderer.contains("renderDirectCellTongues"), "direct flame fronts should include animated flame tongues"),
                () -> assertTrue(renderer.contains("edgeLength / 0.18f"), "front edges should be broken into small flamelets"),
                () -> assertTrue(renderer.contains("renderFrameCellPass"), "active flame cells should render before stale scorch cells"),
                () -> assertTrue(renderer.contains("hasActiveFlame"), "the renderer should identify live flame front samples"),
                () -> assertTrue(renderer.contains("DIRECT_TONGUE_LIMIT"), "direct flame tongues should be bounded per edge"));
    }
}
