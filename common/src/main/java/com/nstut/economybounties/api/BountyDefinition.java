package com.nstut.economybounties.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record BountyDefinition(
        NamespacedId id,
        NamespacedId group,
        int minLevel,
        int maxLevel,
        int weight,
        List<ObjectiveDefinition> objectives,
        RewardDefinition reward,
        Duration offerDuration,
        Duration cooldown,
        Set<String> tags
) {
    public BountyDefinition {
        id = Objects.requireNonNull(id, "id");
        group = Objects.requireNonNull(group, "group");
        if (minLevel < 0 || maxLevel < minLevel) {
            throw new IllegalArgumentException("Invalid level range " + minLevel + ".." + maxLevel);
        }
        if (weight <= 0) throw new IllegalArgumentException("weight must be > 0");
        objectives = List.copyOf(Objects.requireNonNull(objectives, "objectives"));
        if (objectives.isEmpty()) throw new IllegalArgumentException("A bounty needs at least one objective");
        reward = Objects.requireNonNull(reward, "reward");
        offerDuration = requireNonNegative(offerDuration, "offerDuration");
        cooldown = requireNonNegative(cooldown, "cooldown");
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }

    public boolean supportsLevel(int level) {
        return level >= minLevel && level <= maxLevel;
    }

    private static Duration requireNonNegative(Duration value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }
}
