package com.nstut.economybounties.core;

import com.nstut.economybounties.api.BountyStateStore;
import com.nstut.economybounties.api.PlayerBountyStateSnapshot;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryBountyStateStore implements BountyStateStore {
    private final Map<UUID, PlayerBountyStateSnapshot> states = new ConcurrentHashMap<>();

    @Override
    public Optional<PlayerBountyStateSnapshot> load(UUID playerId) {
        return Optional.ofNullable(states.get(playerId));
    }

    @Override
    public void save(PlayerBountyStateSnapshot snapshot) {
        states.put(snapshot.playerId(), snapshot);
    }
}
