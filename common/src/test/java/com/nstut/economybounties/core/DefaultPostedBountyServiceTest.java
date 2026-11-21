package com.nstut.economybounties.core;

import com.nstut.economybounties.api.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DefaultPostedBountyServiceTest {
    private static final NamespacedId KILL = NamespacedId.parse("economy_bounties:kill");

    @Test
    void audienceAcceptanceProgressAndPayoutAreEnforced() {
        UUID creator = UUID.randomUUID();
        UUID allowed = UUID.randomUUID();
        UUID denied = UUID.randomUUID();
        ObjectiveRegistry registry = registry();
        FakeEscrow escrow = new FakeEscrow();
        AudienceProvider audienceProvider = new AudienceProvider() {
            @Override public boolean isGroupMember(UUID playerId, String groupId) { return playerId.equals(allowed) && groupId.equals("hunters"); }
            @Override public int progressionLevel(UUID playerId, NamespacedId group) { return playerId.equals(allowed) ? 7 : 3; }
        };
        DefaultPostedBountyService service = new DefaultPostedBountyService(audienceProvider, registry,
                new InMemoryPostedBountyStore(), escrow);
        BountyAudience audience = new BountyAudience(false, Set.of(), Set.of("hunters"), Set.of(denied),
                Optional.of(NamespacedId.parse("economy_bounties:hunting")), 5, 10);
        PostedBountyService.CreateResult created = service.create(creator,
                request(audience, Duration.ofHours(1)), Instant.parse("2026-09-02T00:00:00Z"));
        assertEquals(PostedBountyService.CreateResult.Status.CREATED, created.status());
        UUID id = created.bounty().bountyId();
        assertEquals(1, escrow.funds.size());

        assertEquals(PostedBountyService.ActionResult.Status.NOT_ALLOWED,
                service.accept(creator, id, Instant.parse("2026-09-02T00:01:00Z")).status());
        assertEquals(PostedBountyService.ActionResult.Status.NOT_ALLOWED,
                service.accept(denied, id, Instant.parse("2026-09-02T00:01:00Z")).status());
        assertEquals(PostedBountyService.ActionResult.Status.SUCCESS,
                service.accept(allowed, id, Instant.parse("2026-09-02T00:01:00Z")).status());

        service.recordProgress(new ProgressEvent(allowed, KILL, "minecraft:zombie", 2, Map.of()),
                Instant.parse("2026-09-02T00:02:00Z"));
        assertEquals(PostedBountyStatus.ACTIVE, service.get(id, Instant.parse("2026-09-02T00:02:00Z")).orElseThrow().status());
        service.recordProgress(new ProgressEvent(allowed, KILL, "minecraft:zombie", 1, Map.of()),
                Instant.parse("2026-09-02T00:03:00Z"));
        assertEquals(PostedBountyStatus.COMPLETED, service.get(id, Instant.parse("2026-09-02T00:03:00Z")).orElseThrow().status());
        assertEquals(PostedBountyService.ActionResult.Status.SUCCESS,
                service.claim(allowed, id, Instant.parse("2026-09-02T00:04:00Z")).status());
        assertEquals(PostedBountyStatus.CLAIMED, service.get(id, Instant.parse("2026-09-02T00:04:00Z")).orElseThrow().status());
        assertEquals(1, escrow.payouts.size());
    }

    @Test
    void openExpiryRefundsAndInFlightRefundRecoversIdempotently() {
        UUID creator = UUID.randomUUID();
        FakeEscrow escrow = new FakeEscrow();
        InMemoryPostedBountyStore store = new InMemoryPostedBountyStore();
        DefaultPostedBountyService service = new DefaultPostedBountyService(AudienceProvider.NONE, registry(), store, escrow);
        Instant start = Instant.parse("2026-09-02T00:00:00Z");
        UUID id = service.create(creator, request(BountyAudience.publicAudience(), Duration.ofMinutes(5)), start).bounty().bountyId();
        escrow.failNextRefund = true;
        PostedBountyView pending = service.get(id, start.plus(Duration.ofMinutes(6))).orElseThrow();
        assertEquals(PostedBountyStatus.EXPIRING, pending.status());
        DefaultPostedBountyService restarted = new DefaultPostedBountyService(AudienceProvider.NONE, registry(), store, escrow);
        restarted.recover(start.plus(Duration.ofMinutes(7)));
        assertEquals(PostedBountyStatus.EXPIRED, restarted.get(id, start.plus(Duration.ofMinutes(7))).orElseThrow().status());
        assertEquals(1, escrow.refunds.size());
    }

    private static PostedBountyService.CreateRequest request(BountyAudience audience, Duration lifetime) {
        return new PostedBountyService.CreateRequest("Zombie cleanup", "Kill three zombies", "minecraft:iron_sword",
                List.of(new ObjectiveDefinition(KILL, "minecraft:zombie", LongRange.fixed(3), Map.of())),
                new BigDecimal("25.00"), audience, lifetime);
    }

    private static ObjectiveRegistry registry() {
        ObjectiveRegistry registry = new ObjectiveRegistry();
        registry.register(new MatchingObjectiveType(KILL));
        return registry;
    }

    private static final class FakeEscrow implements EscrowProvider {
        final Set<UUID> funds = new HashSet<>();
        final Set<UUID> payouts = new HashSet<>();
        final Set<UUID> refunds = new HashSet<>();
        boolean failNextRefund;

        @Override public Result fund(UUID id, UUID creator, BigDecimal amount, Map<String, String> metadata) {
            funds.add(id); return Result.success("fund-" + id);
        }
        @Override public Result payout(UUID id, UUID claimant, BigDecimal amount, Map<String, String> metadata) {
            payouts.add(id); return Result.success("pay-" + id);
        }
        @Override public Result refund(UUID id, UUID creator, BigDecimal amount, Map<String, String> metadata) {
            if (failNextRefund) { failNextRefund = false; return Result.failure("retry"); }
            refunds.add(id); return Result.success("refund-" + id);
        }
    }
}
