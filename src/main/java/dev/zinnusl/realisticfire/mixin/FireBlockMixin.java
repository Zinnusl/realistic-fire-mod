package dev.zinnusl.realisticfire.mixin;

import dev.zinnusl.realisticfire.config.RealisticFireConfig;
import dev.zinnusl.realisticfire.nativebridge.RealisticFireNativeSolver;
import dev.zinnusl.realisticfire.simulation.FireSimulationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private void realisticfire$replaceRandomSpread(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (!shouldReplaceVanillaFire()) {
            return;
        }
        FireSimulationManager.forLevel(level).igniteFromPlacedFire(pos, 1200.0f);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        ci.cancel();
    }

    @Inject(method = "onPlace", at = @At("TAIL"), remap = false)
    private void realisticfire$seedOnPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel) || !shouldSuppressPlacedFire()) {
            return;
        }
        BlockState cleanupState = FireSimulationManager.forLevel(serverLevel).igniteFromPlacedFire(pos, oldState, 1200.0f);
        serverLevel.setBlock(pos, cleanupState, Block.UPDATE_ALL);
    }

    // Random fire-tick spread replacement still requires the native solver — if it is down we
    // leave vanilla spread alone rather than make fire vanish.
    private boolean shouldReplaceVanillaFire() {
        return shouldSuppressPlacedFire() && RealisticFireNativeSolver.available();
    }

    // Suppressing the just-PLACED vanilla fire block (so no vanilla flame renders) must NOT depend
    // on native readiness: igniteFromPlacedFire seeds the sim and the re-entrant AIR removal makes
    // the outer setBlock skip its client notify even before the solver handle is ready.
    private boolean shouldSuppressPlacedFire() {
        return RealisticFireConfig.SERVER.enabled.get()
                && RealisticFireConfig.SERVER.replaceVanillaFireSpread.get();
    }
}
