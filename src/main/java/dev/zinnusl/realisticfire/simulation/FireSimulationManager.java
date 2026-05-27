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
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

public final class FireSimulationManager {
    private static final int CELL_COUNT = 16 * 16 * 16;
    private static final int STATE_FIELDS = 6;
    private static final long VISUAL_CACHE_TTL_NANOS = 5_000_000_000L;
    private static final long VISUAL_RECORD_TTL_NANOS = 2_000_000_000L;
    private static final int MAX_CACHED_VISUAL_RECORDS_PER_SECTION = 8192;
    private static final Map<ServerLevel, FireSimulationManager> MANAGERS = new WeakHashMap<>();
    private static final Map<ServerLevel, Long> PURGE_DEADLINES = new WeakHashMap<>();
    private static final long PURGE_WINDOW_NANOS = 10_000_000_000L;
    private static String debugMode = "off";

    private static final float LIGHT_FLAME_THRESHOLD = 0.24f;
    private static final float LIGHT_HEAT_THRESHOLD = 0.24f;
    private static final int LIGHT_UPDATE_INTERVAL_TICKS = 4;
    private static final int MAX_LIGHT_BLOCKS_PER_LEVEL = 256;
    private static final int MUTATION_VISUAL_RECORD_LIMIT = 1024;
    private static final int SMOKE_EXPOSURE_INTERVAL_TICKS = 5;
    private static final float SMOKE_EXPOSURE_HEIGHT = 7.0f;
    private static final float SMOKE_EXPOSURE_RADIUS = 2.5f;
    private static final float SMOKE_MIN_CONCENTRATION = 0.08f;
    private static final float SMOKE_DOSE_DECAY = 0.74f;
    private static final float SMOKE_CHOKE_DOSE = 0.75f;
    private static final float SMOKE_DAMAGE_DOSE = 1.45f;
    private static final float SMOKE_MAX_DOSE = 6.0f;
    // How far above a hot cell to search for the first open block to host its light. The flame
    // licks up off the surface, so a light a couple of cells up still reads as the fire's glow and
    // lets a ground fire cast light even when the block directly above it is occupied.
    private static final int LIGHT_UPWARD_SCAN = 3;

    private static final int MAX_PENDING_STRIPS_PER_TICK = 16;

    private final ServerLevel level;
    private final LongSet uploadedSections = new LongOpenHashSet();
    // Reused each tick to dedupe the ring of sections around the fire front (lazy upload).
    private final LongOpenHashSet frontScratch = new LongOpenHashSet();
    // Optional solver-thread support (config useSolverThread). The solverLock makes EVERY native
    // world access mutually exclusive with the background step, so there are no data races on the
    // native world regardless of which thread initiates the call.
    private final ReentrantLock solverLock = new ReentrantLock();
    private ExecutorService solverExecutor;
    private Future<StepBatch> pendingStep;

    private record SubstepParams(int substeps, float dt, int maxCellsPerSubstep, int maxMutations,
                                 int maxVisuals, boolean sendVisuals, long deadlineNanos) {
    }

