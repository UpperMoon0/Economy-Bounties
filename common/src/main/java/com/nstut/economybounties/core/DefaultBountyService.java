package com.nstut.economybounties.core;

import com.nstut.economybounties.api.BountyDefinition;
import com.nstut.economybounties.api.BountyService;
import com.nstut.economybounties.api.BountyStateStore;
import com.nstut.economybounties.api.BountyStatus;
import com.nstut.economybounties.api.BountyView;
import com.nstut.economybounties.api.NamespacedId;
import com.nstut.economybounties.api.ObjectiveDefinition;
import com.nstut.economybounties.api.ObjectiveRegistry;
import com.nstut.economybounties.api.PlayerBountyStateSnapshot;
import com.nstut.economybounties.api.ProgressEvent;
import com.nstut.economybounties.api.ProgressionProvider;
import com.nstut.economybounties.api.RewardProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Default loader-neutral implementation of the bounty service. */
public final class DefaultBountyService implements BountyService {
    private final BountyCatalog catalog = new BountyCatalog();
    private final ProgressionProvider progressionProvider;
    private final RewardProvider rewardProvider;
    private final ObjectiveRegistry objectiveRegistry;
    private final BountyStateStore stateStore;
    private final int historyLimit;
    private final Map<UUID, PlayerBountyState> states = new ConcurrentHashMap<>();

    public DefaultBountyService(
            ProgressionProvider progressionProvider,
            RewardProvider rewardProvider,
            ObjectiveRegistry objectiveRegistry,
            BountyStateStore stateStore,
            int historyLimit
    ) {
        this.progressionProvider = Objects.requireNonNull(progressionProvider, "progressionProvider");
        this.rewardProvider = Objects.requireNonNull(rewardProvider, "rewardProvider");
        this.objectiveRegistry = Objects.requireNonNull(objectiveRegistry, "objectiveRegistry");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        if (historyLimit < 0) throw new IllegalArgumentException("historyLimit must be >= 0");
        this.historyLimit = historyLimit;
    }

    @Override
    public synchronized void replaceDefinitions(Collection<BountyDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        for (BountyDefinition definition : definitions) {
            for (ObjectiveDefinition objective : definition.objectives()) {
                objectiveRegistry.validate(objective);
            }
        }
        catalog.replaceAll(definitions);
    }

    @Override
    public synchronized Optional<BountyView> rollOffer(UUID playerId, NamespacedId group, RollContext context) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(context, "context");

        PlayerBountyState playerState = state(playerId);
        boolean expired = playerState.expireAll(context.now());
        int level = progressionProvider.level(playerId, group);
        if (level < 0) throw new IllegalStateException("ProgressionProvider returned a negative level");

        List<BountyDefinition> candidates = catalog.eligible(group, level).stream()
                .filter(definition -> !playerState.onCooldown(definition.id(), context.now()))
                .toList();
        if (candidates.isEmpty()) {
            if (expired) save(playerState);
            return Optional.empty();
        }

        if (historyLimit > 0) {
            List<BountyDefinition> fresh = candidates.stream()
                    .filter(definition -> !playerState.recent().contains(definition.id()))
                    .toList();
            if (!fresh.isEmpty()) candidates = fresh;
        }

        long seed = DeterministicSeed.offer(context.worldSeed(), playerId, group,
                context.rotationEpoch(), context.rerollOrdinal());
        SplittableRandom random = new SplittableRandom(seed);
        BountyDefinition definition = weightedPick(candidates, random);
        UUID instanceId = DeterministicSeed.uuid(random);

        BountyInstanceState existing = playerState.instances().get(instanceId);
        if (existing != null) {
            if (expired) save(playerState);
            return Optional.of(existing.view());
        }

        List<BountyInstanceState.ObjectiveState> objectives = new ArrayList<>();
        for (ObjectiveDefinition objective : definition.objectives()) {
            objectives.add(new BountyInstanceState.ObjectiveState(objective, objective.amount().choose(random)));
        }

