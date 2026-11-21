package com.nstut.economybounties.minecraft;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

public final class BountyBoardRegistries {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(EconomyBountiesRuntime.MOD_ID, Registries.BLOCK);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(EconomyBountiesRuntime.MOD_ID, Registries.ITEM);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(EconomyBountiesRuntime.MOD_ID, Registries.CREATIVE_MODE_TAB);

    public static final RegistrySupplier<Block> BOARD = BLOCKS.register("bounty_board", BountyBoardBlock::new);
    public static final RegistrySupplier<Item> BOARD_ITEM = ITEMS.register("bounty_board",
            () -> new BlockItem(BOARD.get(), new Item.Properties()));
    public static final RegistrySupplier<CreativeModeTab> TAB = CREATIVE_TABS.register("tab", () ->
            CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup.economy_bounties"))
                    .icon(() -> new ItemStack(BOARD_ITEM.get()))
                    .displayItems((params, output) -> output.accept(BOARD_ITEM.get()))
                    .build());

    private BountyBoardRegistries() { }

    public static void init() {
        BLOCKS.register();
        ITEMS.register();
        CREATIVE_TABS.register();
    }
}
