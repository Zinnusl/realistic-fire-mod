package dev.zinnusl.realisticfire.events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.Event;

public abstract class RealisticFireEvent extends Event {
    private final ServerLevel level;
    private final BlockPos pos;

    protected RealisticFireEvent(ServerLevel level, BlockPos pos) {
        this.level = level;
        this.pos = pos.immutable();
    }

    public ServerLevel level() {
        return level;
    }

    public BlockPos pos() {
        return pos;
    }

    public static class Ignite extends RealisticFireEvent {
        private final double temperatureK;

        public Ignite(ServerLevel level, BlockPos pos, double temperatureK) {
            super(level, pos);
            this.temperatureK = temperatureK;
        }

        public double temperatureK() {
            return temperatureK;
        }
    }

    public static class BurnBlock extends RealisticFireEvent {
        private final BlockState oldState;
        private final BlockState newState;

        public BurnBlock(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
            super(level, pos);
            this.oldState = oldState;
            this.newState = newState;
        }

        public BlockState oldState() {
            return oldState;
        }

        public BlockState newState() {
            return newState;
        }
    }

    public static class Extinguish extends RealisticFireEvent {
        public Extinguish(ServerLevel level, BlockPos pos) {
            super(level, pos);
        }
    }
}