        BountyInstanceState instance = new BountyInstanceState(instanceId, playerId, definition,
                context.now(), context.now().plus(definition.offerDuration()),
                definition.reward().currency().choose(random), objectives, seed);
        playerState.instances().put(instanceId, instance);
        save(playerState);
        return Optional.of(instance.view());
    }

    @Override
    public synchronized Optional<BountyView> accept(UUID playerId, UUID instanceId, Instant now) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(now, "now");
        PlayerBountyState playerState = state(playerId);
        BountyInstanceState instance = playerState.instances().get(instanceId);
        if (instance == null) return Optional.empty();
        BountyStatus before = instance.status();
        instance.accept(now);
        if (before != instance.status()) save(playerState);
        return Optional.of(instance.view());
    }

    @Override
    public synchronized List<BountyView> recordProgress(ProgressEvent event, Instant now) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(now, "now");
        PlayerBountyState playerState = state(event.playerId());
        boolean dirty = playerState.expireAll(now);
        List<BountyView> changed = new ArrayList<>();
        for (BountyInstanceState instance : playerState.instances().values()) {
            if (instance.applyProgress(objectiveRegistry, event, now)) {
                dirty = true;
                changed.add(instance.view());
            }
        }
        if (dirty) save(playerState);
        return List.copyOf(changed);
    }

    @Override
    public synchronized ClaimResult claim(UUID playerId, UUID instanceId, Instant now) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(now, "now");
        PlayerBountyState playerState = state(playerId);
        boolean expired = playerState.expireAll(now);
        BountyInstanceState instance = playerState.instances().get(instanceId);
        if (instance == null) {
            if (expired) save(playerState);
            return new ClaimResult(ClaimResult.Status.NOT_FOUND, null, "Bounty instance not found");
        }
        if (instance.status() == BountyStatus.CLAIMED) {
            if (expired) save(playerState);
            return new ClaimResult(ClaimResult.Status.ALREADY_CLAIMED, instance.view(), "Reward already claimed");
        }
        if (instance.status() == BountyStatus.EXPIRED) {
            save(playerState);
            return new ClaimResult(ClaimResult.Status.EXPIRED, instance.view(), "Bounty expired before completion");
        }
        if (instance.status() != BountyStatus.COMPLETED) {
            if (expired) save(playerState);
            return new ClaimResult(ClaimResult.Status.NOT_COMPLETED, instance.view(), "Bounty is not completed");
        }

        Map<String, String> metadata = new LinkedHashMap<>(instance.definition().reward().metadata());
        metadata.put("economy_bounties:bounty_id", instance.definition().id().toString());
        metadata.put("economy_bounties:group", instance.definition().group().toString());
        metadata.put("economy_bounties:instance_id", instance.instanceId().toString());

        RewardProvider.PayoutResult payout;
        try {
            payout = rewardProvider.payout(new RewardProvider.RewardContext(instance.instanceId(), playerId,
                    instance.definition().id(), instance.rewardAmount(), metadata));
        } catch (RuntimeException error) {
            return new ClaimResult(ClaimResult.Status.PAYOUT_FAILED, instance.view(),
                    "Reward provider threw: " + error.getClass().getSimpleName());
        }

        if (!payout.success()) {
            return new ClaimResult(ClaimResult.Status.PAYOUT_FAILED, instance.view(), payout.message());
        }

        instance.markClaimed(payout.transactionId());
        playerState.recordClaim(instance.definition().id(), now.plus(instance.definition().cooldown()), historyLimit);
        save(playerState);
        return new ClaimResult(ClaimResult.Status.PAID, instance.view(), payout.message());
    }

    @Override
    public synchronized Optional<BountyView> get(UUID playerId, UUID instanceId, Instant now) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(now, "now");
        PlayerBountyState playerState = state(playerId);
        boolean dirty = playerState.expireAll(now);
        if (dirty) save(playerState);
        return Optional.ofNullable(playerState.instances().get(instanceId)).map(BountyInstanceState::view);
    }

    @Override
    public synchronized List<BountyView> list(UUID playerId, Instant now) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(now, "now");
        PlayerBountyState playerState = state(playerId);
        boolean dirty = playerState.expireAll(now);
        if (dirty) save(playerState);
        return playerState.instances().values().stream()
                .map(BountyInstanceState::view)
                .sorted(Comparator.comparing(BountyView::offeredAt).thenComparing(BountyView::instanceId))
                .toList();
    }

    private PlayerBountyState state(UUID playerId) {
        return states.computeIfAbsent(playerId, id -> stateStore.load(id)
                .map(snapshot -> checkedSnapshot(id, snapshot))
                .map(PlayerBountyState::fromSnapshot)
                .orElseGet(() -> new PlayerBountyState(id)));
    }

    private PlayerBountyStateSnapshot checkedSnapshot(UUID requestedPlayer, PlayerBountyStateSnapshot snapshot) {
        if (!requestedPlayer.equals(snapshot.playerId())) {
            throw new IllegalStateException("State store returned snapshot for wrong player");
        }
        return snapshot;
    }

    private void save(PlayerBountyState state) {
        stateStore.save(state.snapshot());
    }

    private static BountyDefinition weightedPick(List<BountyDefinition> candidates, SplittableRandom random) {
        long totalWeight = 0;
        for (BountyDefinition candidate : candidates) {
            totalWeight = Math.addExact(totalWeight, candidate.weight());
        }
        long roll = random.nextLong(totalWeight);
        for (BountyDefinition candidate : candidates) {
            if (roll < candidate.weight()) return candidate;
            roll -= candidate.weight();
        }
        throw new IllegalStateException("Weighted selection fell through");
    }
}
