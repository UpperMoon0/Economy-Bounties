package com.nstut.economybounties.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable persistence boundary for one player's bounty state. */
public record PlayerBountyStateSnapshot(
        UUID playerId,
        List<BountyView> bounties,
        List<NamespacedId> recentDefinitionIds,
        List<Cooldown> cooldowns
) {
    public PlayerBountyStateSnapshot {
        playerId = Objects.requireNonNull(playerId, "playerId");
        bounties = List.copyOf(Objects.requireNonNull(bounties, "bounties"));
        recentDefinitionIds = List.copyOf(Objects.requireNonNull(recentDefinitionIds, "recentDefinitionIds"));
        cooldowns = List.copyOf(Objects.requireNonNull(cooldowns, "cooldowns"));
    }

    public record Cooldown(NamespacedId definitionId, Instant until) {
        public Cooldown {
            definitionId = Objects.requireNonNull(definitionId, "definitionId");
            until = Objects.requireNonNull(until, "until");
        }
    }
}
