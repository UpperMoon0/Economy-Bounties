package com.nstut.economybounties.api;

import java.util.UUID;

/** Resolves server-defined audience groups and progression for posted bounties. */
public interface AudienceProvider {
    boolean isGroupMember(UUID playerId, String groupId);

    int progressionLevel(UUID playerId, NamespacedId progressionGroup);

    AudienceProvider NONE = new AudienceProvider() {
        @Override public boolean isGroupMember(UUID playerId, String groupId) { return false; }
        @Override public int progressionLevel(UUID playerId, NamespacedId progressionGroup) { return 0; }
    };
}
