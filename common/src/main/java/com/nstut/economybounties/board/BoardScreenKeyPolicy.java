package com.nstut.economybounties.board;

/** Inventory-key handling mirrors Economy Market's OpenUI focus policy. */
public final class BoardScreenKeyPolicy {
    private BoardScreenKeyPolicy() { }

    public static boolean closeOnInventoryKey(boolean inventoryKey, boolean textFocused,
                                               boolean blockingOverlay) {
        return inventoryKey && !textFocused && !blockingOverlay;
    }
}
