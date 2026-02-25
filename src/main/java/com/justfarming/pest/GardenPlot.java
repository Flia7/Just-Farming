package com.justfarming.pest;

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
 * standard Hypixel unlock-spiral order:
 * <pre>
 *  21  13   9   5  17
 *  22  14   3   1  10
 *  24   4  [B]  2  18
 *  20  16   7   6  11
 *  23  15  12   8  19
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

    static {
        // Standard Hypixel Garden unlock-spiral numbering.
        // Row 0 (north-most)
        NAME_TO_GRID.put("21", new int[]{0, 0});
        NAME_TO_GRID.put("13", new int[]{0, 1});
        NAME_TO_GRID.put("9",  new int[]{0, 2});
        NAME_TO_GRID.put("5",  new int[]{0, 3});
        NAME_TO_GRID.put("17", new int[]{0, 4});
        // Row 1
        NAME_TO_GRID.put("22", new int[]{1, 0});
        NAME_TO_GRID.put("14", new int[]{1, 1});
        NAME_TO_GRID.put("3",  new int[]{1, 2});
        NAME_TO_GRID.put("1",  new int[]{1, 3});
        NAME_TO_GRID.put("10", new int[]{1, 4});
        // Row 2 (barn at col 2)
        NAME_TO_GRID.put("24", new int[]{2, 0});
        NAME_TO_GRID.put("4",  new int[]{2, 1});
        // (2,2) = barn
        NAME_TO_GRID.put("2",  new int[]{2, 3});
        NAME_TO_GRID.put("18", new int[]{2, 4});
        // Row 3
        NAME_TO_GRID.put("20", new int[]{3, 0});
        NAME_TO_GRID.put("16", new int[]{3, 1});
        NAME_TO_GRID.put("7",  new int[]{3, 2});
        NAME_TO_GRID.put("6",  new int[]{3, 3});
        NAME_TO_GRID.put("11", new int[]{3, 4});
        // Row 4 (south-most)
        NAME_TO_GRID.put("23", new int[]{4, 0});
        NAME_TO_GRID.put("15", new int[]{4, 1});
        NAME_TO_GRID.put("12", new int[]{4, 2});
        NAME_TO_GRID.put("8",  new int[]{4, 3});
        NAME_TO_GRID.put("19", new int[]{4, 4});
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
        for (Map.Entry<String, int[]> entry : NAME_TO_GRID.entrySet()) {
            if (entry.getValue()[0] == row && entry.getValue()[1] == col) {
                return entry.getKey();
            }
        }
        return null; // barn cell
    }
}
