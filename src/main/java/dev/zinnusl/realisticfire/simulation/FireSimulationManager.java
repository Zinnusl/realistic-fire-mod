package dev.zinnusl.realisticfire.simulation;

import dev.zinnusl.realisticfire.RealisticFire;
import dev.zinnusl.realisticfire.config.RealisticFireConfig;
import dev.zinnusl.realisticfire.events.RealisticFireEvent;
import dev.zinnusl.realisticfire.nativebridge.RealisticFireNativeSolver;
import dev.zinnusl.realisticfire.network.ClientboundFireVisualDeltaPayload;
import dev.zinnusl.realisticfire.registry.RealisticFireBlocks;
import dev.zinnusl.realisticfire.simulation.material.FireMaterial;
import dev.zinnusl.realisticfire.simulation.material.FireMaterialRegistry;
import dev.zinnusl.realisticfire.simulation.persistence.FireSavedData;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

public final class FireSimulationManager {
    private static final int CELL_COUNT = 16 * 16 * 16;
    private static final int STATE_FIELDS = 6;
    private static final Map<ServerLevel, FireSimulationManager> MANAGERS = new WeakHashMap<>();
    private static String debugMode = "off";

    private final ServerLevel level;
    private final LongSet uploadedSections = new LongOpenHashSet();
    private final LongSet dirtyCells = new LongOpenHashSet();
    private final JavaFireFallback fallback = new JavaFireFallback();
    private final FireSavedData savedData;
    private long worldHandle;
    private int visualSequence;
    private boolean nativeFailureLogged;

    private FireSimulationManager(ServerLevel level) {
        this.level = level;
        this.savedData = FireSavedData.get(level);
        byte[] snapshot = savedData.snapshot();
        boolean compatibleSnapshot = snapshot.length > 0
                && savedData.materialSignature().equals(FireMaterialRegistry.materialSignature());
        this.worldHandle = RealisticFireNativeSolver.createWorld(
                level.dimension().location().hashCode(),
                level.getMinBuildHeight(),
                level.getMaxBuildHeight(),
                (int) level.getSeed());
        boolean loadedSnapshot = false;
        if (this.worldHandle != 0L) {
            uploadMaterialTable();
            loadedSnapshot = compatibleSnapshot && RealisticFireNativeSolver.load(this.worldHandle, snapshot);
        }
        if (loadedSnapshot) {
            for (long section : savedData.activeSections()) {
                this.uploadedSections.add(section);
                if (this.worldHandle != 0L) {
                    RealisticFireNativeSolver.setTileLoaded(
                            this.worldHandle,
                            SectionPos.x(section),
                            SectionPos.y(section),
                            SectionPos.z(section),
                            false);
                }
            }
        }
    }

    public static synchronized FireSimulationManager forLevel(ServerLevel level) {
        return MANAGERS.computeIfAbsent(level, FireSimulationManager::new);
    }

    public static synchronized void unload(ServerLevel level) {
        FireSimulationManager manager = MANAGERS.remove(level);
        if (manager != null) {
            manager.persist();
            RealisticFireNativeSolver.destroyWorld(manager.worldHandle);
            manager.worldHandle = 0L;
        }
    }

    public static synchronized void invalidateMaterialTables() {
        for (FireSimulationManager manager : MANAGERS.values()) {
            manager.uploadedSections.clear();
            manager.dirtyCells.clear();
            if (manager.worldHandle != 0L) {
                RealisticFireNativeSolver.destroyWorld(manager.worldHandle);
            }
            manager.worldHandle = RealisticFireNativeSolver.createWorld(
                    manager.level.dimension().location().hashCode(),
                    manager.level.getMinBuildHeight(),
                    manager.level.getMaxBuildHeight(),
                    (int) manager.level.getSeed());
            manager.uploadMaterialTable();
            manager.savedData.update(new byte[0], new long[0], FireMaterialRegistry.materialSignature());
        }
    }

    public static void setDebugMode(String mode) {
        debugMode = mode;
    }

    public void markDirty(BlockPos pos) {
        dirtyCells.add(pos.asLong());
    }

