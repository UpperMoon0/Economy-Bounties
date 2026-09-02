package com.nstut.economybounties.board;

import java.util.List;
import java.util.Objects;

/** Version-neutral server snapshot consumed by the OpenUI bounty board. */
public record BoardSnapshot(
        List<PoolEntry> pools,
        List<BountyEntry> generated,
        List<BountyEntry> posted,
        String notice
) {
    public BoardSnapshot {
        pools = List.copyOf(pools == null ? List.of() : pools);
        generated = List.copyOf(generated == null ? List.of() : generated);
        posted = List.copyOf(posted == null ? List.of() : posted);
        notice = notice == null ? "" : notice;
    }

    public record PoolEntry(String id) {
        public PoolEntry { id = requireText(id, "id"); }
    }

    public record BountyEntry(
            String id,
            String source,
            String title,
            String subtitle,
            String description,
            String reward,
            String status,
            long expiresAtEpochSecond,
            List<ObjectiveEntry> objectives,
            boolean canAccept,
            boolean canCancel,
            boolean canClaim
    ) {
        public BountyEntry {
            id = requireText(id, "id");
            source = requireText(source, "source");
            title = requireText(title, "title");
            subtitle = subtitle == null ? "" : subtitle;
            description = description == null ? "" : description;
            reward = reward == null ? "0" : reward;
            status = requireText(status, "status");
            objectives = List.copyOf(objectives == null ? List.of() : objectives);
        }
    }

    public record ObjectiveEntry(
            String type,
            String target,
            long targetAmount,
            long progress,
            boolean deliverable
    ) {
        public ObjectiveEntry {
            type = requireText(type, "type");
            target = requireText(target, "target");
            if (targetAmount < 0 || progress < 0) throw new IllegalArgumentException("objective amounts must be >= 0");
        }
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
