package dev.zinnusl.realisticfire;

import dev.zinnusl.realisticfire.command.RealisticFireCommands;
import dev.zinnusl.realisticfire.config.RealisticFireConfig;
import dev.zinnusl.realisticfire.network.RealisticFireNetwork;
import dev.zinnusl.realisticfire.registry.RealisticFireBlocks;
import dev.zinnusl.realisticfire.simulation.FireSimulationManager;
import dev.zinnusl.realisticfire.simulation.material.FireMaterialReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(RealisticFire.MOD_ID)
public final class RealisticFire {
    public static final String MOD_ID = "realisticfire";
    public static final Logger LOGGER = LoggerFactory.getLogger("Realistic Fire");

    public RealisticFire(IEventBus modEventBus, ModContainer modContainer) {
        RealisticFireConfig.register(modContainer);
        RealisticFireBlocks.register(modEventBus);
        RealisticFireNetwork.register(modEventBus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registerClientHooks();
        }

        NeoForge.EVENT_BUS.addListener(RealisticFire::registerReloadListeners);
        NeoForge.EVENT_BUS.addListener(RealisticFire::registerCommands);
        NeoForge.EVENT_BUS.addListener(RealisticFire::onLevelTick);
        NeoForge.EVENT_BUS.addListener(RealisticFire::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(RealisticFire::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(RealisticFire::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(RealisticFire::onBlockPlace);
        NeoForge.EVENT_BUS.addListener(RealisticFire::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(RealisticFire::onFluidPlaceBlock);

        LOGGER.info("Realistic Fire initialized");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private static void registerClientHooks() {
        try {
            Class.forName("dev.zinnusl.realisticfire.client.ClientFireParticles")
                    .getMethod("register")
                    .invoke(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to register Realistic Fire client hooks", exception);
        }
    }

    private static void registerReloadListeners(AddReloadListenerEvent event) {
        event.addListener(FireMaterialReloadListener.INSTANCE);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        RealisticFireCommands.register(event.getDispatcher());
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            FireSimulationManager.forLevel(level).tick();
        }
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            FireSimulationManager.forLevel(level).uploadChunk(event.getChunk());
        }
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            FireSimulationManager.forLevel(level).removeChunk(event.getChunk().getPos());
        }
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            FireSimulationManager.unload(level);
        }
    }

    private static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            FireSimulationManager.forLevel(level).markDirty(event.getPos());
        }
    }

    private static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            FireSimulationManager.forLevel(level).markDirty(event.getPos());
        }
    }

    private static void onFluidPlaceBlock(BlockEvent.FluidPlaceBlockEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            FireSimulationManager.forLevel(level).markDirty(event.getPos());
        }
    }
}
