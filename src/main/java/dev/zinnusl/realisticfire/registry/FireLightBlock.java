package dev.zinnusl.realisticfire.registry;

import dev.zinnusl.realisticfire.simulation.FireSimulationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Invisible block whose only job is to drive the vanilla light engine — placed by
 * FireSimulationManager above hot fire cells. Self-destructs on random tick if the
 * simulation no longer wants light here (handles stray blocks left after chunk reloads).
 */
public final class FireLightBlock extends Block {
    public FireLightBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!FireSimulationManager.isExpectedLightAt(level, pos)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), UPDATE_ALL);
        }
    }
}
