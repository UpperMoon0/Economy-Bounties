package com.nstut.economybounties.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable public snapshot of a player-created bounty. */
public record PostedBountyView(
        UUID bountyId,
        UUID creatorId,
        UUID claimantId,
        String title,
        String description,
        String icon,
        List<PostedBountyObjectiveView> objectives,
        BigDecimal rewardAmount,
        BountyAudience audience,
        Instant createdAt,
        Instant expiresAt,
        PostedBountyStatus status,
        String fundingTransactionId,
        String payoutTransactionId,
        String refundTransactionId
) {
    public PostedBountyView {
        bountyId = Objects.requireNonNull(bountyId, "bountyId");
        creatorId = Objects.requireNonNull(creatorId, "creatorId");
        title = Objects.requireNonNullElse(title, "").trim();
        description = Objects.requireNonNullElse(description, "").trim();
        icon = Objects.requireNonNullElse(icon, "minecraft:paper").trim();
        if (title.isBlank()) throw new IllegalArgumentException("title must not be blank");
        if (icon.isBlank()) throw new IllegalArgumentException("icon must not be blank");
        objectives = List.copyOf(Objects.requireNonNull(objectives, "objectives"));
        if (objectives.isEmpty()) throw new IllegalArgumentException("A posted bounty needs at least one objective");
        rewardAmount = Objects.requireNonNull(rewardAmount, "rewardAmount");
        if (rewardAmount.signum() <= 0) throw new IllegalArgumentException("rewardAmount must be > 0");
        audience = Objects.requireNonNull(audience, "audience");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("expiresAt must be after createdAt");
        status = Objects.requireNonNull(status, "status");
        fundingTransactionId = fundingTransactionId == null ? "" : fundingTransactionId;
        payoutTransactionId = payoutTransactionId == null ? "" : payoutTransactionId;
        refundTransactionId = refundTransactionId == null ? "" : refundTransactionId;
    }

    public boolean complete() { return objectives.stream().allMatch(PostedBountyObjectiveView::complete); }
}
