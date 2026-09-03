package com.nstut.economybounties.minecraft;

import com.nstut.economybounties.api.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Consumes server-side inventory before progressing one exact delivery objective. */
public final class BountyDeliveryService {
    private static final long FLUID_UNITS_PER_BUCKET = 1_000L;

    private BountyDeliveryService() { }

    public static String deliver(ServerPlayer player, String source, String bountyIdText, int objectiveIndex,
                                 String typeText, String target) {
        if (source == null || (!source.equals("generated") && !source.equals("posted"))) {
            return "Invalid bounty source";
        }
        if (objectiveIndex < 0) return "Invalid objective index";
        UUID bountyId;
        try { bountyId = UUID.fromString(bountyIdText == null ? "" : bountyIdText.trim()); }
        catch (IllegalArgumentException error) { return "Invalid bounty id"; }
        if (typeText == null || target == null || target.isBlank()) return "Missing delivery target";

        NamespacedId type = NamespacedId.parse(typeText.trim());
        target = target.trim();
        if (!BuiltinObjectiveTypes.DELIVER_ITEM.equals(type) && !BuiltinObjectiveTypes.DELIVER_FLUID.equals(type)) {
            return "This objective is not a delivery objective";
        }

        long remaining = exactRemaining(player, source, bountyId, objectiveIndex, type, target);
        if (remaining < 0) return "The selected bounty objective is no longer active or does not match";
        if (remaining == 0) return "This delivery objective is already complete";

        long consumedUnits;
        if (BuiltinObjectiveTypes.DELIVER_ITEM.equals(type)) {
            consumedUnits = consumeItems(player, target, remaining);
        } else {
            consumedUnits = consumeFluidBuckets(player, target, remaining);
        }
        if (consumedUnits <= 0) {
            return BuiltinObjectiveTypes.DELIVER_FLUID.equals(type)
                    ? "No matching fluid buckets are available"
                    : "No matching items are available";
        }

        long credited = Math.min(consumedUnits, remaining);
        EconomyBountiesRuntime.recordProgress(new ProgressEvent(player.getUUID(), type, target, credited,
                ProgressScope.metadata(source, bountyId, objectiveIndex,
                        Map.of("source", "bounty_board_delivery"))));
        if (BuiltinObjectiveTypes.DELIVER_FLUID.equals(type)) {
            long buckets = consumedUnits / FLUID_UNITS_PER_BUCKET;
            return credited == consumedUnits
                    ? "Delivered " + credited + " fluid units"
                    : "Delivered " + credited + " fluid units (" + buckets + " bucket" + (buckets == 1 ? "" : "s") + " consumed)";
        }
        return "Delivered " + credited + " item" + (credited == 1 ? "" : "s");
    }

    private static long exactRemaining(ServerPlayer player, String source, UUID bountyId, int objectiveIndex,
                                       NamespacedId type, String target) {
        Instant now = Instant.now();
        if (source.equals("generated")) {
            BountyView view = EconomyBountiesRuntime.generated().get(player.getUUID(), bountyId, now).orElse(null);
            if (view == null || view.status() != BountyStatus.ACTIVE || objectiveIndex >= view.objectives().size()) return -1;
            BountyObjectiveView objective = view.objectives().get(objectiveIndex);
            if (!matches(objective.definition(), type, target)) return -1;
            return Math.max(0, objective.requiredAmount() - objective.progress());
        }

        PostedBountyView view = EconomyBountiesRuntime.posted().get(bountyId, now).orElse(null);
        if (view == null || view.status() != PostedBountyStatus.ACTIVE || !player.getUUID().equals(view.claimantId())
                || objectiveIndex >= view.objectives().size()) return -1;
        PostedBountyObjectiveView objective = view.objectives().get(objectiveIndex);
        if (!matches(objective.definition(), type, target)) return -1;
        return Math.max(0, objective.targetAmount() - objective.progress());
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
        long bucketsNeeded = ((requestedUnits - 1L) / FLUID_UNITS_PER_BUCKET) + 1L;
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
