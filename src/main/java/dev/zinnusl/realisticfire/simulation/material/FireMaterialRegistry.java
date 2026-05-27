package dev.zinnusl.realisticfire.simulation.material;

import dev.zinnusl.realisticfire.RealisticFire;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FireMaterialRegistry {
    private static final Map<Block, FireMaterial> MATERIAL_BY_BLOCK = new HashMap<>();
    private static final List<FireMaterial> MATERIALS_BY_ID = new ArrayList<>();
    private static final Object2IntMap<Block> MATERIAL_ID_BY_BLOCK = new Object2IntOpenHashMap<>();
    private static String materialSignature = "empty";

    static {
        reset();
    }

    private FireMaterialRegistry() {
    }

    public static synchronized void apply(List<FireMaterialDefinition> definitions) {
        reset();
        for (FireMaterialDefinition definition : definitions) {
            for (FireMaterialDefinition.Target target : definition.targets()) {
                if (target.tag()) {
                    applyTarget(definition, target);
                }
            }
        }
        for (FireMaterialDefinition definition : definitions) {
            for (FireMaterialDefinition.Target target : definition.targets()) {
                if (!target.tag()) {
                    applyTarget(definition, target);
                }
            }
        }
        materialSignature = buildSignature();
        RealisticFire.LOGGER.info("Loaded {} realistic fire material bindings", MATERIAL_BY_BLOCK.size());
    }

    public static synchronized FireMaterial material(BlockState state) {
        if (state == null || state.isAir()) {
            return FireMaterial.INERT;
        }
        return MATERIAL_BY_BLOCK.getOrDefault(state.getBlock(), FireMaterial.INERT);
    }

    public static synchronized int materialId(BlockState state) {
        if (state == null || state.isAir()) {
            return 0;
        }
        return MATERIAL_ID_BY_BLOCK.getInt(state.getBlock());
    }

    public static synchronized FireMaterial materialById(int id) {
        if (id <= 0 || id >= MATERIALS_BY_ID.size()) {
            return FireMaterial.INERT;
        }
        return MATERIALS_BY_ID.get(id);
    }

    public static synchronized int materialCount() {
        return MATERIALS_BY_ID.size();
    }

    public static synchronized String materialSignature() {
        return materialSignature;
    }

    private static void reset() {
        MATERIAL_BY_BLOCK.clear();
        MATERIAL_ID_BY_BLOCK.clear();
        MATERIAL_ID_BY_BLOCK.defaultReturnValue(0);
        MATERIALS_BY_ID.clear();
        MATERIALS_BY_ID.add(FireMaterial.INERT);
        materialSignature = "empty";
    }

    private static FireMaterial createMaterial(FireMaterialDefinition definition) {
        int id = MATERIALS_BY_ID.size();
        BlockState charState = defaultBlockState(definition.charBlock());
        BlockState ashState = defaultBlockState(definition.ashBlock());
        FireMaterial material = new FireMaterial(
                id,
                definition.fuel(),
                definition.ignitionTemperatureK(),
                definition.burnRate(),
                definition.heatRelease(),
                definition.smokeYield(),
                definition.moisture(),
                definition.insulation(),
                charState,
                ashState);
        MATERIALS_BY_ID.add(material);
        return material;
    }

    private static void applyTarget(FireMaterialDefinition definition, FireMaterialDefinition.Target target) {
        for (Block block : resolveBlocks(target)) {
            if (definition.replace()) {
                MATERIAL_BY_BLOCK.remove(block);
                MATERIAL_ID_BY_BLOCK.removeInt(block);
            }
            FireMaterial material = createMaterial(definition);
            MATERIAL_BY_BLOCK.put(block, material);
            MATERIAL_ID_BY_BLOCK.put(block, material.id());
        }
    }

    private static BlockState defaultBlockState(ResourceLocation id) {
        if (id == null) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.get(id);
        return block == Blocks.AIR && !id.equals(BuiltInRegistries.BLOCK.getKey(Blocks.AIR)) ? null : block.defaultBlockState();
    }

    private static List<Block> resolveBlocks(FireMaterialDefinition.Target target) {
        List<Block> blocks = new ArrayList<>();
        if (target.tag()) {
            Optional<HolderSet.Named<Block>> tag = BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, target.id()));
            if (tag.isEmpty()) {
                RealisticFire.LOGGER.warn("Unknown realistic fire material tag {}", target.id());
                return blocks;
            }
            for (Holder<Block> holder : tag.get()) {
                blocks.add(holder.value());
            }
            return blocks;
        }

        Block block = BuiltInRegistries.BLOCK.get(target.id());
        if (block == Blocks.AIR && !target.id().equals(BuiltInRegistries.BLOCK.getKey(Blocks.AIR))) {
            RealisticFire.LOGGER.warn("Unknown realistic fire material block {}", target.id());
            return blocks;
        }
        blocks.add(block);
        return blocks;
    }

    private static String buildSignature() {
        StringBuilder builder = new StringBuilder();
        for (int id = 1; id < MATERIALS_BY_ID.size(); id++) {
            FireMaterial material = MATERIALS_BY_ID.get(id);
            builder.append(id)
                    .append(':').append(material.fuel())
                    .append(':').append(material.ignitionTemperatureK())
                    .append(':').append(material.burnRate())
                    .append(':').append(material.heatRelease())
                    .append(':').append(material.smokeYield())
                    .append(':').append(material.moisture())
                    .append(':').append(material.insulation())
                    .append(':').append(blockKey(material.charState()))
                    .append(':').append(blockKey(material.ashState()))
                    .append(';');
        }
        MATERIAL_ID_BY_BLOCK.object2IntEntrySet().stream()
                .sorted(Map.Entry.comparingByKey((left, right) -> BuiltInRegistries.BLOCK.getKey(left).compareTo(BuiltInRegistries.BLOCK.getKey(right))))
                .forEach(entry -> builder.append(BuiltInRegistries.BLOCK.getKey(entry.getKey())).append('=').append(entry.getIntValue()).append(';'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(builder.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    private static ResourceLocation blockKey(BlockState state) {
        return state == null ? RealisticFire.id("none") : BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }
}
