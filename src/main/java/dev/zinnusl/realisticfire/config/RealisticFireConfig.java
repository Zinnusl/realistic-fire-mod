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
        public final ModConfigSpec.IntValue solverSubsteps;
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
            solverSubsteps = builder.defineInRange("solverSubsteps", 4, 1, 16);
            maxActiveCellsPerLevel = builder.defineInRange("maxActiveCellsPerLevel", 262144, 4096, 4_194_304);
            maxBlockMutationsPerTick = builder.defineInRange("maxBlockMutationsPerTick", 512, 1, 8192);
            maxVisualRecordsPerTick = builder.defineInRange("maxVisualRecordsPerTick", 2048, 0, 32768);
            maxSolverMillisPerTick = builder.defineInRange("maxSolverMillisPerTick", 4.0, 0.25, 50.0);
            visualSendIntervalTicks = builder.defineInRange("visualSendIntervalTicks", 2, 1, 20);
            builder.pop();
        }
    }
}
