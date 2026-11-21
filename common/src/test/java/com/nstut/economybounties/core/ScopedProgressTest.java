package com.nstut.economybounties.core;

import com.nstut.economybounties.api.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScopedProgressTest {
    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CREATOR = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");
    private static final NamespacedId GROUP = NamespacedId.parse("test:delivery");

    @Test
    void scopedGeneratedDeliveryOnlyAdvancesExactBountyAndObjective() {
        ObjectiveRegistry registry = registry();
        DefaultBountyService service = new DefaultBountyService(ProgressionProvider.constant(1),
                context -> RewardProvider.PayoutResult.success("tx"), registry, new InMemoryBountyStateStore(), 0);
        service.replaceDefinitions(List.of(new BountyDefinition(
                NamespacedId.parse("test:iron"), GROUP, 1, 0, 10, 1,
                List.of(
                        delivery("minecraft:iron_ingot", 5),
                        delivery("minecraft:iron_ingot", 5)),
                new RewardDefinition(new DecimalRange(BigDecimal.ONE, BigDecimal.ONE), Map.of()),
                Duration.ofMinutes(30), Duration.ZERO, Set.of())));

        BountyView first = service.rollOffer(PLAYER, GROUP, new BountyService.RollContext(1, 1, 0, NOW)).orElseThrow();
        BountyView second = service.rollOffer(PLAYER, GROUP, new BountyService.RollContext(1, 1, 1, NOW)).orElseThrow();
        service.accept(PLAYER, first.instanceId(), NOW);
        service.accept(PLAYER, second.instanceId(), NOW);

        service.recordProgress(new ProgressEvent(PLAYER, BuiltinObjectiveTypes.DELIVER_ITEM, "minecraft:iron_ingot", 2,
                ProgressScope.metadata("generated", first.instanceId(), 1, Map.of())), NOW.plusSeconds(1));

        BountyView firstAfter = service.get(PLAYER, first.instanceId(), NOW.plusSeconds(2)).orElseThrow();
        BountyView secondAfter = service.get(PLAYER, second.instanceId(), NOW.plusSeconds(2)).orElseThrow();
        assertEquals(0, firstAfter.objectives().get(0).progress());
        assertEquals(2, firstAfter.objectives().get(1).progress());
        assertEquals(0, secondAfter.objectives().get(0).progress());
        assertEquals(0, secondAfter.objectives().get(1).progress());

        service.recordProgress(new ProgressEvent(PLAYER, BuiltinObjectiveTypes.DELIVER_ITEM,
                "minecraft:iron_ingot", 1, Map.of()), NOW.plusSeconds(3));
        BountyView unscopedFirst = service.get(PLAYER, first.instanceId(), NOW.plusSeconds(4)).orElseThrow();
        BountyView unscopedSecond = service.get(PLAYER, second.instanceId(), NOW.plusSeconds(4)).orElseThrow();
        assertEquals(1, unscopedFirst.objectives().get(0).progress());
        assertEquals(3, unscopedFirst.objectives().get(1).progress());
        assertEquals(1, unscopedSecond.objectives().get(0).progress());
        assertEquals(1, unscopedSecond.objectives().get(1).progress());
    }

    @Test
    void scopedPostedDeliveryOnlyAdvancesExactBountyAndObjective() {
        DefaultPostedBountyService service = new DefaultPostedBountyService(AudienceProvider.NONE, registry(), emptyStore(), escrow());
        PostedBountyService.CreateRequest request = new PostedBountyService.CreateRequest(
                "Iron delivery", "", "minecraft:iron_ingot",
                List.of(delivery("minecraft:iron_ingot", 5), delivery("minecraft:iron_ingot", 5)),
                BigDecimal.TEN, BountyAudience.publicAudience(), Duration.ofHours(1));

        PostedBountyView first = service.create(CREATOR, request, NOW).bounty();
        PostedBountyView second = service.create(CREATOR, request, NOW.plusSeconds(1)).bounty();
        service.accept(PLAYER, first.bountyId(), NOW.plusSeconds(2));
        service.accept(PLAYER, second.bountyId(), NOW.plusSeconds(2));

        service.recordProgress(new ProgressEvent(PLAYER, BuiltinObjectiveTypes.DELIVER_ITEM, "minecraft:iron_ingot", 2,
                ProgressScope.metadata("posted", first.bountyId(), 1, Map.of())), NOW.plusSeconds(3));

        PostedBountyView firstAfter = service.get(first.bountyId(), NOW.plusSeconds(4)).orElseThrow();
        PostedBountyView secondAfter = service.get(second.bountyId(), NOW.plusSeconds(4)).orElseThrow();
        assertEquals(0, firstAfter.objectives().get(0).progress());
        assertEquals(2, firstAfter.objectives().get(1).progress());
        assertEquals(0, secondAfter.objectives().get(0).progress());
        assertEquals(0, secondAfter.objectives().get(1).progress());
    }

    private static ObjectiveRegistry registry() {
        ObjectiveRegistry registry = new ObjectiveRegistry();
        BuiltinObjectiveTypes.registerAll(registry);
        return registry;
    }

    private static ObjectiveDefinition delivery(String target, long amount) {
        return new ObjectiveDefinition(BuiltinObjectiveTypes.DELIVER_ITEM, target, new LongRange(amount, amount), Map.of());
    }

    private static PostedBountyStore emptyStore() {
        return new PostedBountyStore() {
            @Override public Collection<PostedBountyView> load() { return List.of(); }
            @Override public void save(Collection<PostedBountyView> bounties) { }
        };
    }

    private static EscrowProvider escrow() {
        return new EscrowProvider() {
            @Override public Result fund(UUID bountyId, UUID creatorId, BigDecimal amount, Map<String, String> metadata) {
                return Result.success("fund-" + bountyId);
            }
            @Override public Result payout(UUID bountyId, UUID claimantId, BigDecimal amount, Map<String, String> metadata) {
                return Result.success("pay-" + bountyId);
            }
            @Override public Result refund(UUID bountyId, UUID creatorId, BigDecimal amount, Map<String, String> metadata) {
                return Result.success("refund-" + bountyId);
            }
        };
    }
}
