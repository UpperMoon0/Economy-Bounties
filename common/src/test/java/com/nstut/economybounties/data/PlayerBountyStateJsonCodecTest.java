package com.nstut.economybounties.data;

import com.nstut.economybounties.api.*;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerBountyStateJsonCodecTest {
    @Test
    void roundTripsGeneratedPlayerState() {
        UUID player = UUID.randomUUID();
        NamespacedId id = NamespacedId.parse("example:test");
        ObjectiveDefinition objective = new ObjectiveDefinition(BuiltinObjectiveTypes.KILL_ENTITY, "minecraft:zombie", LongRange.fixed(4), Map.of());
        BountyDefinition definition = new BountyDefinition(id, NamespacedId.parse("example:hunting"), 2, 0, 10, 1,
                List.of(objective), new RewardDefinition(DecimalRange.fixed(new BigDecimal("12.50")), Map.of()),
                Duration.ofMinutes(30), Duration.ofHours(1), Set.of("test"));
        Instant now = Instant.parse("2026-09-02T00:00:00Z");
        BountyView view = new BountyView(UUID.randomUUID(), player, definition, now, now.plus(Duration.ofMinutes(30)),
                new BigDecimal("12.50"), List.of(new BountyObjectiveView(objective, 4, 2)), BountyStatus.ACTIVE, 42L, "");
        PlayerBountyStateSnapshot snapshot = new PlayerBountyStateSnapshot(player, List.of(view), List.of(id),
                List.of(new PlayerBountyStateSnapshot.Cooldown(id, now.plus(Duration.ofHours(1)))));
        PlayerBountyStateJsonCodec codec = new PlayerBountyStateJsonCodec();
        PlayerBountyStateSnapshot decoded = codec.decode(new StringReader(codec.encode(snapshot)));
        assertEquals(snapshot, decoded);
    }
}
