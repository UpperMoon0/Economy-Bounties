package com.nstut.economybounties.api;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PostedBountyService {
    CreateResult create(UUID creatorId, CreateRequest request, Instant now);
    ActionResult accept(UUID playerId, UUID bountyId, Instant now);
    ActionResult cancel(UUID creatorId, UUID bountyId, Instant now);
    List<PostedBountyView> recordProgress(ProgressEvent event, Instant now);
    ActionResult claim(UUID playerId, UUID bountyId, Instant now);
    Optional<PostedBountyView> get(UUID bountyId, Instant now);
    List<PostedBountyView> listVisible(UUID playerId, Instant now);
    List<PostedBountyView> listCreatedBy(UUID creatorId, Instant now);
    void recover(Instant now);

    record CreateRequest(
            String title,
            String description,
            String icon,
            List<ObjectiveDefinition> objectives,
            BigDecimal rewardAmount,
            BountyAudience audience,
            Duration lifetime
    ) {
        public CreateRequest {
            title = title == null ? "" : title.trim();
            description = description == null ? "" : description.trim();
            icon = icon == null || icon.isBlank() ? "minecraft:paper" : icon.trim();
            objectives = List.copyOf(objectives == null ? List.of() : objectives);
            if (title.isBlank()) throw new IllegalArgumentException("title must not be blank");
            if (title.length() > 96) throw new IllegalArgumentException("title is too long");
            if (description.length() > 512) throw new IllegalArgumentException("description is too long");
            if (objectives.isEmpty() || objectives.size() > 16) throw new IllegalArgumentException("objectives must contain 1..16 entries");
            if (rewardAmount == null || rewardAmount.signum() <= 0) throw new IllegalArgumentException("rewardAmount must be > 0");
            if (audience == null) throw new NullPointerException("audience");
            if (lifetime == null || lifetime.isZero() || lifetime.isNegative()) throw new IllegalArgumentException("lifetime must be positive");
            for (ObjectiveDefinition objective : objectives) {
                if (objective.amount().min() != objective.amount().max()) {
                    throw new IllegalArgumentException("Player-posted objectives require a fixed amount");
                }
            }
        }
    }

    record CreateResult(Status status, PostedBountyView bounty, String message) {
        public enum Status { CREATED, FUNDING_PENDING, REJECTED }
        public CreateResult { if (status == null) throw new NullPointerException("status"); message = message == null ? "" : message; }
    }

    record ActionResult(Status status, PostedBountyView bounty, String message) {
        public enum Status {
            SUCCESS, NOT_FOUND, NOT_ALLOWED, INVALID_STATE, PAYMENT_PENDING, PAYMENT_FAILED
        }
        public ActionResult { if (status == null) throw new NullPointerException("status"); message = message == null ? "" : message; }
    }
}
