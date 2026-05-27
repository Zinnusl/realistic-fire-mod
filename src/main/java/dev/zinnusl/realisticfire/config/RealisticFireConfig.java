package dev.zinnusl.realisticfire.config;

import dev.zinnusl.realisticfire.RealisticFire;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public final class RealisticFireConfig {
    public static final Server SERVER;
    public static final ModConfigSpec SERVER_SPEC;

    static {
        Pair<Server, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Server::new);
        SERVER = pair.getLeft();
        SERVER_SPEC = pair.getRight();
    }

    private RealisticFireConfig() {
    }

    public static void register(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, SERVER_SPEC, RealisticFire.MOD_ID + "-server.toml");
    }

    public static final class Server {
        public final ModConfigSpec.BooleanValue enabled;
        public final ModConfigSpec.BooleanValue replaceVanillaFireSpread;
        public final ModConfigSpec.BooleanValue requireNative;
        public final ModConfigSpec.BooleanValue useSolverThread;
        public final ModConfigSpec.IntValue solverSubsteps;
        public final ModConfigSpec.IntValue cellsPerBlockAxis;
        public final ModConfigSpec.IntValue maxActiveCellsPerLevel;
        public final ModConfigSpec.IntValue maxBlockMutationsPerTick;
        public final ModConfigSpec.IntValue maxVisualRecordsPerTick;
        public final ModConfigSpec.DoubleValue maxSolverMillisPerTick;
        public final ModConfigSpec.IntValue visualSendIntervalTicks;

        private Server(ModConfigSpec.Builder builder) {
            builder.push("simulation");
            enabled = builder.define("enabled", true);
            replaceVanillaFireSpread = builder.define("replaceVanillaFireSpread", true);
            requireNative = builder.comment("If true, realistic spread pauses when the native solver is unavailable.")
                    .define("requireNative", true);
            useSolverThread = builder
                    .comment(
                            "Run the native fire solver step on a background thread so its cost overlaps",
                            "the rest of the server tick instead of extending it. Block changes are applied",
                            "one tick later. Most useful for large S>1 fires. Off by default; if you see any",
                            "instability with it on, turn it back off.")
                    .define("useSolverThread", false);
            solverSubsteps = builder.defineInRange("solverSubsteps", 4, 1, 16);
            cellsPerBlockAxis = builder
                    .comment(
                            "Sub-block fire resolution: simulation cells per block per axis (horizontal X/Z).",
                            "1 = one cell per block (classic). Higher gives a smoother, rounder flame front and",
                            "finer flames, at ~N^2 the simulation cost and memory (6 = 36x). Block outcomes",
                            "(ash/char) still happen per whole block, only once it is fully consumed.",
                            "Changing this requires a full restart and discards in-progress fire state.")
                    .defineInRange("cellsPerBlockAxis", 6, 1, 6);
            maxActiveCellsPerLevel = builder
                    .comment("Active-cell budget per level, in BLOCKS. The solver multiplies it by cellsPerBlockAxis^2.")
                    .defineInRange("maxActiveCellsPerLevel", 262144, 4096, 4_194_304);
            maxBlockMutationsPerTick = builder.defineInRange("maxBlockMutationsPerTick", 512, 1, 8192);
            maxVisualRecordsPerTick = builder.defineInRange("maxVisualRecordsPerTick", 8192, 0, 32768);
            maxSolverMillisPerTick = builder.defineInRange("maxSolverMillisPerTick", 4.0, 0.25, 50.0);
            visualSendIntervalTicks = builder.defineInRange("visualSendIntervalTicks", 2, 1, 20);
            builder.pop();
        }
    }
}
