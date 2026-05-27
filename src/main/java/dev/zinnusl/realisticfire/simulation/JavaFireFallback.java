package dev.zinnusl.realisticfire.simulation;

import it.unimi.dsi.fastutil.longs.Long2FloatMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

final class JavaFireFallback {
    private final Long2FloatMap hotCells = new Long2FloatOpenHashMap();

    JavaFireFallback() {
        hotCells.defaultReturnValue(293.15f);
    }

    void ignite(BlockPos pos, float temperatureK, int radius) {
        BlockPos.betweenClosed(pos.offset(-radius, -radius, -radius), pos.offset(radius, radius, radius))
                .forEach(cell -> hotCells.put(cell.asLong(), temperatureK));
    }

    void tick(ServerLevel level) {
        hotCells.long2FloatEntrySet().removeIf(entry -> {
            float temperature = entry.getFloatValue() - 20.0f;
            if (temperature < 350.0f) {
                return true;
            }
            entry.setValue(temperature);
            BlockPos pos = BlockPos.of(entry.getLongKey());
            if (temperature > 850.0f && level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.FIRE)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
            return false;
        });
    }

    int extinguish(AABB bounds) {
        int before = hotCells.size();
        hotCells.long2FloatEntrySet().removeIf(entry -> bounds.contains(BlockPos.of(entry.getLongKey()).getCenter()));
        return before - hotCells.size();
    }

    float query(BlockPos pos) {
        return hotCells.get(pos.asLong());
    }
}
