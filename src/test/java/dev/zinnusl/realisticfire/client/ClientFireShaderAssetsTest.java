package dev.zinnusl.realisticfire.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientFireShaderAssetsTest {
    @Test
    void rendererUsesShaderTypesAndDrawsOvalFootprints() throws IOException {
        String renderer = Files.readString(Path.of("src/main/java/dev/zinnusl/realisticfire/client/ClientFireRenderer.java"));

        assertAll(
                () -> assertTrue(renderer.contains("ClientFireShaders.flame"), "renderer must use the custom flame RenderType"),
                () -> assertTrue(renderer.contains("ClientFireShaders.scorch"), "renderer must use the custom scorch RenderType"),
                () -> assertTrue(Files.readString(Path.of("src/main/java/dev/zinnusl/realisticfire/client/ClientFireShaders.java")).contains("262_144"), "shader render type needs enough buffer for oval meshes"),
                () -> assertTrue(renderer.contains("FireFootprint"), "renderer must cluster records into continuous burn footprints"),
                () -> assertTrue(renderer.contains("renderOvalFireFront"), "renderer must render an oval fire front"),
                () -> assertTrue(renderer.contains("renderCharredOval"), "renderer must render a continuous charred oval"),
                () -> assertTrue(renderer.contains("contourRadius"), "renderer must vary the oval contour instead of drawing block boxes"),
                () -> assertFalse(renderer.contains("renderBurningEdges"), "renderer must not use block-edge fire boxes"),
                () -> assertFalse(renderer.contains("hasHotNeighbor"), "renderer must not rely on block-neighbor edge suppression"));
    }

    @Test
    void modRegistersShaderListenerOnModEventBus() throws IOException {
        String mod = Files.readString(Path.of("src/main/java/dev/zinnusl/realisticfire/RealisticFire.java"));

        assertAll(
                () -> assertTrue(mod.contains("registerClientHooks(modEventBus)"), "client hooks must receive the mod event bus"),
                () -> assertTrue(mod.contains("ClientFireShaders"), "shader listener must be registered on the client"),
                () -> assertTrue(mod.contains("IEventBus.class"), "reflective registration must call register(IEventBus)"));
    }

    @Test
    void shaderAssetsProvideNoisyFlickerAndSoftOvalMasks() throws IOException {
        String fragment = Files.readString(Path.of("src/main/resources/assets/realisticfire/shaders/core/realistic_fire.fsh"));
        String vertex = Files.readString(Path.of("src/main/resources/assets/realisticfire/shaders/core/realistic_fire.vsh"));
        String definition = Files.readString(Path.of("src/main/resources/assets/realisticfire/shaders/core/realistic_fire.json"));

        assertAll(
                () -> assertTrue(fragment.contains("heatNoise"), "fragment shader must include procedural heat noise"),
                () -> assertTrue(fragment.contains("GameTime"), "fragment shader must animate from GameTime"),
                () -> assertTrue(fragment.contains("whiteHotCore"), "fragment shader must add a white-hot rim/core"),
                () -> assertTrue(fragment.contains("porousMask"), "fragment shader must break up the rim alpha"),
                () -> assertTrue(fragment.contains("softOvalMask"), "fragment shader must soften the oval footprint"),
                () -> assertTrue(fragment.contains("smokeBreakup"), "fragment shader must break up smoke alpha into wisps"),
                () -> assertTrue(vertex.contains("softMask"), "vertex shader must pass a soft mask"),
                () -> assertTrue(vertex.contains("vertexPosition"), "vertex shader must pass world-ish position for noise"),
                () -> assertTrue(definition.contains("\"Sampler0\""), "shader definition must declare the texture sampler"),
                () -> assertTrue(definition.contains("\"GameTime\""), "shader definition must declare GameTime"));
    }
}
