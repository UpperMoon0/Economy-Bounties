package com.nstut.economybounties.api;

/** Durable states include in-flight escrow operations for crash-safe recovery. */
public enum PostedBountyStatus {
    FUNDING,
    OPEN,
    ACTIVE,
    COMPLETED,
    PAYING,
    CLAIMED,
    CANCELLING,
    CANCELLED,
    EXPIRING,
    EXPIRED
}
