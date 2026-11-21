package com.nstut.economybounties.board;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoardActionPolicyTest {
    @Test
    void legacyClientCannotTriggerNewSystemBounties() {
        // Old clients or custom packets may still carry the deprecated action.
        BoardRequest legacy = BoardCodec.decodeRequest(
                "{\"action\":\"ROLL\",\"poolId\":\"economy_bounties:example_board\"}");
        assertEquals(BoardRequest.Action.ROLL, legacy.action());
        assertFalse(BoardActionPolicy.allowFromPlayer(legacy.action()));
        assertFalse(BoardActionPolicy.allowFromPlayer(null));
        assertTrue(BoardActionPolicy.manualRollNotice().contains("disabled"));
    }

    @Test
    void existingPlayerActionsRemainSupported() {
        for (BoardRequest.Action action : BoardRequest.Action.values()) {
            if (action != BoardRequest.Action.ROLL) {
                assertTrue(BoardActionPolicy.allowFromPlayer(action),
                        "Accidentally blocked valid action: " + action);
            }
        }
        assertTrue(BoardActionPolicy.allowFromPlayer(
                BoardRequest.order(BoardRequest.Action.ACCEPT_ORDER,
                        "economy_bounties:farm_orders", 0,
                        "00000000-0000-0000-0000-000000000001").action()));
    }
}
