package com.nstut.economybounties.api;

import java.util.Objects;

public record BountyObjectiveView(
        ObjectiveDefinition definition,
        long requiredAmount,
        long progress
) {
    public BountyObjectiveView {
        definition = Objects.requireNonNull(definition, "definition");
        if (requiredAmount < 0) throw new IllegalArgumentException("requiredAmount must be >= 0");
        if (progress < 0 || progress > requiredAmount) {
            throw new IllegalArgumentException("progress must be within 0..requiredAmount");
        }
    }

    public boolean complete() {
        return progress >= requiredAmount;
    }
}
