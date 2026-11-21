package com.nstut.economybounties.core;

import com.nstut.economybounties.api.BountyView;
import com.nstut.economybounties.api.NamespacedId;
import com.nstut.economybounties.api.PlayerBountyStateSnapshot;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class PlayerBountyState {
    private final UUID playerId;
    private final Map<UUID, BountyInstanceState> instances = new LinkedHashMap<>();
    private final ArrayDeque<NamespacedId> recent = new ArrayDeque<>();
    private final Map<NamespacedId, Instant> cooldowns = new LinkedHashMap<>();

    PlayerBountyState(UUID playerId) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
    }

    static PlayerBountyState fromSnapshot(PlayerBountyStateSnapshot snapshot) {
        PlayerBountyState state = new PlayerBountyState(snapshot.playerId());
        for (BountyView view : snapshot.bounties()) {
            state.instances.put(view.instanceId(), BountyInstanceState.fromView(view));
        }
        state.recent.addAll(snapshot.recentDefinitionIds());
        for (PlayerBountyStateSnapshot.Cooldown cooldown : snapshot.cooldowns()) {
            state.cooldowns.put(cooldown.definitionId(), cooldown.until());
        }
        return state;
    }

    UUID playerId() { return playerId; }
    Map<UUID, BountyInstanceState> instances() { return instances; }
    ArrayDeque<NamespacedId> recent() { return recent; }

    boolean onCooldown(NamespacedId definitionId, Instant now) {
        Instant until = cooldowns.get(definitionId);
        if (until == null) return false;
        if (!now.isBefore(until)) {
            cooldowns.remove(definitionId);
            return false;
        }
        return true;
    }

    void recordClaim(NamespacedId definitionId, Instant cooldownUntil, int historyLimit) {
        recent.remove(definitionId);
        recent.addFirst(definitionId);
        while (recent.size() > historyLimit) recent.removeLast();
        cooldowns.put(definitionId, cooldownUntil);
    }

    boolean expireAll(Instant now) {
        boolean changed = false;
        for (BountyInstanceState state : instances.values()) changed |= state.expire(now);
        return changed;
    }

    PlayerBountyStateSnapshot snapshot() {
        List<PlayerBountyStateSnapshot.Cooldown> cooldownSnapshots = cooldowns.entrySet().stream()
                .map(e -> new PlayerBountyStateSnapshot.Cooldown(e.getKey(), e.getValue()))
                .toList();
        return new PlayerBountyStateSnapshot(playerId,
                instances.values().stream().map(BountyInstanceState::view).toList(),
                new ArrayList<>(recent), cooldownSnapshots);
    }
}
