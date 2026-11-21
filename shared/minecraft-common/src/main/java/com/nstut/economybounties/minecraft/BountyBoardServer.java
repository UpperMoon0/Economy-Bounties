package com.nstut.economybounties.minecraft;

import com.nstut.economybounties.api.*;
import com.nstut.economybounties.board.BoardRequest;
import com.nstut.economybounties.board.BoardActionPolicy;
import com.nstut.economybounties.board.BoardSnapshot;
import com.nstut.economybounties.network.BountyNetwork;
import com.nstut.economybounties.orders.OrderBoardService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

/** Resolves every board action against server-owned state; clients only send intent. */
public final class BountyBoardServer {
    private static final int MAX_CREATE_OBJECTIVES = 16;
    private static final int MAX_AUDIENCE_ENTRIES = 128;
    private static final int MAX_OUTSTANDING_POSTED_BY_PLAYER = 64;
    private static final long MAX_LIFETIME_MINUTES = 60L * 24L * 30L;

    private BountyBoardServer() { }

    public static void open(ServerPlayer player) { send(player, ""); }

    public static void handle(ServerPlayer player, BoardRequest request) {
        if (player == null || request == null) return;
        if (!EconomyBountiesRuntime.ready()) {
            send(player, "Economy Bounties is still starting");
            return;
        }
        // Legacy clients can still encode ROLL. Do not trust the absence
        // of its UI button: deny the request at the authoritative entry point.
        if (!BoardActionPolicy.allowFromPlayer(request.action())) {
            send(player, BoardActionPolicy.manualRollNotice());
            return;
        }
        String notice;
        try {
            notice = switch (request.action()) {
                case REFRESH -> "";
                case ACCEPT_ORDER -> acceptOrder(player, request.poolId(), request.objectiveIndex(), request.bountyId());
                case FULFILL_ORDER -> fulfillOrder(player, request.poolId(), request.objectiveIndex(), request.bountyId());
                case DISCARD_ORDER -> discardOrder(player, request.poolId(), request.objectiveIndex(), request.bountyId());
                case ROLL -> BoardActionPolicy.manualRollNotice();
                case ACCEPT_GENERATED -> generatedAccept(player, request.bountyId());
                case CANCEL_GENERATED -> generatedCancel(player, request.bountyId());
                case CLAIM_GENERATED -> generatedClaim(player, request.bountyId());
                case ACCEPT_POSTED -> posted(player, request.bountyId(), PostedAction.ACCEPT);
                case CANCEL_POSTED -> posted(player, request.bountyId(), PostedAction.CANCEL);
                case CLAIM_POSTED -> posted(player, request.bountyId(), PostedAction.CLAIM);
                case CREATE_POSTED -> createPosted(player, request.create());
                case DELIVER -> BountyDeliveryService.deliver(player, request.bountySource(), request.bountyId(),
                        request.objectiveIndex(), request.objectiveType(), request.objectiveTarget());
            };
        } catch (IllegalArgumentException error) {
            notice = error.getMessage() == null ? "Invalid bounty request" : error.getMessage();
        } catch (RuntimeException error) {
            notice = "Bounty action failed safely (" + error.getClass().getSimpleName() + ')';
        }
        send(player, notice);
    }

    public static void send(ServerPlayer player, String notice) {
        BountyNetwork.sendSnapshot(player, snapshot(player, notice));
    }

