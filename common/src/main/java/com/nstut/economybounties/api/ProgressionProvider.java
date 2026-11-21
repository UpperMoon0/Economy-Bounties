package com.nstut.economybounties.api;

import java.util.UUID;

@FunctionalInterface
public interface ProgressionProvider {
    /** Returns the effective progression level used to select bounties in the requested group. */
    int level(UUID playerId, NamespacedId group);

    static ProgressionProvider constant(int level) {
        if (level < 0) throw new IllegalArgumentException("level must be >= 0");
        return (player, group) -> level;
    }
}
