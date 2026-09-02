package com.nstut.economybounties.minecraft;

import com.nstut.economybounties.api.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.Instant;

/** Consumes server-side inventory before emitting delivery progress. */
public final class BountyDeliveryService {
    private static final long FLUID_UNITS_PER_BUCKET = 1_000L;

    private BountyDeliveryService() { }

    public static String deliver(ServerPlayer player, String typeText, String target) {
        if (typeText == null || target == null || target.isBlank()) return "Missing delivery target";
        NamespacedId type = NamespacedId.parse(typeText.trim());
        target = target.trim();
        long remaining = maximumRemaining(player, type, target);
        if (remaining <= 0) return "No active matching delivery objective needs progress";

        long delivered;
        if (BuiltinObjectiveTypes.DELIVER_ITEM.equals(type)) {
            delivered = consumeItems(player, target, remaining);
        } else if (BuiltinObjectiveTypes.DELIVER_FLUID.equals(type)) {
            delivered = consumeFluidBuckets(player, target, remaining);
        } else {
            return "This objective is not a delivery objective";
        }
        if (delivered <= 0) {
            return BuiltinObjectiveTypes.DELIVER_FLUID.equals(type)
                    ? "No matching fluid buckets are available"
                    : "No matching items are available";
        }
        EconomyBountiesRuntime.recordProgress(new ProgressEvent(player.getUUID(), type, target, delivered,
                java.util.Map.of("source", "bounty_board_delivery")));
        return BuiltinObjectiveTypes.DELIVER_FLUID.equals(type)
                ? "Delivered " + delivered + " fluid units"
                : "Delivered " + delivered + " item" + (delivered == 1 ? "" : "s");
    }

    static long maximumRemaining(ServerPlayer player, NamespacedId type, String target) {
        long maximum = 0;
        Instant now = Instant.now();
        for (BountyView bounty : EconomyBountiesRuntime.generatedFor(player.getUUID(), now)) {
            if (bounty.status() != BountyStatus.ACTIVE) continue;
            for (BountyObjectiveView objective : bounty.objectives()) {
                if (matches(objective.definition(), type, target)) {
                    maximum = Math.max(maximum, objective.requiredAmount() - objective.progress());
                }
            }
        }
        for (PostedBountyView bounty : EconomyBountiesRuntime.postedFor(player.getUUID(), now)) {
            if (bounty.status() != PostedBountyStatus.ACTIVE || !player.getUUID().equals(bounty.claimantId())) continue;
            for (PostedBountyObjectiveView objective : bounty.objectives()) {
                if (matches(objective.definition(), type, target)) {
                    maximum = Math.max(maximum, objective.targetAmount() - objective.progress());
                }
            }
        }
        return maximum;
    }

    private static boolean matches(ObjectiveDefinition definition, NamespacedId type, String target) {
        return definition.type().equals(type) && definition.target().equals(target);
    }

    private static long consumeItems(ServerPlayer player, String target, long requested) {
        long remaining = requested;
        long consumed = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (!target.equals(itemId)) continue;
            int take = (int) Math.min((long) stack.getCount(), remaining);
            stack.shrink(take);
            remaining -= take;
            consumed += take;
        }
        if (consumed > 0) inventory.setChanged();
        return consumed;
    }

    private static long consumeFluidBuckets(ServerPlayer player, String fluidTarget, long requestedUnits) {
        int separator = fluidTarget.indexOf(':');
        if (separator <= 0 || separator == fluidTarget.length() - 1) return 0;
        String bucketId = fluidTarget.substring(0, separator + 1) + fluidTarget.substring(separator + 1) + "_bucket";
        long bucketsNeeded = Math.max(1L, (requestedUnits + FLUID_UNITS_PER_BUCKET - 1L) / FLUID_UNITS_PER_BUCKET);
        long bucketsConsumed = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && bucketsConsumed < bucketsNeeded; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (!bucketId.equals(itemId)) continue;
            int take = (int) Math.min((long) stack.getCount(), bucketsNeeded - bucketsConsumed);
            stack.shrink(take);
            bucketsConsumed += take;
        }
        if (bucketsConsumed <= 0) return 0;
        for (long i = 0; i < bucketsConsumed; i++) {
            ItemStack emptyBucket = new ItemStack(Items.BUCKET);
            if (!inventory.add(emptyBucket)) player.drop(emptyBucket, false);
        }
        inventory.setChanged();
        return bucketsConsumed * FLUID_UNITS_PER_BUCKET;
    }
}
