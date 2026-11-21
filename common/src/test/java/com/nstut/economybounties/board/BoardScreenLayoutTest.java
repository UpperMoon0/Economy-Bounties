package com.nstut.economybounties.board;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoardScreenLayoutTest {
    @Test
    void inventoryShortcutNeverClosesWhileTypingOrModalIsOpen() {
        assertFalse(BoardScreenKeyPolicy.closeOnInventoryKey(false, false, false));
        assertFalse(BoardScreenKeyPolicy.closeOnInventoryKey(true, true, false));
        assertFalse(BoardScreenKeyPolicy.closeOnInventoryKey(true, false, true));
        assertFalse(BoardScreenKeyPolicy.closeOnInventoryKey(true, true, true));
        assertTrue(BoardScreenKeyPolicy.closeOnInventoryKey(true, false, false));
    }

    @Test
    void gridHasMultipleColumnsAndMoreSpaceThanListAtUsableWidths() {
        for (int width : new int[]{300, 380, 530, 600}) {
            int gridWidth = BoardBrowserLayout.browserWidth(width, true);
            int listWidth = BoardBrowserLayout.browserWidth(width, false);
            assertTrue(gridWidth > listWidth);
            int min = BoardBrowserLayout.gridCellMinWidth(gridWidth);
            assertTrue(BoardBrowserLayout.visibleGridColumns(gridWidth - 6, min) >= 2,
                    "grid collapsed into a list at width " + width);
        }
        assertEquals(1, BoardBrowserLayout.visibleGridColumns(90,
                BoardBrowserLayout.gridCellMinWidth(90)));
    }
}
