package com.nstut.economybounties.minecraft;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BountyBoardRegistries {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(EconomyBountiesRuntime.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(EconomyBountiesRuntime.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, EconomyBountiesRuntime.MOD_ID);

    public static final DeferredBlock<Block> BOARD = BLOCKS.registerBlock("bounty_board", BountyBoardBlock::new,
            properties -> properties.mapColor(MapColor.COLOR_GREEN)
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL));
    public static final DeferredItem<BlockItem> BOARD_ITEM = ITEMS.registerSimpleBlockItem(BOARD);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = CREATIVE_TABS.register("tab", () ->
            CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup.economy_bounties"))
                    .icon(() -> new ItemStack(BOARD_ITEM.get()))
                    .displayItems((params, output) -> output.accept(BOARD_ITEM.get()))
                    .build());

    private BountyBoardRegistries() { }

    public static void init(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        CREATIVE_TABS.register(bus);
    }
}
