package com.nstut.economybounties.api;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ObjectiveRegistry {
    private final Map<NamespacedId, ObjectiveType> types = new LinkedHashMap<>();

    public synchronized void register(ObjectiveType type) {
        Objects.requireNonNull(type, "type");
        ObjectiveType previous = types.putIfAbsent(type.id(), type);
        if (previous != null) {
            throw new IllegalStateException("Objective type already registered: " + type.id());
        }
    }

    public synchronized Optional<ObjectiveType> find(NamespacedId id) {
        return Optional.ofNullable(types.get(id));
    }

    public synchronized ObjectiveType require(NamespacedId id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("Unknown objective type: " + id));
    }

    public synchronized Collection<NamespacedId> ids() {
        return List.copyOf(types.keySet());
    }

    public synchronized void validate(ObjectiveDefinition definition) {
        require(definition.type()).validate(definition);
    }
}
