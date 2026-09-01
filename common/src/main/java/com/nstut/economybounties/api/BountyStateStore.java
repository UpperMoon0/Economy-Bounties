package com.nstut.economybounties.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence SPI. Loader modules can back this with SavedData/component storage while tests
 * and lightweight integrations may use an in-memory implementation.
 */
public interface BountyStateStore {
    Optional<PlayerBountyStateSnapshot> load(UUID playerId);

    void save(PlayerBountyStateSnapshot snapshot);
}
