package com.nstut.economybounties.board;

/**
 * Player-facing board protocol policy. System offers must originate from the
 * server's scheduled OrderBoardService only, never from a player packet.
 */
public final class BoardActionPolicy {
    private BoardActionPolicy() { }

    public static boolean allowFromPlayer(BoardRequest.Action action) {
        return action != null && action != BoardRequest.Action.ROLL;
    }

    public static String manualRollNotice() {
        return "Manual system bounty rolling is disabled; orders restock automatically.";
    }
}