    public void tick() {
        if (!RealisticFireConfig.SERVER.enabled.get()) {
            return;
        }
        if (!nativeReady()) {
            if (!RealisticFireConfig.SERVER.requireNative.get()) {
                fallback.tick(level);
            }
            return;
        }

        uploadMaterialTable();
        refreshDirtyCells();
        int substeps = RealisticFireConfig.SERVER.solverSubsteps.get();
        int maxCells = RealisticFireConfig.SERVER.maxActiveCellsPerLevel.get();
        int maxCellsPerSubstep = Math.max(1, maxCells / substeps);
        int remainingMutations = RealisticFireConfig.SERVER.maxBlockMutationsPerTick.get();
        int remainingVisuals = RealisticFireConfig.SERVER.maxVisualRecordsPerTick.get();
        long deadline = System.nanoTime() + (long) (RealisticFireConfig.SERVER.maxSolverMillisPerTick.get() * 1_000_000.0);
        boolean sendVisualsThisTick = remainingVisuals > 0 && level.getGameTime() % RealisticFireConfig.SERVER.visualSendIntervalTicks.get() == 0L;
        for (int i = 0; i < substeps; i++) {
            RealisticFireNativeSolver.StepResult result = RealisticFireNativeSolver.step(
                    worldHandle,
                    1.0f / 20.0f / substeps,
                    maxCellsPerSubstep,
                    Math.max(remainingMutations, 0),
                    sendVisualsThisTick ? Math.max(remainingVisuals, 0) : 0);
            applyMutations(result);
            remainingMutations -= result.mutationCount();
            if (sendVisualsThisTick) {
                sendVisuals(result);
                remainingVisuals -= result.visualCount();
            }
            if (remainingMutations <= 0 || System.nanoTime() >= deadline) {
                break;
            }
        }
        if (level.getGameTime() % 100L == 0L) {
            persist();
        }
        if ("stats".equals(debugMode) && level.getGameTime() % 100L == 0L) {
            RealisticFire.LOGGER.info("Realistic Fire {}: {} uploaded sections, native={}", level.dimension().location(), uploadedSections.size(), RealisticFireNativeSolver.available());
        }
    }

    public void ignite(BlockPos pos, float temperatureK, int radius) {
        if (!RealisticFireConfig.SERVER.enabled.get()) {
            return;
        }
        NeoForge.EVENT_BUS.post(new RealisticFireEvent.Ignite(level, pos, temperatureK));
        uploadAround(pos, radius + 16);
        igniteCell(pos, temperatureK, radius);
    }

    public void igniteFromPlacedFire(BlockPos pos, float temperatureK) {
        igniteFromPlacedFire(pos, level.getBlockState(pos), temperatureK);
    }

    public BlockState igniteFromPlacedFire(BlockPos pos, BlockState replacedState, float temperatureK) {
        if (!RealisticFireConfig.SERVER.enabled.get()) {
            return Blocks.AIR.defaultBlockState();
        }
        NeoForge.EVENT_BUS.post(new RealisticFireEvent.Ignite(level, pos, temperatureK));
        uploadAround(pos, 16);
        if (replacedState != null
                && shouldRestoreReplacedFuel(
                        FireMaterialRegistry.material(replacedState).flammable(),
                        seedSyntheticFuelCell(pos, replacedState, temperatureK))) {
            return replacedState;
        }
        BlockPos fuelSurface = findFlammableSurfaceTarget(pos);
        if (fuelSurface != null) {
            uploadAround(fuelSurface, 16);
            igniteCell(fuelSurface, temperatureK, 0);
        } else {
            igniteCell(pos, temperatureK, 0);
        }
        return Blocks.AIR.defaultBlockState();
    }

    static boolean shouldRestoreReplacedFuel(boolean replacedStateFlammable, boolean syntheticFuelSeeded) {
        return replacedStateFlammable && syntheticFuelSeeded;
    }

    private void igniteCell(BlockPos pos, float temperatureK, int radius) {
        if (nativeReady()) {
            RealisticFireNativeSolver.ignite(worldHandle, pos.getX(), pos.getY(), pos.getZ(), temperatureK, radius);
        } else if (!RealisticFireConfig.SERVER.requireNative.get()) {
            fallback.ignite(pos, temperatureK, radius);
        }
    }

