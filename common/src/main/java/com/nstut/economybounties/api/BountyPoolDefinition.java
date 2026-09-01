package com.nstut.economybounties.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A weighted set of bounty groups used by boards, guilds, NPCs or rotations. */
public record BountyPoolDefinition(
        NamespacedId id,
        List<GroupEntry> groups
) {
    public BountyPoolDefinition {
        id = Objects.requireNonNull(id, "id");
        groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
        if (groups.isEmpty()) throw new IllegalArgumentException("A bounty pool needs at least one group");
        Set<NamespacedId> seen = new HashSet<>();
        for (GroupEntry entry : groups) {
            if (!seen.add(entry.group())) {
                throw new IllegalArgumentException("Duplicate group in pool " + id + ": " + entry.group());
            }
        }
    }

    public record GroupEntry(NamespacedId group, int weight) {
        public GroupEntry {
            group = Objects.requireNonNull(group, "group");
            if (weight <= 0) throw new IllegalArgumentException("group weight must be > 0");
        }
    }
}