    public static BoardSnapshot snapshot(ServerPlayer player, String notice) {
        Instant now = Instant.now();
        // Legacy snapshot field remains for wire compatibility; there are no
        // player-rollable pools. Auto-generated offers live in orders instead.
        List<BoardSnapshot.PoolEntry> pools = List.of();
        List<BoardSnapshot.BountyEntry> generated = EconomyBountiesRuntime.generatedFor(player.getUUID(), now).stream()
                .sorted(Comparator.comparingInt((BountyView view) -> terminalGenerated(view.status()) ? 1 : 0)
                        .thenComparing(BountyView::offeredAt, Comparator.reverseOrder()))
                .limit(40)
                .map(BountyBoardServer::generatedEntry)
                .sorted(Comparator.comparing(BoardSnapshot.BountyEntry::expiresAtEpochSecond))
                .toList();
        List<BoardSnapshot.BountyEntry> posted = EconomyBountiesRuntime.postedFor(player.getUUID(), now).stream()
                .sorted(Comparator.comparingInt((PostedBountyView view) -> terminalPosted(view.status()) ? 1 : 0)
                        .thenComparing(PostedBountyView::createdAt, Comparator.reverseOrder()))
                .limit(24)
                .map(view -> postedEntry(player.getUUID(), view))
                .sorted(Comparator.comparing(BoardSnapshot.BountyEntry::expiresAtEpochSecond))
                .toList();
        int playerLevel = MinecraftAudienceProvider.experienceLevel(EconomyBountiesRuntime.server(), player.getUUID());
        List<BoardSnapshot.OrderEntry> orders = EconomyBountiesRuntime.ordersFor(player.getUUID(), playerLevel, now)
                .stream().map(card -> orderEntry(player, card)).toList();
        return new BoardSnapshot(pools, generated, posted, notice, orders);
    }

    /** Keep active work first while bounding board packets and scroll virtualization. */
    private static boolean terminalGenerated(BountyStatus status) {
        return status == BountyStatus.CLAIMED || status == BountyStatus.EXPIRED || status == BountyStatus.CANCELLED;
    }

    private static boolean terminalPosted(PostedBountyStatus status) {
        return status == PostedBountyStatus.CLAIMED || status == PostedBountyStatus.EXPIRED
                || status == PostedBountyStatus.CANCELLED;
    }

    private static BoardSnapshot.OrderEntry orderEntry(ServerPlayer player, OrderBoardService.Card card) {
        return new BoardSnapshot.OrderEntry(card.board(), card.scope(), card.slot(),
                card.offerId(), card.title(), card.item(), card.quantity(), card.reward(),
                card.rarity(), card.timer(), card.status(), card.deadlineSeconds(), card.expiresAt(), card.restocksAt(),
                card.canAccept(), card.canDiscard(),
                card.canAccept() ? inventoryCount(player, card.item()) : 0L);
    }

