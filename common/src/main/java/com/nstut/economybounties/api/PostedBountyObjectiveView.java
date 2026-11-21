package com.nstut.economybounties.api;

import java.util.Objects;

public record PostedBountyObjectiveView(ObjectiveDefinition definition, long targetAmount, long progress) {
    public PostedBountyObjectiveView {
        definition = Objects.requireNonNull(definition, "definition");
        if (targetAmount <= 0) throw new IllegalArgumentException("targetAmount must be > 0");
        if (progress < 0 || progress > targetAmount) throw new IllegalArgumentException("Invalid progress");
    }

    public boolean complete() { return progress >= targetAmount; }
}
