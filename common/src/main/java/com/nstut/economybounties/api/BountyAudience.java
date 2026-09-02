package com.nstut.economybounties.api;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Immutable eligibility policy for a player-posted bounty. */
public record BountyAudience(
        boolean publicAccess,
        Set<UUID> allowedPlayers,
        Set<String> allowedGroups,
        Set<UUID> deniedPlayers,
        Optional<NamespacedId> progressionGroup,
        int minLevel,
        int maxLevel
) {
    public BountyAudience {
        allowedPlayers = allowedPlayers == null ? Set.of() : Set.copyOf(allowedPlayers);
        allowedGroups = allowedGroups == null ? Set.of() : Set.copyOf(allowedGroups);
        deniedPlayers = deniedPlayers == null ? Set.of() : Set.copyOf(deniedPlayers);
        progressionGroup = progressionGroup == null ? Optional.empty() : progressionGroup;
        if (allowedGroups.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("allowedGroups cannot contain blank values");
        }
        if (minLevel < 0 || maxLevel < minLevel) {
            throw new IllegalArgumentException("Invalid audience level range " + minLevel + ".." + maxLevel);
        }
        if (!publicAccess && allowedPlayers.isEmpty() && allowedGroups.isEmpty() && progressionGroup.isEmpty()) {
            throw new IllegalArgumentException("Restricted audience must name players, groups, or a progression range");
        }
    }

    public static BountyAudience publicAudience() {
        return new BountyAudience(true, Set.of(), Set.of(), Set.of(), Optional.empty(), 0, Integer.MAX_VALUE);
    }

    public boolean isEligible(UUID playerId, AudienceProvider provider) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(provider, "provider");
        if (deniedPlayers.contains(playerId)) return false;

        boolean identityMatch = publicAccess || allowedPlayers.contains(playerId)
                || allowedGroups.stream().anyMatch(group -> provider.isGroupMember(playerId, group));
        // A progression-only audience is intentionally valid.
        if (!identityMatch && progressionGroup.isEmpty()) return false;

        if (progressionGroup.isPresent()) {
            int level = provider.progressionLevel(playerId, progressionGroup.get());
            if (level < 0) throw new IllegalStateException("AudienceProvider returned a negative level");
            if (level < minLevel || level > maxLevel) return false;
        }
        return identityMatch || progressionGroup.isPresent();
    }
}