    private record StepBatch(List<RealisticFireNativeSolver.StepResult> results, boolean sendVisuals) {
    }
    // Chunks loaded during a purge window whose fire-strip was deferred out of the
    // ChunkEvent.Load callback. Stripping in-callback re-enters chunk.setBlockState,
    // which Sable's LevelChunkMixin wrapOperation intercepts to read neighbor block
    // states via a synchronous ServerChunkCache.getChunk — that managedBlocks the
    // server thread during spawn-region prep and deadlocks world load. Draining on the
    // level tick (when chunk + neighbors are fully loaded) lets Sable's neighbor fetch
    // return immediately. Guarded by synchronizing on the set itself: ChunkEvent.Load
    // enqueues and tick() drains, both per-level, and the event may complete off the
    // main thread from a chunk-load worker.
    private final LongSet pendingStrips = new LongOpenHashSet();
    private final LongSet dirtyCells = new LongOpenHashSet();
    private final Long2ObjectMap<float[]> latestSectionVisuals = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<long[]> latestSectionVisualRecordNanos = new Long2ObjectOpenHashMap<>();
    private final Long2LongMap latestSectionVisualsNanos = new Long2LongOpenHashMap();
    private final LongSet currentLitPositions = new LongOpenHashSet();
    private final Map<LivingEntity, Float> smokeDoses = new WeakHashMap<>();
    private final JavaFireFallback fallback = new JavaFireFallback();
    private final FireSavedData savedData;
    // Horizontal sub-block resolution captured at construction (changing it needs a restart).
    private final int cellsPerBlockAxis = Math.max(1, RealisticFireConfig.SERVER.cellsPerBlockAxis.get());
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
            // Set the cell-grid resolution before anything is uploaded. A snapshot saved at a
            // different resolution is rejected by the native loader (fail-safe cold start).
            RealisticFireNativeSolver.setSubBlockResolution(this.worldHandle, cellsPerBlockAxis);
            uploadMaterialTable();
            loadedSnapshot = compatibleSnapshot && RealisticFireNativeSolver.load(this.worldHandle, snapshot);
        }
        if (loadedSnapshot) {
            // The snapshot only holds tiles with live fire (cold tiles reconstruct from world blocks
            // on chunk load), so resync the uploaded-section set to exactly what was restored — using
            // the native tile keys, not the persisted section list which may include cold sections.
            int[] keys = RealisticFireNativeSolver.loadedTileKeys(this.worldHandle);
            for (int i = 0; i + 2 < keys.length; i += 3) {
                this.uploadedSections.add(SectionPos.asLong(keys[i], keys[i + 1], keys[i + 2]));
                RealisticFireNativeSolver.setTileLoaded(
                        this.worldHandle, keys[i], keys[i + 1], keys[i + 2], false);
            }
        }
    }

    public static synchronized FireSimulationManager forLevel(ServerLevel level) {
        return MANAGERS.computeIfAbsent(level, FireSimulationManager::new);
    }

    /**
     * Wipe persisted simulation state and arm a 10-second window during which every
     * loading chunk has its vanilla fire and {@code FIRE_LIGHT} blocks stripped to air.
     * Called from {@link net.neoforged.neoforge.event.level.LevelEvent.Load} so test
     * worlds always come up cold regardless of what was saved last session.
     */
    public static synchronized void markPurgeOnLoad(ServerLevel level) {
        FireSavedData savedData = FireSavedData.get(level);
        savedData.update(new byte[0], new long[0], FireMaterialRegistry.materialSignature());
        PURGE_DEADLINES.put(level, System.nanoTime() + PURGE_WINDOW_NANOS);
    }

    public static synchronized void unload(ServerLevel level) {
        FireSimulationManager manager = MANAGERS.remove(level);
        PURGE_DEADLINES.remove(level);
        if (manager != null) {
            manager.shutdownSolverThread();
            manager.persist();
            manager.cleanupAllLights();
            manager.latestSectionVisuals.clear();
            manager.latestSectionVisualRecordNanos.clear();
            manager.latestSectionVisualsNanos.clear();
            RealisticFireNativeSolver.destroyWorld(manager.worldHandle);
            manager.worldHandle = 0L;
        }
    }

    // Wait for any in-flight background step (it holds the native world) so the world handle can be
    // safely destroyed/recreated, then stop the executor.
    private void awaitPendingStep() {
        if (pendingStep != null) {
            try {
                pendingStep.get();
            } catch (Exception ignored) {
                // the step's failure is already logged where its result is consumed
            }
            pendingStep = null;
        }
    }

    private void shutdownSolverThread() {
        awaitPendingStep();
        if (solverExecutor != null) {
            solverExecutor.shutdownNow();
            solverExecutor = null;
        }
    }

    public static synchronized boolean isExpectedLightAt(ServerLevel level, BlockPos pos) {
        FireSimulationManager manager = MANAGERS.get(level);
        return manager != null && manager.currentLitPositions.contains(pos.asLong());
    }

    public static synchronized void invalidateMaterialTables() {
        for (FireSimulationManager manager : MANAGERS.values()) {
            manager.awaitPendingStep(); // the old world handle must not be in use by a background step
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
            RealisticFireNativeSolver.setSubBlockResolution(manager.worldHandle, manager.cellsPerBlockAxis);
            manager.latestSectionVisuals.clear();
            manager.latestSectionVisualRecordNanos.clear();
            manager.latestSectionVisualsNanos.clear();
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
        drainPendingStrips();
        if (!RealisticFireConfig.SERVER.enabled.get()) {
            return;
        }
        if (!nativeReady()) {
            if (!RealisticFireConfig.SERVER.requireNative.get()) {
                fallback.tick(level);
            }
            return;
        }

        if (RealisticFireConfig.SERVER.useSolverThread.get()) {
            tickThreaded();
        } else {
            tickSynchronous();
        }
        if ("stats".equals(debugMode) && level.getGameTime() % 100L == 0L) {
            RealisticFire.LOGGER.info("Realistic Fire {}: {} uploaded sections, native={}", level.dimension().location(), uploadedSections.size(), RealisticFireNativeSolver.available());
        }
    }

    // Classic path: run the solver step inline on the server thread.
    private void tickSynchronous() {
        uploadMaterialTable();
        refreshDirtyCells();
        applyStepBatch(runSubsteps(computeSubstepParams()));
        ensureFireFrontUploaded();
        detectWaterOnFire();
        applySmokeExposure();
        updateDynamicLights();
        if (level.getGameTime() % 100L == 0L) {
            persist();
        }
    }

    // Pipelined path: apply the PREVIOUS tick's step (block changes land one tick late), prepare the
    // world, then submit this tick's step to the background solver thread so its cost overlaps the
    // rest of the server tick instead of extending it.
    private void tickThreaded() {
        if (pendingStep != null) {
            StepBatch batch;
            try {
                batch = pendingStep.get();
            } catch (Exception exception) {
                RealisticFire.LOGGER.error("Realistic Fire solver thread step failed", exception);
                batch = new StepBatch(List.of(), false);
            }
            pendingStep = null;
            applyStepBatch(batch);
        }
        uploadMaterialTable();
        refreshDirtyCells();
        ensureFireFrontUploaded();
        detectWaterOnFire();
        applySmokeExposure();
        updateDynamicLights();
        if (level.getGameTime() % 100L == 0L) {
            persist();
        }
        if (solverExecutor == null) {
            solverExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "RealisticFire-Solver");
                thread.setDaemon(true);
                return thread;
            });
        }
        SubstepParams params = computeSubstepParams();
        pendingStep = solverExecutor.submit(() -> runSubsteps(params));
    }

    private SubstepParams computeSubstepParams() {
        int substeps = RealisticFireConfig.SERVER.solverSubsteps.get();
        // maxActiveCellsPerLevel is a BLOCK budget; the cell budget is S*S larger. The wall-clock
        // deadline is the real governor, so a generous cell cap just lets the fire progress.
        int subCellFactor = cellsPerBlockAxis * cellsPerBlockAxis;
        long maxCells = (long) RealisticFireConfig.SERVER.maxActiveCellsPerLevel.get() * subCellFactor;
        int maxCellsPerSubstep = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, maxCells / substeps));
        int maxMutations = RealisticFireConfig.SERVER.maxBlockMutationsPerTick.get();
        int maxVisuals = RealisticFireConfig.SERVER.maxVisualRecordsPerTick.get();
        boolean sendVisuals = maxVisuals > 0
                && level.getGameTime() % RealisticFireConfig.SERVER.visualSendIntervalTicks.get() == 0L;
        long deadline = System.nanoTime()
                + (long) (RealisticFireConfig.SERVER.maxSolverMillisPerTick.get() * 1_000_000.0);
        return new SubstepParams(substeps, 1.0f / 20.0f / substeps, maxCellsPerSubstep, maxMutations,
                maxVisuals, sendVisuals, deadline);
    }

    // Runs the substep loop and returns the per-substep results WITHOUT touching the Minecraft world
    // (no applyMutations/sendVisuals — those happen on the server thread in applyStepBatch). Holds
    // solverLock so it is mutually exclusive with every other native world access.
    private StepBatch runSubsteps(SubstepParams p) {
        solverLock.lock();
        try {
            List<RealisticFireNativeSolver.StepResult> results = new ArrayList<>(p.substeps());
            int remainingMutations = p.maxMutations();
            int remainingVisuals = p.maxVisuals();
            for (int i = 0; i < p.substeps(); i++) {
                RealisticFireNativeSolver.StepResult result = RealisticFireNativeSolver.step(
                        worldHandle, p.dt(), p.maxCellsPerSubstep(),
                        Math.max(remainingMutations, 0),
                        p.sendVisuals() ? Math.max(remainingVisuals, 0) : 0);
                results.add(result);
                remainingMutations -= result.mutationCount();
                if (p.sendVisuals()) {
                    remainingVisuals -= result.visualCount();
                }
                if (remainingMutations <= 0 || System.nanoTime() >= p.deadlineNanos()) {
                    break;
                }
            }
            return new StepBatch(results, p.sendVisuals());
        } finally {
            solverLock.unlock();
        }
    }

    private void applyStepBatch(StepBatch batch) {
        for (RealisticFireNativeSolver.StepResult result : batch.results()) {
            float[] mutationVisuals = applyMutations(result);
            if (batch.sendVisuals() || mutationVisuals.length > 0) {
                sendVisuals(result, mutationVisuals);
            }
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
            solverLock.lock();
            try {
                RealisticFireNativeSolver.ignite(worldHandle, pos.getX(), pos.getY(), pos.getZ(), temperatureK, radius);
            } finally {
                solverLock.unlock();
            }
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
        solverLock.lock();
        try {
            RealisticFireNativeSolver.setCell(
                    worldHandle,
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    materialIdForState(state, material),
                    initialCellState(state, material));
            RealisticFireNativeSolver.ignite(worldHandle, pos.getX(), pos.getY(), pos.getZ(), temperatureK, 0);
        } finally {
            solverLock.unlock();
        }
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
            solverLock.lock();
            try {
                extinguishedCells = RealisticFireNativeSolver.extinguish(
                        worldHandle,
                        Mth.floor(bounds.minX),
                        Mth.floor(bounds.minY),
                        Mth.floor(bounds.minZ),
                        Mth.floor(bounds.maxX - 1.0E-7),
                        Mth.floor(bounds.maxY - 1.0E-7),
                        Mth.floor(bounds.maxZ - 1.0E-7));
            } finally {
                solverLock.unlock();
            }
        } else {
            extinguishedCells = fallback.extinguish(bounds);
        }
        NeoForge.EVENT_BUS.post(new RealisticFireEvent.Extinguish(level, BlockPos.containing(bounds.getCenter())));
        return extinguishedCells;
    }

    public double queryTemperature(BlockPos pos) {
        if (nativeReady()) {
            solverLock.lock();
            try {
                return RealisticFireNativeSolver.queryTemperature(worldHandle, pos.getX(), pos.getY(), pos.getZ());
            } finally {
                solverLock.unlock();
            }
        }
        return fallback.query(pos);
    }

    public void uploadChunk(ChunkAccess chunkAccess) {
        if (!(chunkAccess instanceof LevelChunk chunk)) {
            return;
        }
        ChunkPos chunkPos = chunk.getPos();
        if (consumePurgeWindow()) {
            // Defer the fire-strip to the next level tick instead of calling
            // chunk.setBlockState inside ChunkEvent.Load (see pendingStrips javadoc).
            synchronized (pendingStrips) {
                pendingStrips.add(chunkPos.toLong());
            }
        }
        // Lazy upload: only RESUME sections that were already part of a fire (so a reloaded chunk's
        // fire keeps ticking). Cold sections are NOT eagerly uploaded — that was uploading every
        // flammable section of every loaded chunk as a 4 MB sub-block tile (~38 GB across a field).
        // New sections come in on demand: around ignitions (uploadAround) and around the spreading
        // fire front each tick (ensureFireFrontUploaded).
        for (int index = 0; index < chunk.getSectionsCount(); index++) {
            int sy = chunk.getSectionYFromSectionIndex(index);
            if (!uploadedSections.contains(SectionPos.asLong(chunkPos.x, sy, chunkPos.z))) {
                continue;
            }
            LevelChunkSection section = chunk.getSection(index);
            if (!section.hasOnlyAir()) {
                uploadSection(chunkPos.x, sy, chunkPos.z, section);
            }
        }
    }

    private boolean consumePurgeWindow() {
        synchronized (FireSimulationManager.class) {
            Long deadline = PURGE_DEADLINES.get(level);
            if (deadline == null) {
                return false;
            }
            if (System.nanoTime() > deadline) {
                PURGE_DEADLINES.remove(level);
                return false;
            }
            return true;
        }
    }

    private void stripFireBlocksFromChunk(LevelChunk chunk) {
        // Mutate via chunk.setBlockState rather than level.setBlock — ChunkEvent.Load explicitly
        // warns that level.setBlock during the load callback can cause chunk-loading deadlocks
        // (see ChunkEvent.Load javadoc; NeoForge installs `currentlyLoading` precisely to
        // mitigate this). chunk.setBlockState updates the section + heightmaps + light engine
        // without re-entering the chunk source.
        Block fireLight = RealisticFireBlocks.FIRE_LIGHT.get();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minBlockX = chunk.getPos().getMinBlockX();
        int minBlockZ = chunk.getPos().getMinBlockZ();
        for (int sIdx = 0; sIdx < chunk.getSectionsCount(); sIdx++) {
            LevelChunkSection section = chunk.getSection(sIdx);
            if (section.hasOnlyAir()) {
                continue;
            }
            int baseY = chunk.getSectionYFromSectionIndex(sIdx) * 16;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.is(Blocks.FIRE) || state.is(fireLight)) {
                            pos.set(minBlockX + x, baseY + y, minBlockZ + z);
                            chunk.setBlockState(pos, air, false);
                        }
                    }
                }
            }
        }
    }

    // Runs on the server tick: re-fetches each deferred chunk and strips its fire blocks
    // now that the chunk and its neighbors are fully loaded, so Sable's neighbor getChunk
    // returns without managedBlock-ing the server thread. Bounded per tick to avoid a
    // load-time spike; the remainder is re-queued for the next tick. Chunks that have since
    // unloaded are dropped (they re-enqueue on next load if still within the purge window).
    private void drainPendingStrips() {
        long[] batch;
        synchronized (pendingStrips) {
            if (pendingStrips.isEmpty()) {
                return;
            }
            int count = Math.min(pendingStrips.size(), MAX_PENDING_STRIPS_PER_TICK);
            batch = new long[count];
            LongIterator it = pendingStrips.iterator();
            for (int i = 0; i < count; i++) {
                batch[i] = it.nextLong();
                it.remove();
            }
        }
        for (long packed : batch) {
            int x = ChunkPos.getX(packed);
            int z = ChunkPos.getZ(packed);
            if (!level.hasChunk(x, z)) {
                continue;
            }
            stripFireBlocksFromChunk(level.getChunk(x, z));
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

    // Lazy upload: each tick, make sure the 3x3x3 ring of sections around every active (burning) tile
    // is uploaded, so the fire never stalls at the edge of the uploaded region. Already-uploaded
    // sections are skipped before any chunk lookup, so the steady-state cost is one cheap set lookup
    // per ring section; only the handful of fresh sections at the advancing front do real work.
    private void ensureFireFrontUploaded() {
        if (!nativeReady()) {
            return;
        }
        int[] active = RealisticFireNativeSolver.activeTileKeys(worldHandle);
        if (active.length == 0) {
            return;
        }
        frontScratch.clear();
        for (int i = 0; i + 3 <= active.length; i += 3) {
            int ax = active[i];
            int ay = active[i + 1];
            int az = active[i + 2];
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        frontScratch.add(SectionPos.asLong(ax + dx, ay + dy, az + dz));
                    }
                }
            }
        }
        LongIterator it = frontScratch.iterator();
        while (it.hasNext()) {
            long section = it.nextLong();
            if (!uploadedSections.contains(section)) {
                uploadSection(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
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
        // Locked so an event-driven upload (e.g. a chunk loading) can't race the background step.
        solverLock.lock();
        try {
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
        } finally {
            solverLock.unlock();
        }
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
        if (state.is(Blocks.FIRE) || state.is(Blocks.LAVA)) {
            return -1;
        }
        // -2 is the Rust solver's water marker — water cells short-circuit to ambient and
        // never accumulate conducted heat, so fire cannot propagate across them.
        if (state.getFluidState().is(FluidTags.WATER)) {
            return -2;
        }
        return material.id();
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

    private float[] applyMutations(RealisticFireNativeSolver.StepResult result) {
        int[] mutations = result.mutations();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int forcedCapacity = Math.min(result.mutationCount(), MUTATION_VISUAL_RECORD_LIMIT);
        float[] forcedVisuals = new float[forcedCapacity * RealisticFireNativeSolver.VISUAL_RECORD_FLOATS];
        int forcedCount = 0;
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
                syncNativeCellToBlock(pos, oldState, false);
                continue;
            }
            if (oldState == newState || oldState.isAir() && newState.isAir()) {
                syncNativeCellToBlock(pos, newState, action == RealisticFireNativeSolver.ACTION_SET_CHAR);
                if (!oldState.isAir()) {
                    forcedCount = appendMutationVisual(forcedVisuals, forcedCount, pos, action, materialId);
                }
                continue;
            }
            NeoForge.EVENT_BUS.post(new RealisticFireEvent.BurnBlock(level, pos, oldState, newState));
            if (level.setBlock(pos, newState, Block.UPDATE_ALL)) {
                syncNativeCellToBlock(pos, newState, action == RealisticFireNativeSolver.ACTION_SET_CHAR);
                forcedCount = appendMutationVisual(forcedVisuals, forcedCount, pos, action, materialId);
            }
        }
        return forcedCount == forcedCapacity
                ? forcedVisuals
                : Arrays.copyOf(forcedVisuals, forcedCount * RealisticFireNativeSolver.VISUAL_RECORD_FLOATS);
    }

    private int appendMutationVisual(float[] out, int record, BlockPos pos, int action, int materialId) {
        if (record * RealisticFireNativeSolver.VISUAL_RECORD_FLOATS >= out.length) {
            return record;
        }
        if (action == RealisticFireNativeSolver.ACTION_EXTINGUISH || materialId <= 0) {
            return record;
        }

        FireMaterial material = FireMaterialRegistry.materialById(materialId);
        float smokeYield = (float) material.smokeYield();
        float temperature;
        float flame;
        float smoke;
        float heat;
        if (action == RealisticFireNativeSolver.ACTION_SET_CHAR) {
            temperature = 690.0f;
            flame = 0.28f;
            smoke = 1.15f + smokeYield * 0.90f;
            heat = 0.46f;
        } else if (action == RealisticFireNativeSolver.ACTION_SET_ASH) {
            temperature = 560.0f;
            flame = 0.12f;
            smoke = 1.35f + smokeYield * 1.05f;
            heat = 0.34f;
        } else if (action == RealisticFireNativeSolver.ACTION_SET_AIR) {
            temperature = 540.0f;
            flame = 0.18f;
            smoke = 0.95f + smokeYield * 0.85f;
            heat = 0.30f;
        } else {
            return record;
        }

        int base = record * RealisticFireNativeSolver.VISUAL_RECORD_FLOATS;
        out[base] = pos.getX() + 0.5f;
        out[base + 1] = pos.getY() + 0.5f;
        out[base + 2] = pos.getZ() + 0.5f;
        out[base + 3] = temperature;
        out[base + 4] = flame;
        out[base + 5] = smoke;
        out[base + 6] = 0.45f;
        out[base + 7] = heat;
        return record + 1;
    }

    private void syncNativeCellToBlock(BlockPos pos, BlockState state, boolean preserveHeat) {
        if (!nativeReady()) {
            return;
        }
        FireMaterial material = FireMaterialRegistry.material(state);
        float[] cellState = initialCellState(state, material);
        solverLock.lock();
        try {
            if (preserveHeat) {
                cellState[0] = Math.max(
                        cellState[0],
                        RealisticFireNativeSolver.queryTemperature(worldHandle, pos.getX(), pos.getY(), pos.getZ()));
            }
            RealisticFireNativeSolver.setCell(
                    worldHandle,
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    materialIdForState(state, material),
                    cellState);
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
        } finally {
            solverLock.unlock();
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

    private void applySmokeExposure() {
        if (level.getGameTime() % SMOKE_EXPOSURE_INTERVAL_TICKS != 0L || !nativeReady()) {
            return;
        }
        int[] activeTiles = RealisticFireNativeSolver.activeTileKeys(worldHandle);
        if (activeTiles.length == 0) {
            decaySmokeDoses(Map.of());
            return;
        }

        Map<LivingEntity, Float> concentrations = new HashMap<>();
        BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
        solverLock.lock();
        try {
            for (int i = 0; i + 2 < activeTiles.length; i += 3) {
                int sx = activeTiles[i];
                int sy = activeTiles[i + 1];
                int sz = activeTiles[i + 2];
                AABB bounds = new AABB(
                        SectionPos.sectionToBlockCoord(sx) - SMOKE_EXPOSURE_RADIUS,
                        SectionPos.sectionToBlockCoord(sy),
                        SectionPos.sectionToBlockCoord(sz) - SMOKE_EXPOSURE_RADIUS,
                        SectionPos.sectionToBlockCoord(sx) + 16 + SMOKE_EXPOSURE_RADIUS,
                        SectionPos.sectionToBlockCoord(sy) + 16 + SMOKE_EXPOSURE_HEIGHT,
                        SectionPos.sectionToBlockCoord(sz) + 16 + SMOKE_EXPOSURE_RADIUS);
                for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, bounds)) {
                    if (!entity.isAlive() || entity.isSpectator() || concentrations.containsKey(entity)) {
                        continue;
                    }
                    float concentration = sampleSmokeConcentrationUnsafe(entity, scratch);
                    if (concentration > SMOKE_MIN_CONCENTRATION) {
                        concentrations.put(entity, concentration);
                    }
                }
            }
        } finally {
            solverLock.unlock();
        }
        decaySmokeDoses(concentrations);
    }

    private float sampleSmokeConcentrationUnsafe(LivingEntity entity, BlockPos.MutableBlockPos scratch) {
        int cx = Mth.floor(entity.getX());
        int hy = Mth.floor(entity.getEyeY());
        int cz = Mth.floor(entity.getZ());
        float max = 0.0f;
        for (int dy = 0; dy <= 4; dy++) {
            float heightFalloff = 1.0f - dy * 0.14f;
            for (int sample = 0; sample < 5; sample++) {
                int ox = sample == 1 ? 1 : sample == 2 ? -1 : 0;
                int oz = sample == 3 ? 1 : sample == 4 ? -1 : 0;
                scratch.set(cx + ox, hy - dy, cz + oz);
                float smoke = RealisticFireNativeSolver.querySmoke(
                        worldHandle,
                        scratch.getX(),
                        scratch.getY(),
                        scratch.getZ());
                max = Math.max(max, smoke * heightFalloff * (sample == 0 ? 1.0f : 0.72f));
            }
        }
        return max;
    }

    private void decaySmokeDoses(Map<LivingEntity, Float> concentrations) {
        smokeDoses.entrySet().removeIf(entry -> {
            LivingEntity entity = entry.getKey();
            if (entity == null || !entity.isAlive() || entity.isSpectator()) {
                return true;
            }
            float concentration = concentrations.getOrDefault(entity, 0.0f);
            float dose = Mth.clamp(entry.getValue() * SMOKE_DOSE_DECAY + concentration * 0.42f, 0.0f, SMOKE_MAX_DOSE);
            if (dose < 0.035f && concentration <= 0.0f) {
                return true;
            }
            entry.setValue(dose);
            applySmokeDose(entity, dose, concentration);
            return false;
        });

        for (Map.Entry<LivingEntity, Float> entry : concentrations.entrySet()) {
            smokeDoses.computeIfAbsent(entry.getKey(), ignored -> {
                float dose = Mth.clamp(entry.getValue() * 0.42f, 0.0f, SMOKE_MAX_DOSE);
                applySmokeDose(entry.getKey(), dose, entry.getValue());
                return dose;
            });
        }
    }

    private void applySmokeDose(LivingEntity entity, float dose, float concentration) {
        if (dose < SMOKE_CHOKE_DOSE) {
            return;
        }
        int effectTicks = SMOKE_EXPOSURE_INTERVAL_TICKS * 4;
        int amplifier = dose > SMOKE_DAMAGE_DOSE ? 1 : 0;
        entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, effectTicks, amplifier, true, true));
        if (dose > SMOKE_DAMAGE_DOSE * 0.85f) {
            entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, effectTicks, 0, true, true));
        }
        int airDrop = Mth.floor(6.0f + dose * 5.0f + concentration * 2.0f);
        entity.setAirSupply(Math.max(0, entity.getAirSupply() - airDrop));
        if (dose >= SMOKE_DAMAGE_DOSE) {
            float amount = Mth.clamp(0.75f + (dose - SMOKE_DAMAGE_DOSE) * 0.55f, 0.75f, 3.0f);
            entity.hurt(level.damageSources().drown(), amount);
        }
    }

    private void sendVisuals(RealisticFireNativeSolver.StepResult result, float[] mandatoryVisuals) {
        int solverLength = result.visualCount() * RealisticFireNativeSolver.VISUAL_RECORD_FLOATS;
        int mandatoryLength = mandatoryVisuals == null ? 0 : mandatoryVisuals.length;
        if (solverLength <= 0 && mandatoryLength <= 0) {
            pruneStaleSectionVisuals(System.nanoTime());
            return;
        }
        float[] visuals = result.visuals();
        long now = System.nanoTime();
        Long2IntOpenHashMap sectionRecordCounts = new Long2IntOpenHashMap();
        for (int base = 0; base < solverLength; base += RealisticFireNativeSolver.VISUAL_RECORD_FLOATS) {
            sectionRecordCounts.addTo(visualSectionKey(visuals, base), 1);
        }
        if (mandatoryVisuals != null) {
            for (int base = 0; base < mandatoryLength; base += RealisticFireNativeSolver.VISUAL_RECORD_FLOATS) {
                sectionRecordCounts.addTo(visualSectionKey(mandatoryVisuals, base), 1);
            }
        }

        Long2ObjectMap<float[]> bySection = new Long2ObjectOpenHashMap<>();
        for (var entry : sectionRecordCounts.long2IntEntrySet()) {
            bySection.put(entry.getLongKey(), new float[entry.getIntValue() * RealisticFireNativeSolver.VISUAL_RECORD_FLOATS]);
        }

        Long2IntOpenHashMap sectionWriteOffsets = new Long2IntOpenHashMap();
        for (int base = 0; base < solverLength; base += RealisticFireNativeSolver.VISUAL_RECORD_FLOATS) {
            long key = visualSectionKey(visuals, base);
            int offset = sectionWriteOffsets.addTo(key, RealisticFireNativeSolver.VISUAL_RECORD_FLOATS);
            System.arraycopy(visuals, base, bySection.get(key), offset, RealisticFireNativeSolver.VISUAL_RECORD_FLOATS);
        }
        if (mandatoryVisuals != null) {
            for (int base = 0; base < mandatoryLength; base += RealisticFireNativeSolver.VISUAL_RECORD_FLOATS) {
                long key = visualSectionKey(mandatoryVisuals, base);
                int offset = sectionWriteOffsets.addTo(key, RealisticFireNativeSolver.VISUAL_RECORD_FLOATS);
                System.arraycopy(mandatoryVisuals, base, bySection.get(key), offset, RealisticFireNativeSolver.VISUAL_RECORD_FLOATS);
            }
        }

        for (Long2ObjectMap.Entry<float[]> entry : bySection.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            float[] snapshot = mergeSectionVisuals(key, entry.getValue(), now);
            latestSectionVisuals.put(key, snapshot);
            latestSectionVisualsNanos.put(key, now);
            PacketDistributor.sendToPlayersInDimension(level, new ClientboundFireVisualDeltaPayload(
                    level.dimension().location().toString(),
                    ++visualSequence,
                    SectionPos.x(key),
                    SectionPos.y(key),
                    SectionPos.z(key),
                    snapshot));
        }
        pruneStaleSectionVisuals(now);
    }

    private float[] mergeSectionVisuals(long sectionKey, float[] incoming, long now) {
        int recordSize = RealisticFireNativeSolver.VISUAL_RECORD_FLOATS;
        float[] old = latestSectionVisuals.get(sectionKey);
        long[] oldNanos = latestSectionVisualRecordNanos.get(sectionKey);
        int incomingRecords = incoming.length / recordSize;
        int oldRecords = old == null ? 0 : old.length / recordSize;
        int maxRecords = Math.min(MAX_CACHED_VISUAL_RECORDS_PER_SECTION, incomingRecords + oldRecords);
        float[] merged = new float[maxRecords * recordSize];
        long[] mergedNanos = new long[maxRecords];
        Long2IntOpenHashMap offsetsByCell = new Long2IntOpenHashMap();
        offsetsByCell.defaultReturnValue(-1);
        int count = 0;

        for (int base = 0; base < incoming.length && count < maxRecords; base += recordSize) {
            long cellKey = visualRecordKey(sectionKey, incoming, base);
            if (offsetsByCell.containsKey(cellKey)) {
                int offset = offsetsByCell.get(cellKey);
                System.arraycopy(incoming, base, merged, offset, recordSize);
                mergedNanos[offset / recordSize] = now;
                continue;
            }
            int offset = count * recordSize;
            System.arraycopy(incoming, base, merged, offset, recordSize);
            mergedNanos[count] = now;
            offsetsByCell.put(cellKey, offset);
            count++;
        }

        if (old != null && oldNanos != null) {
            for (int oldIndex = 0, base = 0;
                 oldIndex < oldRecords && base + recordSize <= old.length && count < maxRecords;
                 oldIndex++, base += recordSize) {
                if (oldIndex >= oldNanos.length || now - oldNanos[oldIndex] > VISUAL_RECORD_TTL_NANOS) {
                    continue;
                }
                long cellKey = visualRecordKey(sectionKey, old, base);
                if (offsetsByCell.containsKey(cellKey)) {
                    continue;
                }
                int offset = count * recordSize;
                System.arraycopy(old, base, merged, offset, recordSize);
                mergedNanos[count] = oldNanos[oldIndex];
                offsetsByCell.put(cellKey, offset);
                count++;
            }
        }

        float[] snapshot = count == maxRecords ? merged : Arrays.copyOf(merged, count * recordSize);
        latestSectionVisualRecordNanos.put(sectionKey, count == maxRecords ? mergedNanos : Arrays.copyOf(mergedNanos, count));
        return snapshot;
    }

    public void syncVisualsToPlayer(ServerPlayer player) {
        if (!nativeReady()) {
            return;
        }
        pruneStaleSectionVisuals(System.nanoTime());
        for (Long2ObjectMap.Entry<float[]> entry : latestSectionVisuals.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            float[] snapshot = Arrays.copyOf(entry.getValue(), entry.getValue().length);
            PacketDistributor.sendToPlayer(player, new ClientboundFireVisualDeltaPayload(
                    level.dimension().location().toString(),
                    ++visualSequence,
                    SectionPos.x(key),
                    SectionPos.y(key),
                    SectionPos.z(key),
                    snapshot));
        }
    }

    private long visualSectionKey(float[] visuals, int base) {
        int sx = SectionPos.blockToSectionCoord(Mth.floor(visuals[base]));
        int sy = SectionPos.blockToSectionCoord(Mth.floor(visuals[base + 1]));
        int sz = SectionPos.blockToSectionCoord(Mth.floor(visuals[base + 2]));
        return SectionPos.asLong(sx, sy, sz);
    }

    private long visualRecordKey(long sectionKey, float[] visuals, int base) {
        int sectionX = SectionPos.sectionToBlockCoord(SectionPos.x(sectionKey));
        int sectionY = SectionPos.sectionToBlockCoord(SectionPos.y(sectionKey));
        int sectionZ = SectionPos.sectionToBlockCoord(SectionPos.z(sectionKey));
        int localX = Mth.floor((visuals[base] - sectionX) * cellsPerBlockAxis);
        int localY = Mth.floor(visuals[base + 1]) - sectionY;
        int localZ = Mth.floor((visuals[base + 2] - sectionZ) * cellsPerBlockAxis);
        return ((long) (localX & 0x7F) << 12)
                | ((long) (localY & 0x1F) << 7)
                | (long) (localZ & 0x7F);
    }

    private void sendClearVisuals(int sx, int sy, int sz) {
        long sectionKey = SectionPos.asLong(sx, sy, sz);
        latestSectionVisuals.remove(sectionKey);
        latestSectionVisualRecordNanos.remove(sectionKey);
        latestSectionVisualsNanos.remove(sectionKey);
        PacketDistributor.sendToPlayersInDimension(level, new ClientboundFireVisualDeltaPayload(
                level.dimension().location().toString(),
                ++visualSequence,
                sx,
                sy,
                sz,
                new float[0]));
    }

    private void pruneStaleSectionVisuals(long nowNanos) {
        LongIterator it = latestSectionVisuals.keySet().iterator();
        while (it.hasNext()) {
            long section = it.nextLong();
            long lastNanos = latestSectionVisualsNanos.get(section);
            if (nowNanos - lastNanos <= VISUAL_CACHE_TTL_NANOS) {
                continue;
            }
            it.remove();
            latestSectionVisualRecordNanos.remove(section);
            latestSectionVisualsNanos.remove(section);
            PacketDistributor.sendToPlayersInDimension(level, new ClientboundFireVisualDeltaPayload(
                    level.dimension().location().toString(),
                    ++visualSequence,
                    SectionPos.x(section),
                    SectionPos.y(section),
                    SectionPos.z(section),
                    new float[0]));
        }
    }

    // Flowing water (and other fluids spreading) does NOT fire a block-place event, so the solver
    // would never learn that water has reached the fire and keep burning underneath (invisible,
    // since the client suppresses the footprint over water). Each tick we check the hot cells and the
    // block just above them: any water found is marked dirty so refreshDirtyCells re-uploads it as the
    // water material (-2), which the solver then uses to extinguish and to block further spread.
    private void detectWaterOnFire() {
        if (!nativeReady() || latestSectionVisuals.isEmpty()) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LongOpenHashSet waterSections = new LongOpenHashSet();
        for (Long2ObjectMap.Entry<float[]> entry : latestSectionVisuals.long2ObjectEntrySet()) {
            float[] records = entry.getValue();
            for (int base = 0;
                 base + RealisticFireNativeSolver.VISUAL_RECORD_FLOATS <= records.length;
                 base += RealisticFireNativeSolver.VISUAL_RECORD_FLOATS) {
                int bx = Mth.floor(records[base]);
                int by = Mth.floor(records[base + 1]);
                int bz = Mth.floor(records[base + 2]);
                for (int dy = 0; dy <= 1; dy++) {
                    pos.set(bx, by + dy, bz);
                    if (level.isLoaded(pos) && level.getFluidState(pos).is(FluidTags.WATER)) {
                        killCellForWater(bx, by, bz, pos.immutable());
                        waterSections.add(SectionPos.asLong(
                                SectionPos.blockToSectionCoord(bx),
                                SectionPos.blockToSectionCoord(by),
                                SectionPos.blockToSectionCoord(bz)));
                    }
                }
            }
        }
        LongIterator it = waterSections.iterator();
        while (it.hasNext()) {
            long section = it.nextLong();
            sendClearVisuals(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
        }
    }

    private void killCellForWater(int burnX, int burnY, int burnZ, BlockPos waterPos) {
        BlockState waterState = level.getBlockState(waterPos);
        FireMaterial waterMaterial = FireMaterialRegistry.material(waterState);
        int waterMaterialId = materialIdForState(waterState, waterMaterial);
        float[] waterCellState = initialCellState(waterState, waterMaterial);
        solverLock.lock();
        try {
            RealisticFireNativeSolver.extinguish(worldHandle, burnX, burnY, burnZ, burnX, burnY, burnZ);
            RealisticFireNativeSolver.setCell(
                    worldHandle,
                    waterPos.getX(),
                    waterPos.getY(),
                    waterPos.getZ(),
                    waterMaterialId,
                    waterCellState);
        } finally {
            solverLock.unlock();
        }
        markDirty(new BlockPos(burnX, burnY, burnZ));
        markDirty(waterPos);
    }

    // Drives the vanilla light engine by placing/removing invisible fire_light blocks at
    // air positions adjacent to hot fire cells. Diffs against the previous tick's set so
    // only deltas hit the world.
    private void updateDynamicLights() {
        if (level.getGameTime() % LIGHT_UPDATE_INTERVAL_TICKS != 0L) {
            return;
        }
        if (!RealisticFireConfig.SERVER.enabled.get()) {
            cleanupAllLights();
            return;
        }

        LongOpenHashSet shouldBeLit = new LongOpenHashSet();
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        Block lightBlock = RealisticFireBlocks.FIRE_LIGHT.get();

        outer:
        for (Long2ObjectMap.Entry<float[]> entry : latestSectionVisuals.long2ObjectEntrySet()) {
            float[] records = entry.getValue();
            for (int base = 0;
                 base + RealisticFireNativeSolver.VISUAL_RECORD_FLOATS <= records.length;
                 base += RealisticFireNativeSolver.VISUAL_RECORD_FLOATS) {
                float flame = records[base + 4];
                float heat = records[base + 7];
                if (flame < LIGHT_FLAME_THRESHOLD && heat < LIGHT_HEAT_THRESHOLD) {
                    continue;
                }
                int bx = Mth.floor(records[base]);
                int by = Mth.floor(records[base + 1]);
                int bz = Mth.floor(records[base + 2]);
                long lightPosLong = pickLightPosition(mut, bx, by, bz, lightBlock);
                if (lightPosLong == Long.MIN_VALUE) {
                    continue;
                }
                shouldBeLit.add(lightPosLong);
                if (shouldBeLit.size() >= MAX_LIGHT_BLOCKS_PER_LEVEL) {
                    break outer;
                }
            }
        }

        LongIterator removeIt = currentLitPositions.iterator();
        while (removeIt.hasNext()) {
            long posLong = removeIt.nextLong();
            if (shouldBeLit.contains(posLong)) {
                continue;
            }
            mut.set(BlockPos.getX(posLong), BlockPos.getY(posLong), BlockPos.getZ(posLong));
            if (level.isLoaded(mut) && level.getBlockState(mut).is(lightBlock)) {
                level.setBlock(mut, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            removeIt.remove();
        }

        LongIterator addIt = shouldBeLit.iterator();
        while (addIt.hasNext()) {
            long posLong = addIt.nextLong();
            if (currentLitPositions.contains(posLong)) {
                continue;
            }
            mut.set(BlockPos.getX(posLong), BlockPos.getY(posLong), BlockPos.getZ(posLong));
            if (!level.isLoaded(mut)) {
                continue;
            }
            BlockState existing = level.getBlockState(mut);
            if (existing.isAir() || existing.is(lightBlock)) {
                level.setBlock(mut, lightBlock.defaultBlockState(), Block.UPDATE_ALL);
                currentLitPositions.add(posLong);
            }
        }
    }

    private long pickLightPosition(BlockPos.MutableBlockPos mut, int bx, int by, int bz, Block lightBlock) {
        // Walk up from the hot cell to the first open (air / existing light) block. The old check
        // only looked at the cell and the one directly above it, so a ground fire whose cell+1 was
        // occupied — by tall grass, a sapling, or the very plant that is burning — placed no light
        // at all and left the surroundings dark. Scanning up to the first gap fixes that; the
        // flames rise off the surface, so a light a couple of cells up still reads as their glow.
        for (int dy = 0; dy <= LIGHT_UPWARD_SCAN; dy++) {
            mut.set(bx, by + dy, bz);
            if (!level.isLoaded(mut)) {
                return Long.MIN_VALUE;
            }
            BlockState state = level.getBlockState(mut);
            if (state.isAir() || state.is(lightBlock)) {
                return BlockPos.asLong(bx, by + dy, bz);
            }
        }
        return Long.MIN_VALUE;
    }

    private void cleanupAllLights() {
        if (currentLitPositions.isEmpty()) {
            return;
        }
        Block lightBlock = RealisticFireBlocks.FIRE_LIGHT.get();
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        LongIterator it = currentLitPositions.iterator();
        while (it.hasNext()) {
            long posLong = it.nextLong();
            mut.set(BlockPos.getX(posLong), BlockPos.getY(posLong), BlockPos.getZ(posLong));
            if (level.isLoaded(mut) && level.getBlockState(mut).is(lightBlock)) {
                level.setBlock(mut, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        currentLitPositions.clear();
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
