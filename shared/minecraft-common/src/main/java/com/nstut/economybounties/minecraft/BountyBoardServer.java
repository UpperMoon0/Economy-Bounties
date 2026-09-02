package com.nstut.economybounties.minecraft;

import com.nstut.economybounties.api.*;
import com.nstut.economybounties.board.BoardRequest;
import com.nstut.economybounties.board.BoardSnapshot;
import com.nstut.economybounties.network.BountyNetwork;
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

/** Resolves every board action against server-owned state; clients only send intent. */
public final class BountyBoardServer {
    private static final int MAX_CREATE_OBJECTIVES = 16;
    private static final long MAX_LIFETIME_MINUTES = 60L * 24L * 30L;

    private BountyBoardServer() { }

    public static void open(ServerPlayer player) { send(player, ""); }

    public static void handle(ServerPlayer player, BoardRequest request) {
        if (player == null || request == null) return;
        if (!EconomyBountiesRuntime.ready()) {
            send(player, "Economy Bounties is still starting");
            return;
        }
        String notice;
        try {
            notice = switch (request.action()) {
                case REFRESH -> "";
                case ROLL -> roll(player, request.poolId());
                case ACCEPT_GENERATED -> generatedAccept(player, request.bountyId());
                case CANCEL_GENERATED -> generatedCancel(player, request.bountyId());
                case CLAIM_GENERATED -> generatedClaim(player, request.bountyId());
                case ACCEPT_POSTED -> posted(player, request.bountyId(), PostedAction.ACCEPT);
                case CANCEL_POSTED -> posted(player, request.bountyId(), PostedAction.CANCEL);
                case CLAIM_POSTED -> posted(player, request.bountyId(), PostedAction.CLAIM);
                case CREATE_POSTED -> createPosted(player, request.create());
                case DELIVER -> BountyDeliveryService.deliver(player, request.objectiveType(), request.objectiveTarget());
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
        List<BoardSnapshot.PoolEntry> pools = EconomyBountiesRuntime.pools().keySet().stream()
                .map(NamespacedId::toString).sorted().map(BoardSnapshot.PoolEntry::new).toList();
        List<BoardSnapshot.BountyEntry> generated = EconomyBountiesRuntime.generatedFor(player.getUUID(), now).stream()
                .map(BountyBoardServer::generatedEntry)
                .sorted(Comparator.comparing(BoardSnapshot.BountyEntry::expiresAtEpochSecond))
                .toList();
        List<BoardSnapshot.BountyEntry> posted = EconomyBountiesRuntime.postedFor(player.getUUID(), now).stream()
                .map(view -> postedEntry(player.getUUID(), view))
                .sorted(Comparator.comparing(BoardSnapshot.BountyEntry::expiresAtEpochSecond))
                .toList();
        return new BoardSnapshot(pools, generated, posted, notice);
    }

    private static String roll(ServerPlayer player, String poolId) {
        NamespacedId id = NamespacedId.parse(required(poolId, "pool id"));
        if (!EconomyBountiesRuntime.pools().containsKey(id)) return "Unknown bounty pool: " + id;
        return EconomyBountiesRuntime.roll(player, id).isPresent()
                ? "New bounty offer generated"
                : "No eligible bounty is available from this pool";
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
        Set<UUID> allowedPlayers = uuids(draft.allowedPlayers(), "allowed player");
        Set<UUID> deniedPlayers = uuids(draft.deniedPlayers(), "denied player");
        Set<String> allowedGroups = new LinkedHashSet<>();
        for (String value : draft.allowedGroups()) if (value != null && !value.isBlank()) allowedGroups.add(value.trim());
        Optional<NamespacedId> progression = draft.progressionGroup() == null || draft.progressionGroup().isBlank()
                ? Optional.empty() : Optional.of(NamespacedId.parse(draft.progressionGroup().trim()));
        return new BountyAudience(draft.publicAccess(), allowedPlayers, allowedGroups, deniedPlayers,
                progression, draft.minLevel(), draft.maxLevel());
    }

    private static Set<UUID> uuids(List<String> values, String label) {
        Set<UUID> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            try { result.add(UUID.fromString(value.trim())); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid " + label + " UUID: " + value); }
        }
        return result;
    }

    private static BoardSnapshot.BountyEntry generatedEntry(BountyView view) {
        boolean active = view.status() == BountyStatus.ACTIVE;
        List<BoardSnapshot.ObjectiveEntry> objectives = view.objectives().stream().map(objective -> new BoardSnapshot.ObjectiveEntry(
                objective.definition().type().toString(), objective.definition().target(), objective.requiredAmount(), objective.progress(),
                active && isDelivery(objective.definition().type()))).toList();
        return new BoardSnapshot.BountyEntry(view.instanceId().toString(), "generated",
                view.definition().id().toString(), "Group " + view.definition().group() + " • Tier " + view.definition().tier(), "",
                view.rewardAmount().toPlainString(), view.status().name(), view.expiresAt().getEpochSecond(), objectives,
                view.status() == BountyStatus.OFFERED,
                view.status() == BountyStatus.OFFERED || view.status() == BountyStatus.ACTIVE,
                view.status() == BountyStatus.COMPLETED);
    }

    private static BoardSnapshot.BountyEntry postedEntry(UUID playerId, PostedBountyView view) {
        boolean activeForPlayer = view.status() == PostedBountyStatus.ACTIVE && playerId.equals(view.claimantId());
        List<BoardSnapshot.ObjectiveEntry> objectives = view.objectives().stream().map(objective -> new BoardSnapshot.ObjectiveEntry(
                objective.definition().type().toString(), objective.definition().target(), objective.targetAmount(), objective.progress(),
                activeForPlayer && isDelivery(objective.definition().type()))).toList();
        String owner = "Creator " + shortId(view.creatorId());
        if (view.claimantId() != null) owner += " • Claimant " + shortId(view.claimantId());
        return new BoardSnapshot.BountyEntry(view.bountyId().toString(), "posted", view.title(), owner,
                view.description(), view.rewardAmount().toPlainString(), view.status().name(), view.expiresAt().getEpochSecond(), objectives,
                view.status() == PostedBountyStatus.OPEN && !view.creatorId().equals(playerId),
                view.status() == PostedBountyStatus.OPEN && view.creatorId().equals(playerId),
                view.status() == PostedBountyStatus.COMPLETED && playerId.equals(view.claimantId()));
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
