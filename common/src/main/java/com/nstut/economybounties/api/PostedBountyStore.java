package com.nstut.economybounties.api;

import java.util.Collection;

/** Durable server-global store for player-created bounties. */
public interface PostedBountyStore {
    Collection<PostedBountyView> load();
    void save(Collection<PostedBountyView> bounties);
}
