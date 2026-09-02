package com.nstut.economybounties.core;

import com.nstut.economybounties.api.AudienceProvider;
import com.nstut.economybounties.api.BountyAudience;
import com.nstut.economybounties.api.EscrowProvider;
import com.nstut.economybounties.api.ObjectiveDefinition;
import com.nstut.economybounties.api.ObjectiveRegistry;
import com.nstut.economybounties.api.ObjectiveType;
import com.nstut.economybounties.api.PostedBountyObjectiveView;
import com.nstut.economybounties.api.PostedBountyService;
import com.nstut.economybounties.api.PostedBountyStatus;
import com.nstut.economybounties.api.PostedBountyStore;
import com.nstut.economybounties.api.PostedBountyView;
import com.nstut.economybounties.api.ProgressEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Default crash-recoverable implementation for player-created bounties. */
public final class DefaultPostedBountyService implements PostedBountyService {
    private final AudienceProvider audienceProvider;
    private final ObjectiveRegistry objectiveRegistry;
    private final PostedBountyStore store;
    private final EscrowProvider escrow;
    private final Map<UUID, PostedBountyView> bounties = new LinkedHashMap<>();

    public DefaultPostedBountyService(AudienceProvider audienceProvider, ObjectiveRegistry objectiveRegistry,
                                      PostedBountyStore store, EscrowProvider escrow) {
        this.audienceProvider = Objects.requireNonNull(audienceProvider, "audienceProvider");
        this.objectiveRegistry = Objects.requireNonNull(objectiveRegistry, "objectiveRegistry");
        this.store = Objects.requireNonNull(store, "store");
        this.escrow = Objects.requireNonNull(escrow, "escrow");
        for (PostedBountyView view : store.load()) {
            PostedBountyView previous = bounties.putIfAbsent(view.bountyId(), view);
            if (previous != null) throw new IllegalStateException("Duplicate posted bounty id " + view.bountyId());
        }
    }

    @Override
    public synchronized CreateResult create(UUID creatorId, CreateRequest request, Instant now) {
        Objects.requireNonNull(creatorId, "creatorId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");
        for (ObjectiveDefinition objective : request.objectives()) objectiveRegistry.validate(objective);

        UUID id = UUID.randomUUID();
        List<PostedBountyObjectiveView> objectives = request.objectives().stream()
                .map(def -> new PostedBountyObjectiveView(def, def.amount().min(), 0)).toList();
        PostedBountyView funding = new PostedBountyView(id, creatorId, null, request.title(), request.description(),
                request.icon(), objectives, request.rewardAmount(), request.audience(), now, now.plus(request.lifetime()),
                PostedBountyStatus.FUNDING, "", "", "");
        bounties.put(id, funding);
        save();

        EscrowProvider.Result result = safeFund(funding);
        if (!result.success()) {
            return new CreateResult(CreateResult.Status.FUNDING_PENDING, funding,
                    result.message().isBlank() ? "Escrow funding is pending" : result.message());
        }
        PostedBountyView open = copy(funding, null, objectives, PostedBountyStatus.OPEN,
                result.transactionId(), "", "");
        bounties.put(id, open);
        save();
        return new CreateResult(CreateResult.Status.CREATED, open, "");
    }

    @Override
    public synchronized ActionResult accept(UUID playerId, UUID bountyId, Instant now) {
        Objects.requireNonNull(playerId, "playerId");
        PostedBountyView view = maintainOne(bountyId, now);
        if (view == null) return result(ActionResult.Status.NOT_FOUND, null, "Bounty not found");
        if (view.status() != PostedBountyStatus.OPEN) return result(ActionResult.Status.INVALID_STATE, view, "Bounty is not open");
        if (view.creatorId().equals(playerId)) return result(ActionResult.Status.NOT_ALLOWED, view, "Creators cannot claim their own bounty");
        if (!view.audience().isEligible(playerId, audienceProvider)) {
            return result(ActionResult.Status.NOT_ALLOWED, view, "You are not eligible for this bounty");
        }
        PostedBountyView active = copy(view, playerId, view.objectives(), PostedBountyStatus.ACTIVE,
                view.fundingTransactionId(), view.payoutTransactionId(), view.refundTransactionId());
        bounties.put(bountyId, active);
        save();
        return result(ActionResult.Status.SUCCESS, active, "");
    }

    @Override
    public synchronized ActionResult cancel(UUID creatorId, UUID bountyId, Instant now) {
        Objects.requireNonNull(creatorId, "creatorId");
        PostedBountyView view = maintainOne(bountyId, now);
        if (view == null) return result(ActionResult.Status.NOT_FOUND, null, "Bounty not found");
        if (!view.creatorId().equals(creatorId)) return result(ActionResult.Status.NOT_ALLOWED, view, "Only the creator can cancel this bounty");
        if (view.status() != PostedBountyStatus.OPEN) return result(ActionResult.Status.INVALID_STATE, view, "Only an unclaimed open bounty can be cancelled");

        PostedBountyView pending = copy(view, view.claimantId(), view.objectives(), PostedBountyStatus.CANCELLING,
                view.fundingTransactionId(), view.payoutTransactionId(), view.refundTransactionId());
        bounties.put(bountyId, pending);
        save();
        return finishRefund(pending, PostedBountyStatus.CANCELLED);
    }

