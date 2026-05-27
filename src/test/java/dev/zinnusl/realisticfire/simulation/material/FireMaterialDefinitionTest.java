package dev.zinnusl.realisticfire.simulation.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FireMaterialDefinitionTest {
    @Test
    void parsesRequiredSchemaAndDefaults() {
        FireMaterialDefinition definition = FireMaterialDefinition.parse(JsonParser.parseString("""
                {
                  "targets": [{ "block": "minecraft:oak_planks" }],
                  "fuel": 1.0,
                  "ignition_temperature_k": 573.0,
                  "burn_rate": 0.03,
                  "heat_release": 1800.0,
                  "smoke_yield": 0.35
                }
                """).getAsJsonObject());

        assertEquals(1, definition.targets().size());
        assertEquals(0.0, definition.moisture());
        assertEquals(0.0, definition.insulation());
    }

    @Test
    void rejectsAmbiguousTarget() {
        assertThrows(RuntimeException.class, () -> FireMaterialDefinition.parse(JsonParser.parseString("""
                {
                  "targets": [{ "block": "minecraft:oak_planks", "tag": "minecraft:planks" }],
                  "fuel": 1.0,
                  "ignition_temperature_k": 573.0,
                  "burn_rate": 0.03,
                  "heat_release": 1800.0,
                  "smoke_yield": 0.35
                }
                """).getAsJsonObject()));
    }

    @Test
    void defaultMaterialsKeepShapeAndResidueContracts() throws IOException {
        FireMaterialDefinition wood = parseResourceMaterial("default_wood.json");
        FireMaterialDefinition woodShapes = parseResourceMaterial("default_wood_shapes.json");
        FireMaterialDefinition turf = parseResourceMaterial("default_turf.json");
        FireMaterialDefinition charredTurf = parseResourceMaterial("default_charred_turf_residue.json");

        assertEquals(ResourceLocation.parse("realisticfire:charred_block"), wood.charBlock());
        assertNull(woodShapes.charBlock());
        assertEquals(ResourceLocation.parse("minecraft:air"), woodShapes.ashBlock());
        assertEquals(0.55, woodShapes.fuel());
        assertEquals(0.09, woodShapes.burnRate());
        assertEquals(ResourceLocation.parse("minecraft:oak_stairs"), woodShapes.targets().get(4).id());
        assertEquals(ResourceLocation.parse("realisticfire:charred_turf"), turf.charBlock());
        assertEquals(ResourceLocation.parse("minecraft:dirt"), charredTurf.ashBlock());
    }

    private static FireMaterialDefinition parseResourceMaterial(String name) throws IOException {
        Path path = Path.of("src/main/resources/data/realisticfire/realistic_fire/materials", name);
        return FireMaterialDefinition.parse(JsonParser.parseString(Files.readString(path)).getAsJsonObject());
    }
}
