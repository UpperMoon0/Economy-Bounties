package com.nstut.economybounties.core;

import com.nstut.economybounties.api.BountyDefinition;
import com.nstut.economybounties.api.BountyService;
import com.nstut.economybounties.api.BountyStatus;
import com.nstut.economybounties.api.BountyView;
import com.nstut.economybounties.api.BuiltinObjectiveTypes;
import com.nstut.economybounties.api.DecimalRange;
import com.nstut.economybounties.api.LongRange;
import com.nstut.economybounties.api.NamespacedId;
import com.nstut.economybounties.api.ObjectiveDefinition;
import com.nstut.economybounties.api.ObjectiveRegistry;
import com.nstut.economybounties.api.ProgressEvent;
import com.nstut.economybounties.api.ProgressionProvider;
import com.nstut.economybounties.api.RewardDefinition;
import com.nstut.economybounties.api.RewardProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultBountyServiceTest {
    private static final NamespacedId FARMING = NamespacedId.parse("test:farming");
    private static final UUID PLAYER = UUID.fromString("12345678-1234-5678-1234-567812345678");
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void sameRotationProducesSameOfferAcrossFreshServices() {
        BountyDefinition carrots = definition("test:carrots", 0, 20, 7, 48, 80, "160.00", "230.00", Duration.ZERO);
        BountyDefinition wheat = definition("test:wheat", 0, 20, 3, 32, 64, "120.00", "190.00", Duration.ZERO);

        DefaultBountyService first = service(ProgressionProvider.constant(12), context -> RewardProvider.PayoutResult.success("tx"), new InMemoryBountyStateStore());
        DefaultBountyService second = service(ProgressionProvider.constant(12), context -> RewardProvider.PayoutResult.success("tx"), new InMemoryBountyStateStore());
        first.replaceDefinitions(List.of(carrots, wheat));
        second.replaceDefinitions(List.of(carrots, wheat));

        BountyService.RollContext roll = new BountyService.RollContext(99L, 20260902L, 0, NOW);
        BountyView a = first.rollOffer(PLAYER, FARMING, roll).orElseThrow();
        BountyView b = second.rollOffer(PLAYER, FARMING, roll).orElseThrow();

        assertEquals(a.instanceId(), b.instanceId());
        assertEquals(a.definition().id(), b.definition().id());
        assertEquals(a.rewardAmount(), b.rewardAmount());
        assertEquals(a.objectives().get(0).requiredAmount(), b.objectives().get(0).requiredAmount());
    }

    @Test
    void progressionLevelFiltersDefinitionsBeforeRolling() {
        BountyDefinition novice = definition("test:novice", 0, 10, 100, 1, 1, "1.00", "1.00", Duration.ZERO);
        BountyDefinition expert = definition("test:expert", 20, 30, 1, 1, 1, "2.00", "2.00", Duration.ZERO);
        DefaultBountyService service = service(ProgressionProvider.constant(25), context -> RewardProvider.PayoutResult.success("tx"), new InMemoryBountyStateStore());
        service.replaceDefinitions(List.of(novice, expert));

        BountyView offer = service.rollOffer(PLAYER, FARMING, new BountyService.RollContext(1, 1, 0, NOW)).orElseThrow();
        assertEquals(NamespacedId.parse("test:expert"), offer.definition().id());
    }

    @Test
    void progressCompletesAndClaimPaysExactlyOnce() {
        AtomicInteger payouts = new AtomicInteger();
        RewardProvider reward = context -> {
            payouts.incrementAndGet();
            assertEquals(context.payoutKey(), context.payoutKey());
            assertEquals(new BigDecimal("10.00"), context.currencyAmount());
            assertEquals("test:carrots", context.metadata().get("economy_bounties:bounty_id"));
            return RewardProvider.PayoutResult.success("economy-tx-1");
        };
        DefaultBountyService service = service(ProgressionProvider.constant(5), reward, new InMemoryBountyStateStore());
        service.replaceDefinitions(List.of(definition("test:carrots", 0, 10, 1, 3, 3, "10.00", "10.00", Duration.ofHours(1))));

        BountyView offer = service.rollOffer(PLAYER, FARMING, new BountyService.RollContext(2, 3, 0, NOW)).orElseThrow();
        BountyView active = service.accept(PLAYER, offer.instanceId(), NOW).orElseThrow();
        assertEquals(BountyStatus.ACTIVE, active.status());

        service.recordProgress(event(2), NOW.plusSeconds(10));
        BountyView completed = service.recordProgress(event(1), NOW.plusSeconds(20)).get(0);
        assertEquals(BountyStatus.COMPLETED, completed.status());

        BountyService.ClaimResult paid = service.claim(PLAYER, offer.instanceId(), NOW.plusSeconds(30));
        assertEquals(BountyService.ClaimResult.Status.PAID, paid.status());
        assertEquals(BountyStatus.CLAIMED, paid.bounty().status());
        assertEquals("economy-tx-1", paid.bounty().payoutTransactionId());

        BountyService.ClaimResult duplicate = service.claim(PLAYER, offer.instanceId(), NOW.plusSeconds(31));
        assertEquals(BountyService.ClaimResult.Status.ALREADY_CLAIMED, duplicate.status());
        assertEquals(1, payouts.get());
    }

    @Test
    void failedPayoutLeavesCompletedBountyRetryable() {
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        RewardProvider reward = context -> {
            attempts.incrementAndGet();
            return fail.getAndSet(false)
                    ? RewardProvider.PayoutResult.failure("treasury unavailable")
                    : RewardProvider.PayoutResult.success("tx-retry");
        };
        DefaultBountyService service = service(ProgressionProvider.constant(1), reward, new InMemoryBountyStateStore());
        service.replaceDefinitions(List.of(definition("test:retry", 0, 10, 1, 1, 1, "5.00", "5.00", Duration.ZERO)));

        BountyView offer = service.rollOffer(PLAYER, FARMING, new BountyService.RollContext(4, 5, 0, NOW)).orElseThrow();
        service.accept(PLAYER, offer.instanceId(), NOW);
        service.recordProgress(event(1), NOW.plusSeconds(1));

        assertEquals(BountyService.ClaimResult.Status.PAYOUT_FAILED, service.claim(PLAYER, offer.instanceId(), NOW.plusSeconds(2)).status());
        assertEquals(BountyStatus.COMPLETED, service.get(PLAYER, offer.instanceId(), NOW.plusSeconds(3)).orElseThrow().status());
        assertEquals(BountyService.ClaimResult.Status.PAID, service.claim(PLAYER, offer.instanceId(), NOW.plusSeconds(4)).status());
        assertEquals(2, attempts.get());
    }

    @Test
    void claimedDefinitionIsSuppressedByHistoryAndCooldown() {
        BountyDefinition first = definition("test:first", 0, 10, 1, 1, 1, "1.00", "1.00", Duration.ofDays(1));
        BountyDefinition second = definition("test:second", 0, 10, 1, 1, 1, "1.00", "1.00", Duration.ZERO);
        DefaultBountyService service = service(ProgressionProvider.constant(1), context -> RewardProvider.PayoutResult.success("tx"), new InMemoryBountyStateStore());
        service.replaceDefinitions(List.of(first, second));

        BountyView offer = service.rollOffer(PLAYER, FARMING, new BountyService.RollContext(100, 1, 0, NOW)).orElseThrow();
        service.accept(PLAYER, offer.instanceId(), NOW);
        service.recordProgress(event(1), NOW.plusSeconds(1));
        service.claim(PLAYER, offer.instanceId(), NOW.plusSeconds(2));

        BountyView next = service.rollOffer(PLAYER, FARMING, new BountyService.RollContext(100, 2, 0, NOW.plusSeconds(3))).orElseThrow();
        assertNotEquals(offer.definition().id(), next.definition().id());
    }

    @Test
    void stateSurvivesServiceRecreation() {
        InMemoryBountyStateStore store = new InMemoryBountyStateStore();
        BountyDefinition definition = definition("test:persist", 0, 10, 1, 4, 4, "7.00", "7.00", Duration.ZERO);
        DefaultBountyService before = service(ProgressionProvider.constant(1), context -> RewardProvider.PayoutResult.success("tx"), store);
        before.replaceDefinitions(List.of(definition));
        BountyView offer = before.rollOffer(PLAYER, FARMING, new BountyService.RollContext(7, 8, 0, NOW)).orElseThrow();
        before.accept(PLAYER, offer.instanceId(), NOW);
        before.recordProgress(event(2), NOW.plusSeconds(1));

        DefaultBountyService after = service(ProgressionProvider.constant(1), context -> RewardProvider.PayoutResult.success("tx"), store);
        after.replaceDefinitions(List.of(definition));
        BountyView restored = after.get(PLAYER, offer.instanceId(), NOW.plusSeconds(2)).orElseThrow();
        assertEquals(BountyStatus.ACTIVE, restored.status());
        assertEquals(2, restored.objectives().get(0).progress());
    }

    private static DefaultBountyService service(ProgressionProvider progression, RewardProvider reward, InMemoryBountyStateStore store) {
        ObjectiveRegistry registry = new ObjectiveRegistry();
        BuiltinObjectiveTypes.registerAll(registry);
        return new DefaultBountyService(progression, reward, registry, store, 8);
    }

    private static BountyDefinition definition(String id, int minLevel, int maxLevel, int weight,
                                               long minAmount, long maxAmount, String minReward, String maxReward,
                                               Duration cooldown) {
        return new BountyDefinition(NamespacedId.parse(id), FARMING, minLevel, maxLevel, weight,
                List.of(new ObjectiveDefinition(BuiltinObjectiveTypes.DELIVER_ITEM, "minecraft:carrot",
                        new LongRange(minAmount, maxAmount), Map.of())),
                new RewardDefinition(new DecimalRange(new BigDecimal(minReward), new BigDecimal(maxReward)), Map.of()),
                Duration.ofMinutes(30), cooldown, Set.of("test"));
    }

    private static ProgressEvent event(long amount) {
        return new ProgressEvent(PLAYER, BuiltinObjectiveTypes.DELIVER_ITEM, "minecraft:carrot", amount, Map.of());
    }
}
