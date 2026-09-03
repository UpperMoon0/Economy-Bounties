package com.nstut.economybounties.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@FunctionalInterface
public interface RewardProvider {
    PayoutResult payout(RewardContext context);

    record RewardContext(
            UUID payoutKey,
            UUID playerId,
            NamespacedId bountyId,
            BigDecimal currencyAmount,
            Map<String, String> metadata
    ) {
        public RewardContext {
            payoutKey = Objects.requireNonNull(payoutKey, "payoutKey");
            playerId = Objects.requireNonNull(playerId, "playerId");
            bountyId = Objects.requireNonNull(bountyId, "bountyId");
            currencyAmount = Objects.requireNonNull(currencyAmount, "currencyAmount");
            if (currencyAmount.signum() < 0) throw new IllegalArgumentException("currencyAmount must not be negative");
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    record PayoutResult(boolean success, String transactionId, String message) {
        public PayoutResult {
            transactionId = transactionId == null ? "" : transactionId;
            message = message == null ? "" : message;
        }

        public static PayoutResult success(String transactionId) {
            return new PayoutResult(true, transactionId, "");
        }

        public static PayoutResult failure(String message) {
            return new PayoutResult(false, "", message);
        }
    }
}
