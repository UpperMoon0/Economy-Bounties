package com.nstut.economybounties.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Loader-neutral gameplay progress event emitted by a Minecraft adapter or addon. */
public record ProgressEvent(
        UUID playerId,
        NamespacedId type,
        String target,
        long amount,
        Map<String, String> metadata
) {
    public ProgressEvent {
        playerId = Objects.requireNonNull(playerId, "playerId");
        type = Objects.requireNonNull(type, "type");
        target = Objects.requireNonNull(target, "target");
        if (amount <= 0) throw new IllegalArgumentException("amount must be > 0");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
