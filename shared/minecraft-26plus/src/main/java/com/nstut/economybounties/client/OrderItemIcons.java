package com.nstut.economybounties.client;

import com.nstut.openui.api.Ui;
import com.nstut.openui.controls.IconWidget;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;

/** Renders native inventory sprites for vanilla and modded item IDs. */
final class OrderItemIcons {
    private static final Map<String, ItemStack> ICONS = new HashMap<>();

    private OrderItemIcons() { }

    static IconWidget icon(String itemId) {
        return Ui.icon(ICONS.computeIfAbsent(itemId == null ? "" : itemId, OrderItemIcons::resolve)).size(18);
    }

    private static ItemStack resolve(String id) {
        if (!id.isBlank()) {
            // BuiltInRegistries.ITEM and getKey exist on all supported MC
            // families, unlike the renamed ResourceLocation parse APIs.
            for (var item : BuiltInRegistries.ITEM) {
                if (id.equals(BuiltInRegistries.ITEM.getKey(item).toString())) {
                    return new ItemStack(item);
                }
            }
        }
        return new ItemStack(Items.BARRIER); // visibly invalid datapack ID
    }
}
