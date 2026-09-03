package com.nstut.economybounties.board;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoardCodecTest {
    @Test
    void requestRoundTripPreservesCreateIntent() {
        BoardRequest original = BoardRequest.create(new BoardRequest.CreateDraft(
                "Iron delivery", "Bring the smith iron", "minecraft:iron_ingot", "125.50", 120,
                List.of(new BoardRequest.ObjectiveDraft("economy_bounties:deliver_item", "minecraft:iron_ingot", 32)),
                new BoardRequest.AudienceDraft(true, List.of(), List.of("team:smiths"), List.of(),
                        "economy_bounties:smithing", 3, 20)));

        BoardRequest decoded = BoardCodec.decodeRequest(BoardCodec.encodeRequest(original));

        assertEquals(original, decoded);
    }

    @Test
    void deliveryRoundTripPreservesExactContractAndObjective() {
        BoardRequest original = BoardRequest.deliver("posted", "00000000-0000-0000-0000-000000000001", 3,
                "economy_bounties:deliver_item", "minecraft:iron_ingot");

        BoardRequest decoded = BoardCodec.decodeRequest(BoardCodec.encodeRequest(original));

        assertEquals(original, decoded);
        assertEquals("posted", decoded.bountySource());
        assertEquals(3, decoded.objectiveIndex());
    }

    @Test
    void snapshotRoundTripPreservesActionCapabilities() {
        BoardSnapshot original = new BoardSnapshot(
                List.of(new BoardSnapshot.PoolEntry("economy_bounties:village")),
                List.of(new BoardSnapshot.BountyEntry(
                        "00000000-0000-0000-0000-000000000001", "generated", "economy_bounties:iron",
                        "Tier 2", "", "100", "ACTIVE", 1234,
                        List.of(new BoardSnapshot.ObjectiveEntry(
                                0, "economy_bounties:deliver_item", "minecraft:iron_ingot", 16, 5, true)),
                        false, true, false)),
                List.of(), "Delivered 5 items");

        BoardSnapshot decoded = BoardCodec.decodeSnapshot(BoardCodec.encodeSnapshot(original));

        assertEquals(original, decoded);
    }

    @Test
    void codecRejectsOversizedInboundPayloadBeforeParsing() {
        String oversized = "x".repeat(BoardCodec.MAX_JSON_CHARS + 1);
        assertThrows(IllegalArgumentException.class, () -> BoardCodec.decodeRequest(oversized));
        assertThrows(IllegalArgumentException.class, () -> BoardCodec.decodeSnapshot(oversized));
    }

    @Test
    void codecRejectsEmptyPayloads() {
        assertThrows(IllegalArgumentException.class, () -> BoardCodec.decodeRequest(""));
        assertThrows(IllegalArgumentException.class, () -> BoardCodec.decodeSnapshot(" "));
    }
}
