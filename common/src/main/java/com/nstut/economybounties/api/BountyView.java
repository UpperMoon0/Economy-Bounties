package com.nstut.economybounties.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record BountyView(
        UUID instanceId,
        UUID playerId,
        BountyDefinition definition,
        Instant offeredAt,
        Instant expiresAt,
        BigDecimal rewardAmount,
        List<BountyObjectiveView> objectives,
        BountyStatus status,
        long deterministicSeed,
        String payoutTransactionId
) {
    public BountyView {
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        definition = Objects.requireNonNull(definition, "definition");
        offeredAt = Objects.requireNonNull(offeredAt, "offeredAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        rewardAmount = Objects.requireNonNull(rewardAmount, "rewardAmount");
        objectives = List.copyOf(Objects.requireNonNull(objectives, "objectives"));
        status = Objects.requireNonNull(status, "status");
        payoutTransactionId = payoutTransactionId == null ? "" : payoutTransactionId;
    }
}
