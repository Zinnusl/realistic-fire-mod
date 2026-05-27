package dev.zinnusl.realisticfire.nativebridge;

import dev.zinnusl.realisticfire.RealisticFire;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class RealisticFireNativeSolver {
    public static final int ABI_VERSION = 4;
    public static final int MUTATION_RECORD_INTS = 6;
    public static final int VISUAL_RECORD_FLOATS = 8;
    public static final int ACTION_SET_CHAR = 1;
    public static final int ACTION_SET_ASH = 2;
    public static final int ACTION_SET_AIR = 3;
    public static final int ACTION_DAMAGE_ENTITY_AREA = 4;
    public static final int ACTION_EXTINGUISH = 5;

    private static final String LIB_NAME = "realisticfire_solver";
    private static final String NATIVE_DIR = ".realisticfire/natives";
    private static volatile boolean available = loadLibrary();

    private RealisticFireNativeSolver() {
    }

    public static boolean available() {
        return available;
    }

    public static long createWorld(int dimensionId, int minBuildHeight, int maxBuildHeight, int seed) {
        if (!available) {
            return 0L;
        }
        try {
            return createWorldNative(dimensionId, minBuildHeight, maxBuildHeight, seed);
        } catch (LinkageError | RuntimeException exception) {
            disable("createWorld", exception);
            return 0L;
        }
    }

    public static void destroyWorld(long worldHandle) {
        if (!available || worldHandle == 0L) {
            return;
        }
        try {
            destroyWorldNative(worldHandle);
        } catch (LinkageError | RuntimeException exception) {
            disable("destroyWorld", exception);
        }
    }

    public static void setTile(long worldHandle, int sectionX, int sectionY, int sectionZ, int[] materialIds, float[] initialState) {
        if (!available || worldHandle == 0L) {
            return;
        }
        try {
            setTileNative(worldHandle, sectionX, sectionY, sectionZ, materialIds, initialState);
        } catch (LinkageError | RuntimeException exception) {
            disable("setTile", exception);
        }
    }

    public static void setMaterial(long worldHandle, int id, float fuel, boolean hasCharStage, boolean hasAshStage, float ignitionTemperatureK, float burnRate, float heatRelease, float smokeYield, float insulation) {
        if (!available || worldHandle == 0L || id <= 0) {
            return;
        }
        try {
            setMaterialNative(worldHandle, id, fuel, hasCharStage, hasAshStage, ignitionTemperatureK, burnRate, heatRelease, smokeYield, insulation);
        } catch (LinkageError | RuntimeException exception) {
            disable("setMaterial", exception);
        }
    }

    public static void setCell(long worldHandle, int x, int y, int z, int materialId, float[] initialState) {
        if (!available || worldHandle == 0L) {
            return;
        }
        try {
            setCellNative(worldHandle, x, y, z, materialId, initialState);
        } catch (LinkageError | RuntimeException exception) {
            disable("setCell", exception);
        }
    }

    public static void removeTile(long worldHandle, int sectionX, int sectionY, int sectionZ) {
        if (!available || worldHandle == 0L) {
            return;
        }
        try {
            removeTileNative(worldHandle, sectionX, sectionY, sectionZ);
        } catch (LinkageError | RuntimeException exception) {
            disable("removeTile", exception);
        }
    }

    public static void setTileLoaded(long worldHandle, int sectionX, int sectionY, int sectionZ, boolean loaded) {
        if (!available || worldHandle == 0L) {
            return;
        }
        try {
            setTileLoadedNative(worldHandle, sectionX, sectionY, sectionZ, loaded ? 1 : 0);
        } catch (LinkageError | RuntimeException exception) {
            disable("setTileLoaded", exception);
        }
    }

    public static void ignite(long worldHandle, int x, int y, int z, float temperatureK, int radius) {
        if (!available || worldHandle == 0L) {
            return;
        }
        try {
            igniteNative(worldHandle, x, y, z, temperatureK, radius);
        } catch (LinkageError | RuntimeException exception) {
            disable("ignite", exception);
        }
    }

    public static int extinguish(long worldHandle, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (!available || worldHandle == 0L) {
            return 0;
        }
        try {
            return extinguishNative(worldHandle, minX, minY, minZ, maxX, maxY, maxZ);
        } catch (LinkageError | RuntimeException exception) {
            disable("extinguish", exception);
            return 0;
        }
    }

    public static StepResult step(long worldHandle, float dtSeconds, int maxCells, int maxMutations, int maxVisuals) {
        int[] mutations = new int[Math.max(0, maxMutations) * MUTATION_RECORD_INTS];
        float[] visuals = new float[Math.max(0, maxVisuals) * VISUAL_RECORD_FLOATS];
        if (!available || worldHandle == 0L) {
            return StepResult.empty(mutations, visuals);
        }
        try {
            int packed = stepNative(worldHandle, dtSeconds, maxCells, maxMutations, maxVisuals, mutations, visuals);
            int mutationCount = packed & 0xffff;
            int visualCount = (packed >>> 16) & 0xffff;
            if (mutationCount > maxMutations || visualCount > maxVisuals) {
                throw new IllegalStateException("Native solver returned counts outside caller buffers");
            }
            return new StepResult(mutationCount, visualCount, mutations, visuals);
        } catch (LinkageError | RuntimeException exception) {
            disable("step", exception);
            return StepResult.empty(mutations, visuals);
        }
    }

    public static float queryTemperature(long worldHandle, int x, int y, int z) {
        if (!available || worldHandle == 0L) {
            return 293.15f;
        }
        try {
            return queryTemperatureNative(worldHandle, x, y, z);
        } catch (LinkageError | RuntimeException exception) {
            disable("queryTemperature", exception);
            return 293.15f;
        }
    }

    public static byte[] save(long worldHandle) {
        if (!available || worldHandle == 0L) {
            return new byte[0];
        }
        try {
            return saveNative(worldHandle);
        } catch (LinkageError | RuntimeException exception) {
            disable("save", exception);
            return new byte[0];
        }
    }

    public static boolean load(long worldHandle, byte[] snapshot) {
        if (!available || worldHandle == 0L || snapshot == null || snapshot.length == 0) {
            return false;
        }
        try {
            return loadNative(worldHandle, snapshot);
        } catch (LinkageError | RuntimeException exception) {
            disable("load", exception);
            return false;
        }
    }

    private static void disable(String operation, Throwable throwable) {
        available = false;
        RealisticFire.LOGGER.error("Realistic Fire native solver failed during {}; disabling native solver.", operation, throwable);
    }

    private static String nativeName() {
        String archProperty = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String arch = archProperty.equals("arm") || archProperty.startsWith("aarch64") ? "aarch64" : "x86_64";
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return LIB_NAME + "_" + arch + "_windows.dll";
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return LIB_NAME + "_" + arch + "_macos.dylib";
        }
        return LIB_NAME + "_" + arch + "_linux.so";
    }

    private static boolean loadLibrary() {
        String nativeName = nativeName();
        String resource = "/natives/" + LIB_NAME + "/" + nativeName;
        try (InputStream stream = RealisticFireNativeSolver.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new FileNotFoundException(resource);
            }
            Path dir = Paths.get(NATIVE_DIR);
            Files.createDirectories(dir);
            Path extracted = dir.resolve(nativeName);
            Files.copy(stream, extracted, StandardCopyOption.REPLACE_EXISTING);
            System.load(extracted.toAbsolutePath().toString());
            int abiVersion = abiVersionNative();
            if (abiVersion != ABI_VERSION) {
                RealisticFire.LOGGER.error("Realistic Fire native solver ABI {} does not match expected {}; disabling native solver.", abiVersion, ABI_VERSION);
                return false;
            }
            RealisticFire.LOGGER.info("Loaded Realistic Fire native solver {}", nativeName);
            return true;
        } catch (Throwable throwable) {
            RealisticFire.LOGGER.warn("Realistic Fire native solver {} is unavailable.", nativeName, throwable);
            return false;
        }
    }

    public record StepResult(int mutationCount, int visualCount, int[] mutations, float[] visuals) {
        public static StepResult empty(int[] mutations, float[] visuals) {
            return new StepResult(0, 0, mutations, visuals);
        }
    }

    private static native int abiVersionNative();

    private static native long createWorldNative(int dimensionId, int minBuildHeight, int maxBuildHeight, int seed);

    private static native void destroyWorldNative(long worldHandle);

    private static native void setTileNative(long worldHandle, int sectionX, int sectionY, int sectionZ, int[] materialIds, float[] initialState);

    private static native void setMaterialNative(long worldHandle, int id, float fuel, boolean hasCharStage, boolean hasAshStage, float ignitionTemperatureK, float burnRate, float heatRelease, float smokeYield, float insulation);

    private static native void setCellNative(long worldHandle, int x, int y, int z, int materialId, float[] initialState);

    private static native void removeTileNative(long worldHandle, int sectionX, int sectionY, int sectionZ);

    private static native void setTileLoadedNative(long worldHandle, int sectionX, int sectionY, int sectionZ, int loaded);

    private static native void igniteNative(long worldHandle, int x, int y, int z, float temperatureK, int radius);

    private static native int extinguishNative(long worldHandle, int minX, int minY, int minZ, int maxX, int maxY, int maxZ);

    private static native int stepNative(long worldHandle, float dtSeconds, int maxCells, int maxMutations, int maxVisuals, int[] outMutations, float[] outVisuals);

    private static native float queryTemperatureNative(long worldHandle, int x, int y, int z);

    private static native byte[] saveNative(long worldHandle);

    private static native boolean loadNative(long worldHandle, byte[] snapshot);
}
