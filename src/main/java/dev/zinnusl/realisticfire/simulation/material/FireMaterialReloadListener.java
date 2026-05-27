package dev.zinnusl.realisticfire.simulation.material;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.zinnusl.realisticfire.RealisticFire;
import dev.zinnusl.realisticfire.simulation.FireSimulationManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FireMaterialReloadListener extends SimpleJsonResourceReloadListener {
    public static final FireMaterialReloadListener INSTANCE = new FireMaterialReloadListener();
    public static final String DIRECTORY = "realistic_fire/materials";

    private FireMaterialReloadListener() {
        super(new Gson(), DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager, ProfilerFiller profiler) {
        List<FireMaterialDefinition> definitions = new ArrayList<>(entries.size());
        entries.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
            try {
                definitions.add(FireMaterialDefinition.parse(entry.getValue().getAsJsonObject()));
            } catch (RuntimeException exception) {
                RealisticFire.LOGGER.error("Failed to load realistic fire material {}: {}", entry.getKey(), exception.getMessage());
            }
        });
        FireMaterialRegistry.apply(definitions);
        FireSimulationManager.invalidateMaterialTables();
    }
}