    private static long inventoryCount(ServerPlayer player, String itemId) {
        if (itemId == null || itemId.isBlank()) return 0;
        long count = 0;
        var inventory = player.getInventory();
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            ItemStack stack = inventory.getItem(index);
            if (!stack.isEmpty() && itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()))
                count += stack.getCount();
        }
        return count;
    }

    /**
     * Optional one-click delivery for orders already stocked in inventory.
     * Every state mutation still uses the canonical accept -> scoped consume
     * -> objective completion -> idempotent Economy payout chain.
     */
    private static String fulfillOrder(ServerPlayer player, String board, int slot, String offerId) {
        NamespacedId id = NamespacedId.parse(required(board, "order board"));
        UUID offer = uuid(offerId);
        Instant now = Instant.now();
        int level = MinecraftAudienceProvider.experienceLevel(EconomyBountiesRuntime.server(), player.getUUID());
        OrderBoardService.Card card = EconomyBountiesRuntime.orders().list(player.getUUID(), level, now)
                .stream()
                .filter(entry -> entry.board().equals(id.toString()) && entry.slot() == slot
                        && entry.offerId().equals(offerId) && entry.canAccept())
                .findFirst().orElse(null);
        if (card == null) return "That order is no longer available. Refresh the board.";
        if (inventoryCount(player, card.item()) < card.quantity())
            return "You need " + card.quantity() + " " + card.item() + " in your inventory to deliver now";

        String accepted = EconomyBountiesRuntime.orders().accept(player.getUUID(), level, id, slot, offer, now);
        UUID contract = OrderBoardService.issuedBountyId(offer, player.getUUID());
        BountyView issued = EconomyBountiesRuntime.generated().get(player.getUUID(), contract, Instant.now())
                .orElse(null);
        if (issued == null || issued.status() != BountyStatus.ACTIVE) return accepted;

        String delivery = BountyDeliveryService.deliver(player, "generated", contract.toString(), 0,
                BuiltinObjectiveTypes.DELIVER_ITEM.toString(), card.item());
        BountyView after = EconomyBountiesRuntime.generated().get(player.getUUID(), contract, Instant.now())
                .orElse(null);
        if (after == null || after.status() != BountyStatus.COMPLETED)
            return delivery + ". You can finish this order under My Jobs.";
        BountyService.ClaimResult claimed = EconomyBountiesRuntime.generated().claim(
                player.getUUID(), contract, Instant.now());
        return claimed.status() == BountyService.ClaimResult.Status.PAID
                || claimed.status() == BountyService.ClaimResult.Status.ALREADY_CLAIMED
                ? "Delivery complete. $" + card.reward() + " credited to your Economy account."
                : "Delivery recorded. Reward is pending; claim it from My Jobs. " + claimed.message();
    }

    private static String acceptOrder(ServerPlayer player, String board, int slot, String offerId) {
        return EconomyBountiesRuntime.orders().accept(player.getUUID(),
                MinecraftAudienceProvider.experienceLevel(EconomyBountiesRuntime.server(), player.getUUID()),
                NamespacedId.parse(required(board, "order board")), slot,
                uuid(offerId), Instant.now());
    }

    private static String discardOrder(ServerPlayer player, String board, int slot, String offerId) {
        return EconomyBountiesRuntime.orders().discard(player.getUUID(),
                MinecraftAudienceProvider.experienceLevel(EconomyBountiesRuntime.server(), player.getUUID()),
                NamespacedId.parse(required(board, "order board")), slot,
                uuid(offerId), Instant.now());
    }

    private static String generatedAccept(ServerPlayer player, String id) {
        return EconomyBountiesRuntime.generated().accept(player.getUUID(), uuid(id), Instant.now())
                .map(view -> view.status() == BountyStatus.ACTIVE ? "Bounty accepted" : "Bounty cannot be accepted in its current state")
                .orElse("Bounty not found");
    }

    private static String generatedCancel(ServerPlayer player, String id) {
        return EconomyBountiesRuntime.generated().cancel(player.getUUID(), uuid(id), Instant.now())
                .map(view -> view.status() == BountyStatus.CANCELLED ? "Bounty cancelled" : "Bounty cannot be cancelled in its current state")
                .orElse("Bounty not found");
    }

    private static String generatedClaim(ServerPlayer player, String id) {
        BountyService.ClaimResult result = EconomyBountiesRuntime.generated().claim(player.getUUID(), uuid(id), Instant.now());
        return result.message().isBlank() ? switch (result.status()) {
            case PAID -> "Reward claimed";
            case ALREADY_CLAIMED -> "Reward already claimed";
            case NOT_FOUND -> "Bounty not found";
            case NOT_COMPLETED -> "Bounty is not complete";
            case EXPIRED -> "Bounty expired";
            case CANCELLED -> "Bounty was cancelled";
            case PAYOUT_FAILED -> "Reward payout failed; retry is safe";
        } : result.message();
    }

    private enum PostedAction { ACCEPT, CANCEL, CLAIM }

    private static String posted(ServerPlayer player, String id, PostedAction action) {
        UUID bountyId = uuid(id);
        PostedBountyService.ActionResult result = switch (action) {
            case ACCEPT -> EconomyBountiesRuntime.posted().accept(player.getUUID(), bountyId, Instant.now());
            case CANCEL -> EconomyBountiesRuntime.posted().cancel(player.getUUID(), bountyId, Instant.now());
            case CLAIM -> EconomyBountiesRuntime.posted().claim(player.getUUID(), bountyId, Instant.now());
        };
        if (!result.message().isBlank()) return result.message();
        return switch (result.status()) {
            case SUCCESS -> switch (action) {
                case ACCEPT -> "Posted bounty accepted";
                case CANCEL -> "Posted bounty cancelled and refund completed";
                case CLAIM -> "Posted bounty reward claimed";
            };
            case NOT_FOUND -> "Posted bounty not found";
            case NOT_ALLOWED -> "You are not allowed to do that";
            case INVALID_STATE -> "Posted bounty is not in a valid state for that action";
            case PAYMENT_PENDING -> "Payment is pending; recovery will retry safely";
            case PAYMENT_FAILED -> "Payment failed";
        };
    }

    private static String createPosted(ServerPlayer player, BoardRequest.CreateDraft draft) {
        if (draft == null) throw new IllegalArgumentException("Missing bounty form");
        if (draft.objectives().isEmpty() || draft.objectives().size() > MAX_CREATE_OBJECTIVES) {
            throw new IllegalArgumentException("A posted bounty needs 1.." + MAX_CREATE_OBJECTIVES + " objectives");
        }
        if (draft.lifetimeMinutes() < 1 || draft.lifetimeMinutes() > MAX_LIFETIME_MINUTES) {
            throw new IllegalArgumentException("Lifetime must be between 1 minute and 30 days");
        }
        long ownOutstanding = EconomyBountiesRuntime.posted().listCreatedBy(player.getUUID(), Instant.now()).stream()
                .filter(view -> view.status() != PostedBountyStatus.CLAIMED
                        && view.status() != PostedBountyStatus.CANCELLED
                        && view.status() != PostedBountyStatus.EXPIRED)
                .count();
        if (ownOutstanding >= MAX_OUTSTANDING_POSTED_BY_PLAYER) {
            throw new IllegalArgumentException("You already have too many outstanding posted bounties");
        }

        BigDecimal reward;
        try { reward = new BigDecimal(required(draft.reward(), "reward")); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Reward must be a valid decimal number"); }

        List<ObjectiveDefinition> objectives = new ArrayList<>();
        for (BoardRequest.ObjectiveDraft objective : draft.objectives()) {
            if (objective.amount() <= 0) throw new IllegalArgumentException("Objective amount must be positive");
            NamespacedId type = NamespacedId.parse(required(objective.type(), "objective type"));
            String target = required(objective.target(), "objective target");
            objectives.add(new ObjectiveDefinition(type, target,
                    new LongRange(objective.amount(), objective.amount()), java.util.Map.of()));
        }

        BountyAudience audience = audience(draft.audience());
        PostedBountyService.CreateRequest request = new PostedBountyService.CreateRequest(
                draft.title(), draft.description(), draft.icon(), objectives, reward, audience,
                Duration.ofMinutes(draft.lifetimeMinutes()));
        PostedBountyService.CreateResult result = EconomyBountiesRuntime.posted().create(player.getUUID(), request, Instant.now());
        if (!result.message().isBlank()) return result.message();
        return switch (result.status()) {
            case CREATED -> "Posted bounty created and funded";
            case FUNDING_PENDING -> "Bounty saved; funding recovery is pending";
            case REJECTED -> "Bounty creation rejected";
        };
    }

    private static BountyAudience audience(BoardRequest.AudienceDraft draft) {
        draft = draft == null ? BoardRequest.AudienceDraft.publicAudience() : draft;
        Set<UUID> allowedPlayers = playerIds(draft.allowedPlayers(), "allowed player");
        Set<UUID> deniedPlayers = playerIds(draft.deniedPlayers(), "denied player");
        if (draft.allowedGroups().size() > MAX_AUDIENCE_ENTRIES) throw new IllegalArgumentException("Too many allowed groups");
        Set<String> allowedGroups = new LinkedHashSet<>();
        for (String value : draft.allowedGroups()) if (value != null && !value.isBlank()) allowedGroups.add(value.trim());
        Optional<NamespacedId> progression = draft.progressionGroup() == null || draft.progressionGroup().isBlank()
                ? Optional.empty() : Optional.of(NamespacedId.parse(draft.progressionGroup().trim()));
        return new BountyAudience(draft.publicAccess(), allowedPlayers, allowedGroups, deniedPlayers,
                progression, draft.minLevel(), draft.maxLevel());
    }

    private static Set<UUID> playerIds(List<String> values, String label) {
        Set<UUID> result = new LinkedHashSet<>();
        if (values == null) return result;
        if (values.size() > MAX_AUDIENCE_ENTRIES) throw new IllegalArgumentException("Too many " + label + " entries");
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String trimmed = value.trim();
            try {
                result.add(UUID.fromString(trimmed));
                continue;
            } catch (IllegalArgumentException ignored) {
                // User-facing forms may use online player names; offline targets should use UUIDs.
            }
            ServerPlayer online = EconomyBountiesRuntime.server().getPlayerList().getPlayerByName(trimmed);
            if (online == null) throw new IllegalArgumentException("Unknown " + label + ": " + trimmed + " (use UUID for offline players)");
            result.add(online.getUUID());
        }
        return result;
    }

    private static BoardSnapshot.BountyEntry generatedEntry(BountyView view) {
        boolean active = view.status() == BountyStatus.ACTIVE;
        List<BoardSnapshot.ObjectiveEntry> objectives = IntStream.range(0, view.objectives().size()).mapToObj(index -> {
            BountyObjectiveView objective = view.objectives().get(index);
            return new BoardSnapshot.ObjectiveEntry(index, objective.definition().type().toString(), objective.definition().target(),
                    objective.requiredAmount(), objective.progress(), active && isDelivery(objective.definition().type()));
        }).toList();
        return new BoardSnapshot.BountyEntry(view.instanceId().toString(), "generated",
                view.definition().id().toString(), "Group " + view.definition().group() + " • Tier " + view.definition().tier(), "",
                view.rewardAmount().toPlainString(), view.status().name(), view.expiresAt().getEpochSecond(), objectives,
                view.status() == BountyStatus.OFFERED,
                view.status() == BountyStatus.OFFERED || view.status() == BountyStatus.ACTIVE,
                view.status() == BountyStatus.COMPLETED,
                view.objectives().isEmpty() ? "minecraft:paper"
                    : view.objectives().get(0).definition().target(), false);
    }

    private static BoardSnapshot.BountyEntry postedEntry(UUID playerId, PostedBountyView view) {
        boolean activeForPlayer = view.status() == PostedBountyStatus.ACTIVE && playerId.equals(view.claimantId());
        List<BoardSnapshot.ObjectiveEntry> objectives = IntStream.range(0, view.objectives().size()).mapToObj(index -> {
            PostedBountyObjectiveView objective = view.objectives().get(index);
            return new BoardSnapshot.ObjectiveEntry(index, objective.definition().type().toString(), objective.definition().target(),
                    objective.targetAmount(), objective.progress(), activeForPlayer && isDelivery(objective.definition().type()));
        }).toList();
        String owner = "Creator " + shortId(view.creatorId());
        if (view.claimantId() != null) owner += " • Claimant " + shortId(view.claimantId());
        return new BoardSnapshot.BountyEntry(view.bountyId().toString(), "posted", view.title(), owner,
                view.description(), view.rewardAmount().toPlainString(), view.status().name(), view.expiresAt().getEpochSecond(), objectives,
                view.status() == PostedBountyStatus.OPEN && !view.creatorId().equals(playerId),
                view.status() == PostedBountyStatus.OPEN && view.creatorId().equals(playerId),
                view.status() == PostedBountyStatus.COMPLETED && playerId.equals(view.claimantId()),
                view.icon(), view.creatorId().equals(playerId));
    }

    private static boolean isDelivery(NamespacedId type) {
        return BuiltinObjectiveTypes.DELIVER_ITEM.equals(type) || BuiltinObjectiveTypes.DELIVER_FLUID.equals(type);
    }

    private static UUID uuid(String value) {
        try { return UUID.fromString(required(value, "bounty id")); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid bounty id"); }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + label);
        return value.trim();
    }

    private static String shortId(UUID id) {
        String value = id.toString();
        return value.substring(0, 8);
    }
}
