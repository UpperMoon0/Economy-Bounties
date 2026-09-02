package com.nstut.economybounties.core;

import com.nstut.economybounties.api.PostedBountyStore;
import com.nstut.economybounties.api.PostedBountyView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class InMemoryPostedBountyStore implements PostedBountyStore {
    private List<PostedBountyView> state = List.of();

    @Override public synchronized Collection<PostedBountyView> load() { return List.copyOf(state); }
    @Override public synchronized void save(Collection<PostedBountyView> bounties) { state = new ArrayList<>(bounties); }
}