    private boolean seedSyntheticFuelCell(BlockPos pos, BlockState state, float temperatureK) {
        FireMaterial material = FireMaterialRegistry.material(state);
        if (!material.flammable()) {
            return false;
        }
        int sx = SectionPos.blockToSectionCoord(pos.getX());
        int sy = SectionPos.blockToSectionCoord(pos.getY());
        int sz = SectionPos.blockToSectionCoord(pos.getZ());
        if (!uploadedSections.contains(SectionPos.asLong(sx, sy, sz)) && !uploadSection(sx, sy, sz)) {
            return false;
        }
        if (!nativeReady()) {
            return false;
        }
        RealisticFireNativeSolver.setCell(
                worldHandle,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                materialIdForState(state, material),
                initialCellState(state, material));
        RealisticFireNativeSolver.ignite(worldHandle, pos.getX(), pos.getY(), pos.getZ(), temperatureK, 0);
        return true;
    }

    public int extinguish(AABB bounds) {
        int minSectionX = SectionPos.blockToSectionCoord(Mth.floor(bounds.minX));
        int maxSectionX = SectionPos.blockToSectionCoord(Mth.floor(bounds.maxX - 1.0E-7));
        int minSectionY = SectionPos.blockToSectionCoord(Mth.floor(bounds.minY));
        int maxSectionY = SectionPos.blockToSectionCoord(Mth.floor(bounds.maxY - 1.0E-7));
        int minSectionZ = SectionPos.blockToSectionCoord(Mth.floor(bounds.minZ));
        int maxSectionZ = SectionPos.blockToSectionCoord(Mth.floor(bounds.maxZ - 1.0E-7));
        for (int sx = minSectionX; sx <= maxSectionX; sx++) {
            for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                for (int sz = minSectionZ; sz <= maxSectionZ; sz++) {
                    if (uploadSection(sx, sy, sz)) {
                        sendClearVisuals(sx, sy, sz);
                    }
                }
            }
        }
        int extinguishedCells;
        if (nativeReady()) {
            extinguishedCells = RealisticFireNativeSolver.extinguish(
                    worldHandle,
                    Mth.floor(bounds.minX),
                    Mth.floor(bounds.minY),
                    Mth.floor(bounds.minZ),
                    Mth.floor(bounds.maxX - 1.0E-7),
                    Mth.floor(bounds.maxY - 1.0E-7),
                    Mth.floor(bounds.maxZ - 1.0E-7));
        } else {
            extinguishedCells = fallback.extinguish(bounds);
        }
        NeoForge.EVENT_BUS.post(new RealisticFireEvent.Extinguish(level, BlockPos.containing(bounds.getCenter())));
        return extinguishedCells;
    }

    public double queryTemperature(BlockPos pos) {
        if (nativeReady()) {
            return RealisticFireNativeSolver.queryTemperature(worldHandle, pos.getX(), pos.getY(), pos.getZ());
        }
        return fallback.query(pos);
    }

    public void uploadChunk(ChunkAccess chunkAccess) {
        if (!(chunkAccess instanceof LevelChunk chunk)) {
            return;
        }
        ChunkPos chunkPos = chunk.getPos();
        for (int index = 0; index < chunk.getSectionsCount(); index++) {
            LevelChunkSection section = chunk.getSection(index);
            if (!section.hasOnlyAir()) {
                uploadSection(chunkPos.x, chunk.getSectionYFromSectionIndex(index), chunkPos.z, section);
            }
        }
    }

    public void removeChunk(ChunkPos pos) {
        for (long section : uploadedSections) {
            if (SectionPos.x(section) == pos.x && SectionPos.z(section) == pos.z) {
                if (nativeReady()) {
                    RealisticFireNativeSolver.setTileLoaded(worldHandle, SectionPos.x(section), SectionPos.y(section), SectionPos.z(section), false);
                }
                sendClearVisuals(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
            }
        }
        persist();
    }

    private boolean nativeReady() {
        boolean ready = worldHandle != 0L && RealisticFireNativeSolver.available();
        if (!ready && !nativeFailureLogged && RealisticFireConfig.SERVER.requireNative.get()) {
            nativeFailureLogged = true;
            RealisticFire.LOGGER.warn("Realistic Fire native solver is unavailable in {}; destructive spread is paused.", level.dimension().location());
        }
        return ready;
    }

    private void uploadAround(BlockPos pos, int radius) {
        int minSectionX = SectionPos.blockToSectionCoord(pos.getX() - radius);
        int maxSectionX = SectionPos.blockToSectionCoord(pos.getX() + radius);
        int minSectionY = SectionPos.blockToSectionCoord(pos.getY() - radius);
        int maxSectionY = SectionPos.blockToSectionCoord(pos.getY() + radius);
        int minSectionZ = SectionPos.blockToSectionCoord(pos.getZ() - radius);
        int maxSectionZ = SectionPos.blockToSectionCoord(pos.getZ() + radius);
        for (int sx = minSectionX; sx <= maxSectionX; sx++) {
            for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                for (int sz = minSectionZ; sz <= maxSectionZ; sz++) {
                    uploadSection(sx, sy, sz);
                }
            }
        }
    }

    private boolean uploadSection(int sx, int sy, int sz) {
        if (!level.hasChunk(sx, sz)) {
            return false;
        }
        LevelChunk chunk = level.getChunk(sx, sz);
        int sectionIndex = level.getSectionIndexFromSectionY(sy);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) {
            return false;
        }
        return uploadSection(sx, sy, sz, chunk.getSection(sectionIndex));
    }

    private boolean uploadSection(int sx, int sy, int sz, LevelChunkSection section) {
        long sectionKey = SectionPos.asLong(sx, sy, sz);
        if (uploadedSections.contains(sectionKey) && nativeReady()) {
            RealisticFireNativeSolver.setTileLoaded(worldHandle, sx, sy, sz, true);
            return true;
        }
        uploadMaterialTable();
        int[] materialIds = new int[CELL_COUNT];
        float[] initialState = new float[CELL_COUNT * STATE_FIELDS];
        boolean hasInterestingCell = false;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int index = (y << 8) | (z << 4) | x;
                    BlockState state = section.getBlockState(x, y, z);
                    FireMaterial material = FireMaterialRegistry.material(state);
                    materialIds[index] = materialIdForState(state, material);
                    int base = index * STATE_FIELDS;
                    System.arraycopy(initialCellState(state, material), 0, initialState, base, STATE_FIELDS);
                    hasInterestingCell |= material.flammable() || state.is(Blocks.FIRE) || state.is(Blocks.LAVA);
                }
            }
        }
        if (!hasInterestingCell) {
            return false;
        }
        if (nativeReady()) {
            uploadedSections.add(sectionKey);
            RealisticFireNativeSolver.setTile(worldHandle, sx, sy, sz, materialIds, initialState);
            return true;
        }
        return false;
    }

    private void refreshDirtyCells() {
        if (dirtyCells.isEmpty()) {
            return;
        }
        long[] cells = dirtyCells.toLongArray();
        dirtyCells.clear();
        for (long cell : cells) {
            BlockPos pos = BlockPos.of(cell);
            if (!level.isLoaded(pos)) {
                dirtyCells.add(cell);
                continue;
            }
            int sx = SectionPos.blockToSectionCoord(pos.getX());
            int sy = SectionPos.blockToSectionCoord(pos.getY());
            int sz = SectionPos.blockToSectionCoord(pos.getZ());
            if (!uploadedSections.contains(SectionPos.asLong(sx, sy, sz)) && !uploadSection(sx, sy, sz)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            FireMaterial material = FireMaterialRegistry.material(state);
            RealisticFireNativeSolver.setCell(worldHandle, pos.getX(), pos.getY(), pos.getZ(), materialIdForState(state, material), initialCellState(state, material));
            if (state.is(Blocks.FIRE) || state.is(Blocks.LAVA)) {
                RealisticFireNativeSolver.ignite(worldHandle, pos.getX(), pos.getY(), pos.getZ(), 1200.0f, 0);
                if (state.is(Blocks.FIRE)) {
                    BlockPos fuelSurface = findFlammableSurfaceTarget(pos);
                    if (fuelSurface != null) {
                        int fsx = SectionPos.blockToSectionCoord(fuelSurface.getX());
                        int fsy = SectionPos.blockToSectionCoord(fuelSurface.getY());
                        int fsz = SectionPos.blockToSectionCoord(fuelSurface.getZ());
                        if (uploadedSections.contains(SectionPos.asLong(fsx, fsy, fsz)) || uploadSection(fsx, fsy, fsz)) {
                            RealisticFireNativeSolver.ignite(worldHandle, fuelSurface.getX(), fuelSurface.getY(), fuelSurface.getZ(), 1200.0f, 0);
                        }
                    }
                }
            }
        }
    }

    private BlockPos findFlammableSurfaceTarget(BlockPos pos) {
        for (BlockPos candidate : new BlockPos[]{
                pos.below(),
                pos.north(),
                pos.south(),
                pos.east(),
                pos.west(),
                pos.above()
        }) {
            if (level.isLoaded(candidate) && FireMaterialRegistry.material(level.getBlockState(candidate)).flammable()) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private int materialIdForState(BlockState state, FireMaterial material) {
        return state.is(Blocks.FIRE) || state.is(Blocks.LAVA) ? -1 : material.id();
    }

    private float[] initialCellState(BlockState state, FireMaterial material) {
        return new float[]{
                state.is(Blocks.FIRE) || state.is(Blocks.LAVA) ? 1200.0f : 293.15f,
                (float) material.fuel(),
                1.0f,
                0.0f,
                (float) material.moisture(),
                0.0f
        };
    }

    private void uploadMaterialTable() {
        if (!nativeReady()) {
            return;
        }
        for (int id = 1; id < FireMaterialRegistry.materialCount(); id++) {
            FireMaterial material = FireMaterialRegistry.materialById(id);
            RealisticFireNativeSolver.setMaterial(
                    worldHandle,
                    id,
                    (float) material.fuel(),
                    material.charState() != null,
                    material.ashState() != null,
                    (float) material.ignitionTemperatureK(),
                    (float) material.burnRate(),
                    (float) material.heatRelease(),
                    (float) material.smokeYield(),
                    (float) material.insulation());
        }
    }

    private void applyMutations(RealisticFireNativeSolver.StepResult result) {
        int[] mutations = result.mutations();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < result.mutationCount(); i++) {
            int base = i * RealisticFireNativeSolver.MUTATION_RECORD_INTS;
            pos.set(mutations[base], mutations[base + 1], mutations[base + 2]);
            int action = mutations[base + 3];
            int materialId = mutations[base + 4];
            int aux = mutations[base + 5];
            if (action == RealisticFireNativeSolver.ACTION_DAMAGE_ENTITY_AREA) {
                damageEntities(pos, aux);
                continue;
            }
            BlockState newState = mutationState(action, materialId);
            if (newState == null || !level.isLoaded(pos)) {
                continue;
            }
            BlockState oldState = level.getBlockState(pos);
            if (isDestructiveMutation(action) && materialId > 0 && !matchesExpectedMutationInput(action, materialId, oldState)) {
                markDirty(pos);
                continue;
            }
            if (oldState == newState || oldState.isAir() && newState.isAir()) {
                continue;
            }
            NeoForge.EVENT_BUS.post(new RealisticFireEvent.BurnBlock(level, pos, oldState, newState));
            level.setBlock(pos, newState, Block.UPDATE_ALL);
        }
    }

    private BlockState mutationState(int action, int materialId) {
        FireMaterial material = FireMaterialRegistry.materialById(materialId);
        return switch (action) {
            case RealisticFireNativeSolver.ACTION_SET_CHAR -> material.charState();
            case RealisticFireNativeSolver.ACTION_SET_ASH -> material.ashState() != null ? material.ashState() : RealisticFireBlocks.ASH.get().defaultBlockState();
            case RealisticFireNativeSolver.ACTION_SET_AIR, RealisticFireNativeSolver.ACTION_EXTINGUISH -> Blocks.AIR.defaultBlockState();
            default -> null;
        };
    }

    private boolean isDestructiveMutation(int action) {
        return action == RealisticFireNativeSolver.ACTION_SET_CHAR
                || action == RealisticFireNativeSolver.ACTION_SET_ASH
                || action == RealisticFireNativeSolver.ACTION_SET_AIR
                || action == RealisticFireNativeSolver.ACTION_EXTINGUISH;
    }

    private boolean matchesExpectedMutationInput(int action, int materialId, BlockState oldState) {
        if (FireMaterialRegistry.material(oldState).id() == materialId) {
            return true;
        }
        if (action == RealisticFireNativeSolver.ACTION_SET_ASH
                || action == RealisticFireNativeSolver.ACTION_SET_AIR
                || action == RealisticFireNativeSolver.ACTION_EXTINGUISH) {
            BlockState charState = charStateFor(materialId);
            return charState != null && oldState.is(charState.getBlock());
        }
        return false;
    }

    private BlockState charStateFor(int materialId) {
        FireMaterial material = FireMaterialRegistry.materialById(materialId);
        return material.charState();
    }

    private void damageEntities(BlockPos pos, int aux) {
        float amount = Math.max(1.0f, aux / 100.0f);
        DamageSource source = level.damageSources().inFire();
        AABB bounds = new AABB(pos).inflate(1.25);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, bounds)) {
            entity.hurt(source, amount);
        }
    }

    private void sendVisuals(RealisticFireNativeSolver.StepResult result) {
        if (result.visualCount() <= 0) {
            return;
        }
        int length = result.visualCount() * RealisticFireNativeSolver.VISUAL_RECORD_FLOATS;
        float[] visuals = result.visuals();
        Long2IntOpenHashMap sectionRecordCounts = new Long2IntOpenHashMap();
        for (int base = 0; base < length; base += RealisticFireNativeSolver.VISUAL_RECORD_FLOATS) {
            sectionRecordCounts.addTo(visualSectionKey(visuals, base), 1);
        }

        Long2ObjectMap<float[]> bySection = new Long2ObjectOpenHashMap<>();
        for (var entry : sectionRecordCounts.long2IntEntrySet()) {
            bySection.put(entry.getLongKey(), new float[entry.getIntValue() * RealisticFireNativeSolver.VISUAL_RECORD_FLOATS]);
        }

        Long2IntOpenHashMap sectionWriteOffsets = new Long2IntOpenHashMap();
        for (int base = 0; base < length; base += RealisticFireNativeSolver.VISUAL_RECORD_FLOATS) {
            long key = visualSectionKey(visuals, base);
            int offset = sectionWriteOffsets.addTo(key, RealisticFireNativeSolver.VISUAL_RECORD_FLOATS);
            System.arraycopy(visuals, base, bySection.get(key), offset, RealisticFireNativeSolver.VISUAL_RECORD_FLOATS);
        }

        for (Long2ObjectMap.Entry<float[]> entry : bySection.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            PacketDistributor.sendToPlayersInDimension(level, new ClientboundFireVisualDeltaPayload(
                    level.dimension().location().toString(),
                    ++visualSequence,
                    SectionPos.x(key),
                    SectionPos.y(key),
                    SectionPos.z(key),
                    Arrays.copyOf(entry.getValue(), entry.getValue().length)));
        }
    }

    private long visualSectionKey(float[] visuals, int base) {
        int sx = SectionPos.blockToSectionCoord(Mth.floor(visuals[base]));
        int sy = SectionPos.blockToSectionCoord(Mth.floor(visuals[base + 1]));
        int sz = SectionPos.blockToSectionCoord(Mth.floor(visuals[base + 2]));
        return SectionPos.asLong(sx, sy, sz);
    }

    private void sendClearVisuals(int sx, int sy, int sz) {
        PacketDistributor.sendToPlayersInDimension(level, new ClientboundFireVisualDeltaPayload(
                level.dimension().location().toString(),
                ++visualSequence,
                sx,
                sy,
                sz,
                new float[0]));
    }

    private void persist() {
        if (nativeReady()) {
            refreshDirtyCells();
        }
        if (!dirtyCells.isEmpty()) {
            savedData.update(new byte[0], new long[0], FireMaterialRegistry.materialSignature());
            return;
        }
        savedData.update(
                nativeReady() ? RealisticFireNativeSolver.save(worldHandle) : savedData.snapshot(),
                uploadedSections.toLongArray(),
                FireMaterialRegistry.materialSignature());
    }
}
