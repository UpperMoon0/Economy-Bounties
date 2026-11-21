package com.nstut.economybounties.api;

import java.util.Map;
import java.util.Objects;

public record ObjectiveDefinition(
        NamespacedId type,
        String target,
        LongRange amount,
        Map<String, String> metadata
) {
    public ObjectiveDefinition {
        type = Objects.requireNonNull(type, "type");
        target = Objects.requireNonNull(target, "target");
        if (target.isBlank()) throw new IllegalArgumentException("target must not be blank");
        amount = Objects.requireNonNull(amount, "amount");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
