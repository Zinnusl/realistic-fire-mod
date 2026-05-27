package dev.zinnusl.realisticfire.registry;

import dev.zinnusl.realisticfire.RealisticFire;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class RealisticFireBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(RealisticFire.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RealisticFire.MOD_ID);

    public static final DeferredBlock<Block> CHARRED_BLOCK = BLOCKS.register(
            "charred_block",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.0f)
                    .sound(SoundType.DEEPSLATE)));

    public static final DeferredBlock<Block> CHARRED_TURF = BLOCKS.register(
            "charred_turf",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(0.6f)
                    .sound(SoundType.GRASS)));

    public static final DeferredBlock<Block> ASH = BLOCKS.register(
            "ash",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(0.2f)
                    .sound(SoundType.SAND)));

    public static final DeferredItem<BlockItem> CHARRED_BLOCK_ITEM = ITEMS.register(
            "charred_block",
            () -> new BlockItem(CHARRED_BLOCK.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> CHARRED_TURF_ITEM = ITEMS.register(
            "charred_turf",
            () -> new BlockItem(CHARRED_TURF.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> ASH_ITEM = ITEMS.register(
            "ash",
            () -> new BlockItem(ASH.get(), new Item.Properties()));

    private RealisticFireBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
    }
}
