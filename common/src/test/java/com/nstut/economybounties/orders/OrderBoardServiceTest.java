package com.nstut.economybounties.orders;

import com.nstut.economybounties.api.*;
import com.nstut.economybounties.core.DefaultBountyService;
import com.nstut.economybounties.data.JsonDirectoryBountyStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OrderBoardServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final UUID A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    @TempDir Path temp;

    private static OrderBoardDefinition config(OrderBoardDefinition.Scope scope,
                                              OrderBoardDefinition.Timer timer, int slots) {
        return new OrderBoardDefinition(NamespacedId.parse("economy_bounties:test_orders"),
                scope, slots, Duration.ofSeconds(5), Duration.ofHours(1),
                true, 0, 253402300799L, new BigDecimal("5.00"), new BigDecimal("100.00"),
                List.of(new OrderBoardDefinition.Input("minecraft:wheat", LongRange.fixed(3),
                        1,new BigDecimal("2.00"),0)),
                List.of(new OrderBoardDefinition.Prize("standard","Farmer dispatch","Common",1,
                        new BigDecimal("2.00"),new BigDecimal("1.00"),
                        timer,Duration.ofSeconds(120),0,0)));
    }
    private static DefaultBountyService service(Path dir, AtomicInteger payouts) {
        ObjectiveRegistry reg = new ObjectiveRegistry();
        BuiltinObjectiveTypes.registerAll(reg);
        return new DefaultBountyService(ProgressionProvider.constant(0),
                context -> {
                    payouts.incrementAndGet();
                    return RewardProvider.PayoutResult.success("payment-" + context.payoutKey());
                },reg,new JsonDirectoryBountyStateStore(dir.resolve("players")),8);
    }

    @Test
    void allBundledOrderPoolsDecodeAndHaveSafeBounds() throws Exception {
        for(String file : List.of("farm_orders","town_dispatch","daily_special","harvest_festival")) {
            try(var raw = getClass().getResourceAsStream("/data/economy_bounties/order_boards/" + file + ".json")) {
                assertNotNull(raw,"Missing bundled board " + file);
                var board = new OrderBoardJsonCodec().decode(new InputStreamReader(raw, StandardCharsets.UTF_8));
                assertFalse(board.inputs().isEmpty());
                assertFalse(board.prizes().isEmpty());
                assertTrue(board.slots() >= 1 && board.slots() <= 12);
                assertTrue(board.maxReward().compareTo(new BigDecimal("1000000.00")) <= 0);
            }
        }
    }

    @Test
    void personalBoardPrefillsSixSlotsWithoutRollsAndPersistsAcrossRestart() {
        OrderBoardDefinition board = config(OrderBoardDefinition.Scope.PERSONAL,OrderBoardDefinition.Timer.NONE,6);
        var s = new OrderBoardService(temp,42,service(temp,new AtomicInteger()));
        s.replaceDefinitions(List.of(board));
        var first = s.list(A,0,NOW);
        assertEquals(6,first.size());
        assertTrue(first.stream().allMatch(OrderBoardService.Card::canAccept));
        assertEquals(first,s.list(A,0,NOW.plusSeconds(30)));
        var again = new OrderBoardService(temp,42,service(temp,new AtomicInteger()));
        again.replaceDefinitions(List.of(board));
        assertEquals(first,again.list(A,0,NOW.plusSeconds(30)));
        assertNotEquals(first.get(0).offerId(),s.list(B,0,NOW).get(0).offerId());
    }

    @Test
    void acceptanceIsServerAuthoritativeAndPayoutCannotDuplicate() {
        AtomicInteger payouts = new AtomicInteger();
        DefaultBountyService generated = service(temp,payouts);
        var board = config(OrderBoardDefinition.Scope.PERSONAL,OrderBoardDefinition.Timer.ACCEPT,1);
        var orders = new OrderBoardService(temp,42,generated);
        orders.replaceDefinitions(List.of(board));
        var card = orders.list(A,0,NOW).get(0);
        assertEquals("13.00",card.reward()); // 3 wheat * 2.00 * 2 + 1 bonus
        assertTrue(orders.accept(A,0,board.id(),0,UUID.fromString(card.offerId()),NOW).contains("accepted"));
        assertTrue(orders.accept(A,0,board.id(),0,UUID.fromString(card.offerId()),NOW).contains("already"));
        assertEquals(1,generated.list(A,NOW).size());
        var generatedBounty = generated.list(A,NOW).get(0);
        assertEquals(BountyStatus.ACTIVE,generatedBounty.status());
        assertEquals(NOW.plusSeconds(120),generatedBounty.expiresAt());
        assertEquals("minecraft:wheat",generatedBounty.objectives().get(0).definition().target());
        generated.recordProgress(new ProgressEvent(A,BuiltinObjectiveTypes.DELIVER_ITEM,
                "minecraft:wheat",3,Map.of()),NOW.plusSeconds(10));
        assertEquals(BountyStatus.COMPLETED,generated.get(A,generatedBounty.instanceId(),NOW.plusSeconds(11)).orElseThrow().status());
        assertEquals(BountyService.ClaimResult.Status.PAID,
                generated.claim(A,generatedBounty.instanceId(),NOW.plusSeconds(12)).status());
        assertEquals(BountyService.ClaimResult.Status.ALREADY_CLAIMED,
                generated.claim(A,generatedBounty.instanceId(),NOW.plusSeconds(13)).status());
        assertEquals(1,payouts.get());
        assertEquals("RESTOCKING", orders.list(A,0,NOW.plusSeconds(14)).get(0).status());
        assertTrue(orders.list(A,0,NOW.plusSeconds(20)).get(0).canAccept());
    }

    @Test
    void sharedBoardClaimIsExclusiveAndStaleClicksAreRejected() {
        var board = config(OrderBoardDefinition.Scope.SHARED,OrderBoardDefinition.Timer.OFFER,1);
        var generated = service(temp,new AtomicInteger());
        var manager = new OrderBoardService(temp,1234,generated);
        manager.replaceDefinitions(List.of(board));
        var first = manager.list(A,0,NOW).get(0);
        assertEquals(first.offerId(),manager.list(B,0,NOW).get(0).offerId());
        assertTrue(manager.accept(A,0,board.id(),0,UUID.fromString(first.offerId()),NOW).contains("accepted"));
        assertTrue(manager.accept(B,0,board.id(),0,UUID.fromString(first.offerId()),NOW).contains("Another player"));
        assertEquals(0,generated.list(B,NOW).size());
        assertEquals(NOW.plusSeconds(120),generated.list(A,NOW).get(0).expiresAt());
        // Acceptance does not refill a slot while the contract is unfinished.
        assertEquals("CLAIMED",manager.list(B,0,NOW.plusSeconds(10)).get(0).status());
        generated.recordProgress(new ProgressEvent(A,BuiltinObjectiveTypes.DELIVER_ITEM,
                "minecraft:wheat",3,Map.of()),NOW.plusSeconds(11));
        var instance = generated.list(A,NOW.plusSeconds(11)).get(0).instanceId();
        assertEquals(BountyService.ClaimResult.Status.PAID,
                generated.claim(A,instance,NOW.plusSeconds(12)).status());
        assertEquals("RESTOCKING",manager.list(B,0,NOW.plusSeconds(13)).get(0).status());
        var rotated = manager.list(B,0,NOW.plusSeconds(19)).get(0);
        assertNotEquals(first.offerId(),rotated.offerId());
        assertTrue(manager.accept(B,0,board.id(),0,UUID.fromString(first.offerId()),NOW.plusSeconds(19)).contains("rotated"));
    }

    @Test
    void discardEnforcesCooldownAndPreventsRerollSpamming() {
        var board = config(OrderBoardDefinition.Scope.PERSONAL,OrderBoardDefinition.Timer.NONE,1);
        var manager = new OrderBoardService(temp,100,service(temp,new AtomicInteger()));
        manager.replaceDefinitions(List.of(board));
        var first = manager.list(A,0,NOW).get(0);
        assertTrue(manager.discard(A,0,board.id(),0,UUID.fromString(first.offerId()),NOW).contains("discarded"));
        var waiting=manager.list(A,0,NOW.plusSeconds(3)).get(0);
        assertFalse(waiting.canAccept());
        assertEquals("RESTOCKING",waiting.status());
        var second=manager.list(A,0,NOW.plusSeconds(5)).get(0);
        assertTrue(second.canAccept());
        assertNotEquals(first.offerId(),second.offerId());
    }

    @Test
    void excessivePriceProviderIsCappedByReferenceValue() {
        var board = config(OrderBoardDefinition.Scope.PERSONAL,OrderBoardDefinition.Timer.NONE,1);
        var manager = new OrderBoardService(temp,123,service(temp,new AtomicInteger()),
                ignored -> new BigDecimal("100000.00"));
        manager.replaceDefinitions(List.of(board));
        assertEquals("13.00",manager.list(A,0,NOW).get(0).reward());
    }

    @Test
    void marketPriceQuotesAreClampedBeforeMintingRewards() {
        var board = config(OrderBoardDefinition.Scope.PERSONAL,OrderBoardDefinition.Timer.NONE,1);
        var manager = new OrderBoardService(temp,123,service(temp,new AtomicInteger()),
                ignored -> new BigDecimal("8.00"));
        manager.replaceDefinitions(List.of(board));
        // 3 wheat x capped 4.00 market price x 2 + 1.00 bonus
        assertEquals("25.00",manager.list(A,0,NOW).get(0).reward());
    }

    @Test
    void crashedPendingGrantIsReplayedForTheOriginalClaimantOnly() {
        var board = config(OrderBoardDefinition.Scope.SHARED,OrderBoardDefinition.Timer.ACCEPT,1);
        ObjectiveRegistry invalidRegistry = new ObjectiveRegistry();
        DefaultBountyService unavailable = new DefaultBountyService(ProgressionProvider.constant(0),
                context -> RewardProvider.PayoutResult.success("unused"), invalidRegistry,
                new JsonDirectoryBountyStateStore(temp.resolve("players")),8);
        var before = new OrderBoardService(temp,42,unavailable);
        before.replaceDefinitions(List.of(board));
        var preview = before.list(A,0,NOW).get(0);
        assertTrue(before.accept(A,0,board.id(),0,UUID.fromString(preview.offerId()),NOW)
                .contains("pending"));
        assertTrue(before.accept(B,0,board.id(),0,UUID.fromString(preview.offerId()),NOW)
                .contains("Another player"));
        var recovered = service(temp,new AtomicInteger());
        var after = new OrderBoardService(temp,42,recovered);
        after.replaceDefinitions(List.of(board));
        assertFalse(after.list(A,0,NOW.plusSeconds(10)).get(0).canAccept());
        assertEquals(1,recovered.list(A,NOW.plusSeconds(10)).size());
        assertEquals(0,recovered.list(B,NOW.plusSeconds(10)).size());
    }

    @Test
    void expiredPendingRushClaimIsReleasedAfterRestock() {
        var board = config(OrderBoardDefinition.Scope.SHARED,OrderBoardDefinition.Timer.OFFER,1);
        var unavailable = new DefaultBountyService(ProgressionProvider.constant(0),
                context -> RewardProvider.PayoutResult.success("unused"), new ObjectiveRegistry(),
                new JsonDirectoryBountyStateStore(temp.resolve("players")),8);
        var manager = new OrderBoardService(temp,42,unavailable);
        manager.replaceDefinitions(List.of(board));
        var preview = manager.list(A,0,NOW).get(0);
        assertTrue(manager.accept(A,0,board.id(),0,UUID.fromString(preview.offerId()),NOW).contains("pending"));
        assertEquals("RESTOCKING",manager.list(B,0,NOW.plusSeconds(121)).get(0).status());
        var refreshed = manager.list(B,0,NOW.plusSeconds(126)).get(0);
        assertTrue(refreshed.canAccept());
        assertNotEquals(preview.offerId(),refreshed.offerId());
    }

    @Test
    void eventWindowIsEnforcedWithoutDeletingExistingState() {
        var earlier = config(OrderBoardDefinition.Scope.PERSONAL,OrderBoardDefinition.Timer.NONE,1);
        var event = new OrderBoardDefinition(earlier.id(),earlier.scope(),earlier.slots(),
                earlier.restock(),earlier.rotation(),true,NOW.plusSeconds(100).getEpochSecond(),
                NOW.plusSeconds(300).getEpochSecond(),earlier.minReward(),earlier.maxReward(),
                earlier.inputs(),earlier.prizes());
        var manager = new OrderBoardService(temp,5,service(temp,new AtomicInteger()));
        manager.replaceDefinitions(List.of(event));
        assertTrue(manager.list(A,0,NOW).isEmpty());
        assertEquals(1,manager.list(A,0,NOW.plusSeconds(100)).size());
        assertTrue(manager.list(A,0,NOW.plusSeconds(301)).isEmpty());
    }
}
