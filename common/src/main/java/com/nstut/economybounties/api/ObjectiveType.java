package com.nstut.economybounties.api;

/**
 * Extensible objective behavior. Implementations validate their definition and translate
 * matching gameplay events into positive progress deltas.
 */
public interface ObjectiveType {
    NamespacedId id();

    default void validate(ObjectiveDefinition definition) {
        if (!id().equals(definition.type())) {
            throw new IllegalArgumentException("Objective type mismatch: expected " + id() + ", got " + definition.type());
        }
    }

    /** Returns the progress delta caused by an event. Zero means no match. */
    long progressDelta(ObjectiveDefinition definition, ProgressEvent event);
}
