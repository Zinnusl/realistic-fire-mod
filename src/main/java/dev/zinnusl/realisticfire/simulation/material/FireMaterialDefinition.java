package dev.zinnusl.realisticfire.simulation.material;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record FireMaterialDefinition(
        boolean replace,
        List<Target> targets,
        double fuel,
        double ignitionTemperatureK,
        double burnRate,
        double heatRelease,
        double smokeYield,
        double moisture,
        double insulation,
        ResourceLocation charBlock,
        ResourceLocation ashBlock) {
    public record Target(boolean tag, ResourceLocation id) {
    }

    public static FireMaterialDefinition parse(JsonObject json) {
        boolean replace = json.has("replace") && json.get("replace").getAsBoolean();
        List<Target> targets = parseTargets(json);
        double fuel = requiredDouble(json, "fuel");
        double ignitionTemperature = requiredDouble(json, "ignition_temperature_k");
        double burnRate = requiredDouble(json, "burn_rate");
        double heatRelease = requiredDouble(json, "heat_release");
        double smokeYield = requiredDouble(json, "smoke_yield");
        double moisture = optionalDouble(json, "moisture", 0.0);
        double insulation = optionalDouble(json, "insulation", 0.0);
        ResourceLocation charBlock = optionalLocation(json, "char_block");
        ResourceLocation ashBlock = optionalLocation(json, "ash_block");

        if (fuel < 0.0 || burnRate < 0.0 || heatRelease < 0.0 || smokeYield < 0.0) {
            throw new JsonParseException("Fire material numeric fields must be non-negative");
        }
        if (ignitionTemperature < 0.0) {
            throw new JsonParseException("ignition_temperature_k must be non-negative");
        }

        return new FireMaterialDefinition(
                replace,
                targets,
                fuel,
                ignitionTemperature,
                burnRate,
                heatRelease,
                smokeYield,
                moisture,
                insulation,
                charBlock,
                ashBlock);
    }

    private static List<Target> parseTargets(JsonObject json) {
        if (!json.has("targets") || !json.get("targets").isJsonArray()) {
            throw new JsonParseException("Fire material requires targets array");
        }
        JsonArray array = json.getAsJsonArray("targets");
        List<Target> targets = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            JsonObject target = element.getAsJsonObject();
            boolean hasBlock = target.has("block");
            boolean hasTag = target.has("tag");
            if (hasBlock == hasTag) {
                throw new JsonParseException("Each target must contain exactly one of block or tag");
            }
            String value = target.get(hasBlock ? "block" : "tag").getAsString();
            targets.add(new Target(hasTag, ResourceLocation.parse(value)));
        }
        if (targets.isEmpty()) {
            throw new JsonParseException("Fire material must target at least one block or tag");
        }
        return targets;
    }

    private static double requiredDouble(JsonObject json, String field) {
        if (!json.has(field)) {
            throw new JsonParseException("Missing required field " + field);
        }
        return json.get(field).getAsDouble();
    }

    private static double optionalDouble(JsonObject json, String field, double fallback) {
        return json.has(field) ? json.get(field).getAsDouble() : fallback;
    }

    private static ResourceLocation optionalLocation(JsonObject json, String field) {
        return json.has(field) ? ResourceLocation.parse(json.get(field).getAsString()) : null;
    }
}
