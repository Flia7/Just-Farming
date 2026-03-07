package com.flia.pest;

import java.util.HashMap;
import java.util.Map;

/**
 * Provides world-space coordinate bounds for each of the 24 Hypixel Skyblock
 * Garden plots.
 *
 * <p>Garden layout (5×5 grid, barn at centre):
 * <ul>
 *   <li>25 cells in a 5-column × 5-row grid; the barn occupies the centre cell.</li>
 *   <li>Each plot is {@value #PLOT_SIZE} × {@value #PLOT_SIZE} blocks.</li>
 *   <li>Grid spans X ∈ [−240, 240], Z ∈ [−240, 240].</li>
 * </ul>
 *
 * <p>Plot names (1–24) mapped to grid positions (row, col) following the
 * standard Hypixel unlock-spiral order (matching SkyHanni):
 * <pre>
 *  21  13   9  14  22
 *  15   5   1   6  16
 *  10   2  [B]  3  11
 *  17   7   4   8  18
 *  23  19  12  20  24
 * </pre>
 */
public final class GardenPlot {

    /** Number of blocks along each side of a plot. */
    public static final int PLOT_SIZE = 96;

    /** Number of plot columns in the garden grid. */
    public static final int COLS = 5;

    /** Number of plot rows in the garden grid. */
    public static final int ROWS = 5;

    /** Minimum Y for rendering plot borders (matching SkyHanni). */
    public static final int MIN_Y = 66;

    /** Maximum Y for rendering plot borders (MIN_Y + 36, matching SkyHanni). */
    public static final int MAX_Y = 102;

    /**
     * Maps plot name (String "1"–"24") → grid index {row, col} in the 5×5 grid.
     * The barn is at (2, 2) and has no plot number.
     */
    private static final Map<String, int[]> NAME_TO_GRID = new HashMap<>();

    /**
     * Reverse lookup: grid key (row * COLS + col) → plot name.
     * Enables O(1) position-to-name resolution in {@link #getPlotNameAt}.
     */
    private static final Map<Integer, String> GRID_TO_NAME = new HashMap<>();

    static {
        // Standard Hypixel Garden unlock-spiral numbering (matching SkyHanni).
        // Row 0 (north-most)
        putPlot("21", 0, 0);
        putPlot("13", 0, 1);
        putPlot("9",  0, 2);
        putPlot("14", 0, 3);
        putPlot("22", 0, 4);
        // Row 1
        putPlot("15", 1, 0);
        putPlot("5",  1, 1);
        putPlot("1",  1, 2);
        putPlot("6",  1, 3);
        putPlot("16", 1, 4);
        // Row 2 (barn at col 2)
        putPlot("10", 2, 0);
        putPlot("2",  2, 1);
        // (2,2) = barn
        putPlot("3",  2, 3);
        putPlot("11", 2, 4);
        // Row 3
        putPlot("17", 3, 0);
        putPlot("7",  3, 1);
        putPlot("4",  3, 2);
        putPlot("8",  3, 3);
        putPlot("18", 3, 4);
        // Row 4 (south-most)
        putPlot("23", 4, 0);
        putPlot("19", 4, 1);
        putPlot("12", 4, 2);
        putPlot("20", 4, 3);
        putPlot("24", 4, 4);
    }

    private static void putPlot(String name, int row, int col) {
        NAME_TO_GRID.put(name, new int[]{row, col});
        GRID_TO_NAME.put(row * COLS + col, name);
    }

    private GardenPlot() {}

    /**
     * Returns the axis-aligned bounding box for the plot identified by
     * {@code plotName} (e.g. "1", "12") as
     * {@code [minX, minY, minZ, maxX, maxY, maxZ]}, or {@code null} if
     * the name is unknown.
     */
    public static double[] getBounds(String plotName) {
        int[] rc = NAME_TO_GRID.get(plotName);
        if (rc == null) return null;
        int col = rc[1];
        int row = rc[0];
        double minX = -240.0 + col * PLOT_SIZE;
        double minZ = -240.0 + row * PLOT_SIZE;
        return new double[]{minX, MIN_Y, minZ, minX + PLOT_SIZE, MAX_Y, minZ + PLOT_SIZE};
    }

    /**
     * Returns the centre X coordinate of the given plot (used for label
     * placement), or {@code Double.NaN} if the name is unknown.
     */
    public static double getCentreX(String plotName) {
        int[] rc = NAME_TO_GRID.get(plotName);
        if (rc == null) return Double.NaN;
        return -240.0 + rc[1] * PLOT_SIZE + PLOT_SIZE / 2.0;
    }

    /**
     * Returns the centre Z coordinate of the given plot, or
     * {@code Double.NaN} if the name is unknown.
     */
    public static double getCentreZ(String plotName) {
        int[] rc = NAME_TO_GRID.get(plotName);
        if (rc == null) return Double.NaN;
        return -240.0 + rc[0] * PLOT_SIZE + PLOT_SIZE / 2.0;
    }

    /**
     * Returns the plot name for the grid cell that contains the given
     * world X/Z coordinates, or {@code null} if outside the garden or on
     * the barn.
     */
    public static String getPlotNameAt(double x, double z) {
        if (x < -240 || x > 240 || z < -240 || z > 240) return null;
        int col = (int) Math.floor((x + 240) / PLOT_SIZE);
        int row = (int) Math.floor((z + 240) / PLOT_SIZE);
        if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return null;
        return GRID_TO_NAME.get(row * COLS + col); // null for barn cell
    }
}
