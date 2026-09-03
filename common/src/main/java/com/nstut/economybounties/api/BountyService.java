package com.nstut.economybounties.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BountyService {
    void replaceDefinitions(Collection<BountyDefinition> definitions);

    /** Rolls within one explicit bounty group. */
    Optional<BountyView> rollOffer(UUID playerId, NamespacedId group, RollContext context);

    /** Rolls a weighted eligible group from a pool, then a weighted bounty inside that group. */
    Optional<BountyView> rollOffer(UUID playerId, BountyPoolDefinition pool, RollContext context);

    Optional<BountyView> accept(UUID playerId, UUID instanceId, Instant now);

    Optional<BountyView> cancel(UUID playerId, UUID instanceId, Instant now);

    List<BountyView> recordProgress(ProgressEvent event, Instant now);

    ClaimResult claim(UUID playerId, UUID instanceId, Instant now);

    Optional<BountyView> get(UUID playerId, UUID instanceId, Instant now);

    List<BountyView> list(UUID playerId, Instant now);

    record RollContext(long worldSeed, long rotationEpoch, int rerollOrdinal, Instant now) {
        public RollContext {
            if (rerollOrdinal < 0) throw new IllegalArgumentException("rerollOrdinal must be >= 0");
            if (now == null) throw new NullPointerException("now");
        }
    }

    record ClaimResult(Status status, BountyView bounty, String message) {
        public enum Status {
            PAID,
            ALREADY_CLAIMED,
            NOT_FOUND,
            NOT_COMPLETED,
            EXPIRED,
            CANCELLED,
            PAYOUT_FAILED
        }

        public ClaimResult {
            if (status == null) throw new NullPointerException("status");
            message = message == null ? "" : message;
        }
    }
}
