package com.nstut.economybounties.api;

import java.util.Map;
import java.util.Objects;

public record RewardDefinition(
        DecimalRange currency,
        Map<String, String> metadata
) {
    public RewardDefinition {
        currency = Objects.requireNonNull(currency, "currency");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
