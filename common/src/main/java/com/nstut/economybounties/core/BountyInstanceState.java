package com.nstut.economybounties.core;

import com.nstut.economybounties.api.BountyDefinition;
import com.nstut.economybounties.api.BountyObjectiveView;
import com.nstut.economybounties.api.BountyStatus;
import com.nstut.economybounties.api.BountyView;
import com.nstut.economybounties.api.ObjectiveDefinition;
import com.nstut.economybounties.api.ObjectiveRegistry;
import com.nstut.economybounties.api.ObjectiveType;
import com.nstut.economybounties.api.ProgressEvent;
import com.nstut.economybounties.api.ProgressScope;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class BountyInstanceState {
    private final UUID instanceId;
    private final UUID playerId;
    private final BountyDefinition definition;
    private final Instant offeredAt;
    private final Instant expiresAt;
    private final BigDecimal rewardAmount;
    private final List<ObjectiveState> objectives;
    private final long deterministicSeed;
    private BountyStatus status;
    private String payoutTransactionId;

    BountyInstanceState(
            UUID instanceId,
            UUID playerId,
            BountyDefinition definition,
            Instant offeredAt,
            Instant expiresAt,
            BigDecimal rewardAmount,
            List<ObjectiveState> objectives,
            long deterministicSeed
    ) {
        this(instanceId, playerId, definition, offeredAt, expiresAt, rewardAmount, objectives,
                BountyStatus.OFFERED, deterministicSeed, "");
    }

    private BountyInstanceState(
            UUID instanceId,
            UUID playerId,
            BountyDefinition definition,
            Instant offeredAt,
            Instant expiresAt,
            BigDecimal rewardAmount,
            List<ObjectiveState> objectives,
            BountyStatus status,
            long deterministicSeed,
            String payoutTransactionId
    ) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.offeredAt = Objects.requireNonNull(offeredAt, "offeredAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.rewardAmount = Objects.requireNonNull(rewardAmount, "rewardAmount");
        this.objectives = new ArrayList<>(Objects.requireNonNull(objectives, "objectives"));
        this.status = Objects.requireNonNull(status, "status");
        this.deterministicSeed = deterministicSeed;
        this.payoutTransactionId = payoutTransactionId == null ? "" : payoutTransactionId;
    }

    static BountyInstanceState fromView(BountyView view) {
        List<ObjectiveState> objectives = view.objectives().stream()
                .map(v -> new ObjectiveState(v.definition(), v.requiredAmount(), v.progress()))
                .toList();
        return new BountyInstanceState(view.instanceId(), view.playerId(), view.definition(), view.offeredAt(),
                view.expiresAt(), view.rewardAmount(), objectives, view.status(), view.deterministicSeed(),
                view.payoutTransactionId());
    }

    UUID instanceId() { return instanceId; }
    UUID playerId() { return playerId; }
    BountyDefinition definition() { return definition; }
    BountyStatus status() { return status; }
    BigDecimal rewardAmount() { return rewardAmount; }

    boolean expire(Instant now) {
        if ((status == BountyStatus.OFFERED || status == BountyStatus.ACTIVE) && !now.isBefore(expiresAt)) {
            status = BountyStatus.EXPIRED;
            return true;
        }
        return false;
    }

    boolean accept(Instant now) {
        expire(now);
        if (status != BountyStatus.OFFERED) return false;
        status = BountyStatus.ACTIVE;
        return true;
    }

    boolean cancel(Instant now) {
        expire(now);
        if (status != BountyStatus.OFFERED && status != BountyStatus.ACTIVE) return false;
        status = BountyStatus.CANCELLED;
        return true;
    }

    boolean applyProgress(ObjectiveRegistry registry, ProgressEvent event, Instant now) {
        expire(now);
        if (status != BountyStatus.ACTIVE || !playerId.equals(event.playerId())) return false;

        boolean changed = false;
        for (int objectiveIndex = 0; objectiveIndex < objectives.size(); objectiveIndex++) {
            ObjectiveState objective = objectives.get(objectiveIndex);
            if (objective.complete()) continue;
            if (!ProgressScope.applies(event, "generated", instanceId, objectiveIndex)) continue;
            ObjectiveType type = registry.require(objective.definition.type());
            long delta = type.progressDelta(objective.definition, event);
            if (delta > 0) changed |= objective.add(delta);
        }

        if (changed && objectives.stream().allMatch(ObjectiveState::complete)) {
            status = BountyStatus.COMPLETED;
        }
        return changed;
    }

    void markClaimed(String transactionId) {
        if (status != BountyStatus.COMPLETED) {
            throw new IllegalStateException("Only completed bounties can be claimed");
        }
        status = BountyStatus.CLAIMED;
        payoutTransactionId = transactionId == null ? "" : transactionId;
    }

    BountyView view() {
        return new BountyView(instanceId, playerId, definition, offeredAt, expiresAt, rewardAmount,
                objectives.stream().map(ObjectiveState::view).toList(), status, deterministicSeed, payoutTransactionId);
    }

    static final class ObjectiveState {
        private final ObjectiveDefinition definition;
        private final long requiredAmount;
        private long progress;

        ObjectiveState(ObjectiveDefinition definition, long requiredAmount) {
            this(definition, requiredAmount, 0);
        }

        ObjectiveState(ObjectiveDefinition definition, long requiredAmount, long progress) {
            this.definition = Objects.requireNonNull(definition, "definition");
            if (requiredAmount < 0) throw new IllegalArgumentException("requiredAmount must be >= 0");
            if (progress < 0 || progress > requiredAmount) throw new IllegalArgumentException("Invalid progress");
            this.requiredAmount = requiredAmount;
            this.progress = progress;
        }

        boolean complete() { return progress >= requiredAmount; }

        boolean add(long delta) {
            if (delta <= 0 || complete()) return false;
            long next;
            try {
                next = Math.addExact(progress, delta);
            } catch (ArithmeticException overflow) {
                next = Long.MAX_VALUE;
            }
            progress = Math.min(requiredAmount, next);
            return true;
        }

        BountyObjectiveView view() {
            return new BountyObjectiveView(definition, requiredAmount, progress);
        }
    }
}
