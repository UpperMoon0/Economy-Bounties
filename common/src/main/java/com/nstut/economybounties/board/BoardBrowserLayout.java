package com.nstut.economybounties.board;

/**
 * Screen-scale-independent order browser geometry. Grid must retain at least
 * two actual columns at reasonable GUI widths instead of being a wide list.
 */
public final class BoardBrowserLayout {
    private BoardBrowserLayout() { }

    public static int browserWidth(int availableWidth, boolean grid) {
        return Math.max(80, availableWidth * (grid ? 67 : 55) / 100);
    }

    public static int gridCellMinWidth(int browserWidth) {
        // Allow card inset and grid gap without sacrificing the last column.
        int usable = Math.max(1, browserWidth - 10);
        int columns = usable >= 355 ? 3 : usable >= 155 ? 2 : 1;
        return Math.max(1, (usable - 4 * (columns - 1)) / columns);
    }

    /** Same integer column calculation as OpenUI VirtualGrid. */
    public static int visibleGridColumns(int actualViewportWidth, int minCellWidth) {
        return Math.max(1, (Math.max(1, actualViewportWidth) + 4) / (minCellWidth + 4));
    }
}