    @Override
    public synchronized List<PostedBountyView> recordProgress(ProgressEvent event, Instant now) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(now, "now");
        recover(now);
        List<PostedBountyView> changed = new ArrayList<>();
        boolean dirty = false;
        for (PostedBountyView view : List.copyOf(bounties.values())) {
            if (view.status() != PostedBountyStatus.ACTIVE || !event.playerId().equals(view.claimantId())) continue;
            List<PostedBountyObjectiveView> updated = new ArrayList<>(view.objectives().size());
            boolean viewChanged = false;
            for (PostedBountyObjectiveView objective : view.objectives()) {
                long progress = objective.progress();
                if (!objective.complete()) {
                    ObjectiveType type = objectiveRegistry.require(objective.definition().type());
                    long delta = type.progressDelta(objective.definition(), event);
                    if (delta < 0) throw new IllegalStateException("ObjectiveType returned a negative progress delta");
                    if (delta > 0) {
                        progress = Math.min(objective.targetAmount(), Math.addExact(progress, delta));
                        viewChanged = true;
                    }
                }
                updated.add(new PostedBountyObjectiveView(objective.definition(), objective.targetAmount(), progress));
            }
            if (!viewChanged) continue;
            PostedBountyStatus status = updated.stream().allMatch(PostedBountyObjectiveView::complete)
                    ? PostedBountyStatus.COMPLETED : PostedBountyStatus.ACTIVE;
            PostedBountyView replacement = copy(view, view.claimantId(), updated, status,
                    view.fundingTransactionId(), view.payoutTransactionId(), view.refundTransactionId());
            bounties.put(view.bountyId(), replacement);
            changed.add(replacement);
            dirty = true;
        }
        if (dirty) save();
        return List.copyOf(changed);
    }

    @Override
    public synchronized ActionResult claim(UUID playerId, UUID bountyId, Instant now) {
        Objects.requireNonNull(playerId, "playerId");
        PostedBountyView view = maintainOne(bountyId, now);
        if (view == null) return result(ActionResult.Status.NOT_FOUND, null, "Bounty not found");
        if (!playerId.equals(view.claimantId())) return result(ActionResult.Status.NOT_ALLOWED, view, "Only the claimant can collect this reward");
        if (view.status() == PostedBountyStatus.CLAIMED) return result(ActionResult.Status.SUCCESS, view, "Reward already claimed");
        if (view.status() != PostedBountyStatus.COMPLETED && view.status() != PostedBountyStatus.PAYING) {
            return result(ActionResult.Status.INVALID_STATE, view, "Bounty is not completed");
        }
        if (view.status() == PostedBountyStatus.COMPLETED) {
            view = copy(view, view.claimantId(), view.objectives(), PostedBountyStatus.PAYING,
                    view.fundingTransactionId(), view.payoutTransactionId(), view.refundTransactionId());
            bounties.put(bountyId, view);
            save();
        }
        EscrowProvider.Result payment = safePayout(view);
        if (!payment.success()) return result(ActionResult.Status.PAYMENT_PENDING, view, payment.message());
        PostedBountyView claimed = copy(view, view.claimantId(), view.objectives(), PostedBountyStatus.CLAIMED,
                view.fundingTransactionId(), payment.transactionId(), view.refundTransactionId());
        bounties.put(bountyId, claimed);
        save();
        return result(ActionResult.Status.SUCCESS, claimed, "");
    }

    @Override public synchronized Optional<PostedBountyView> get(UUID bountyId, Instant now) {
        return Optional.ofNullable(maintainOne(bountyId, now));
    }

    @Override
    public synchronized List<PostedBountyView> listVisible(UUID playerId, Instant now) {
        Objects.requireNonNull(playerId, "playerId");
        recover(now);
        return bounties.values().stream()
                .filter(view -> view.creatorId().equals(playerId) || playerId.equals(view.claimantId())
                        || view.status() == PostedBountyStatus.OPEN && view.audience().isEligible(playerId, audienceProvider))
                .sorted(Comparator.comparing(PostedBountyView::createdAt).reversed())
                .toList();
    }

    @Override
    public synchronized List<PostedBountyView> listCreatedBy(UUID creatorId, Instant now) {
        Objects.requireNonNull(creatorId, "creatorId");
        recover(now);
        return bounties.values().stream().filter(view -> view.creatorId().equals(creatorId))
                .sorted(Comparator.comparing(PostedBountyView::createdAt).reversed()).toList();
    }

    @Override
    public synchronized void recover(Instant now) {
        Objects.requireNonNull(now, "now");
        for (PostedBountyView original : List.copyOf(bounties.values())) {
            PostedBountyView view = bounties.get(original.bountyId());
            if (view == null) continue;
            switch (view.status()) {
                case FUNDING -> {
                    EscrowProvider.Result funded = safeFund(view);
                    if (funded.success()) {
                        bounties.put(view.bountyId(), copy(view, null, view.objectives(), PostedBountyStatus.OPEN,
                                funded.transactionId(), view.payoutTransactionId(), view.refundTransactionId()));
                        save();
                    }
                }
                case PAYING -> {
                    EscrowProvider.Result paid = safePayout(view);
                    if (paid.success()) {
                        bounties.put(view.bountyId(), copy(view, view.claimantId(), view.objectives(), PostedBountyStatus.CLAIMED,
                                view.fundingTransactionId(), paid.transactionId(), view.refundTransactionId()));
                        save();
                    }
                }
                case CANCELLING -> finishRefund(view, PostedBountyStatus.CANCELLED);
                case EXPIRING -> finishRefund(view, PostedBountyStatus.EXPIRED);
                default -> { }
            }
            view = bounties.get(original.bountyId());
            if (view != null && (view.status() == PostedBountyStatus.OPEN || view.status() == PostedBountyStatus.ACTIVE)
                    && !now.isBefore(view.expiresAt())) {
                PostedBountyView expiring = copy(view, view.claimantId(), view.objectives(), PostedBountyStatus.EXPIRING,
                        view.fundingTransactionId(), view.payoutTransactionId(), view.refundTransactionId());
                bounties.put(view.bountyId(), expiring);
                save();
                finishRefund(expiring, PostedBountyStatus.EXPIRED);
            }
        }
    }

    private PostedBountyView maintainOne(UUID bountyId, Instant now) {
        Objects.requireNonNull(bountyId, "bountyId");
        recover(now);
        return bounties.get(bountyId);
    }

    private ActionResult finishRefund(PostedBountyView pending, PostedBountyStatus terminal) {
        EscrowProvider.Result refund = safeRefund(pending);
        if (!refund.success()) return result(ActionResult.Status.PAYMENT_PENDING, pending, refund.message());
        PostedBountyView done = copy(pending, pending.claimantId(), pending.objectives(), terminal,
                pending.fundingTransactionId(), pending.payoutTransactionId(), refund.transactionId());
        bounties.put(pending.bountyId(), done);
        save();
        return result(ActionResult.Status.SUCCESS, done, "");
    }

    private EscrowProvider.Result safeFund(PostedBountyView view) {
        try { return requireResult(escrow.fund(view.bountyId(), view.creatorId(), view.rewardAmount(), metadata(view)), "fund"); }
        catch (RuntimeException error) { return EscrowProvider.Result.failure("Escrow provider fund failed: " + error.getClass().getSimpleName()); }
    }

    private EscrowProvider.Result safePayout(PostedBountyView view) {
        if (view.claimantId() == null) return EscrowProvider.Result.failure("Bounty has no claimant");
        try { return requireResult(escrow.payout(view.bountyId(), view.claimantId(), view.rewardAmount(), metadata(view)), "payout"); }
        catch (RuntimeException error) { return EscrowProvider.Result.failure("Escrow provider payout failed: " + error.getClass().getSimpleName()); }
    }

    private EscrowProvider.Result safeRefund(PostedBountyView view) {
        try { return requireResult(escrow.refund(view.bountyId(), view.creatorId(), view.rewardAmount(), metadata(view)), "refund"); }
        catch (RuntimeException error) { return EscrowProvider.Result.failure("Escrow provider refund failed: " + error.getClass().getSimpleName()); }
    }

    private static EscrowProvider.Result requireResult(EscrowProvider.Result result, String operation) {
        return result == null ? EscrowProvider.Result.failure("Escrow provider returned no " + operation + " result") : result;
    }

    private static Map<String, String> metadata(PostedBountyView view) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("economy_bounties:bounty_id", view.bountyId().toString());
        values.put("economy_bounties:creator_id", view.creatorId().toString());
        values.put("economy_bounties:title", view.title());
        if (view.claimantId() != null) values.put("economy_bounties:claimant_id", view.claimantId().toString());
        return Map.copyOf(values);
    }

    private void save() { store.save(List.copyOf(bounties.values())); }

    private static ActionResult result(ActionResult.Status status, PostedBountyView bounty, String message) {
        return new ActionResult(status, bounty, message);
    }

    private static PostedBountyView copy(PostedBountyView base, UUID claimant,
                                         List<PostedBountyObjectiveView> objectives, PostedBountyStatus status,
                                         String fundingTx, String payoutTx, String refundTx) {
        return new PostedBountyView(base.bountyId(), base.creatorId(), claimant, base.title(), base.description(), base.icon(),
                objectives, base.rewardAmount(), base.audience(), base.createdAt(), base.expiresAt(), status,
                fundingTx, payoutTx, refundTx);
    }
}
