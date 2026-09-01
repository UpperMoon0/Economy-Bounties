package com.nstut.economybounties.api;

import java.util.Objects;

/**
 * Generic objective implementation for loader adapters that emit one event type and target.
 * Examples include kill-entity, craft-item, mine-block and deliver-item events.
 */
public final class MatchingObjectiveType implements ObjectiveType {
    private final NamespacedId id;

    public MatchingObjectiveType(NamespacedId id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public NamespacedId id() {
        return id;
    }

    @Override
    public long progressDelta(ObjectiveDefinition definition, ProgressEvent event) {
        if (!event.playerId().equals(event.playerId())) return 0;
        if (!id.equals(event.type())) return 0;
        if (!definition.target().equals(event.target())) return 0;
        return event.amount();
    }
}
