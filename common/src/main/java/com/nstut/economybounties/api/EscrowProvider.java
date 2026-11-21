package com.nstut.economybounties.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Money boundary for player-posted bounties. Implementations must make each
 * operation idempotent for a bounty id so crash recovery can safely retry it.
 */
public interface EscrowProvider {
    Result fund(UUID bountyId, UUID creatorId, BigDecimal amount, Map<String, String> metadata);
    Result payout(UUID bountyId, UUID claimantId, BigDecimal amount, Map<String, String> metadata);
    Result refund(UUID bountyId, UUID creatorId, BigDecimal amount, Map<String, String> metadata);

    record Result(boolean success, String transactionId, String message) {
        public Result {
            transactionId = transactionId == null ? "" : transactionId;
            message = message == null ? "" : message;
        }

        public static Result success(String transactionId) { return new Result(true, transactionId, ""); }
        public static Result failure(String message) { return new Result(false, "", message); }
    }
}
