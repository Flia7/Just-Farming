package com.justfarming.pest;

/**
 * Provides world-space coordinate bounds for each of the 24 Hypixel Skyblock
 * Garden plots.
 *
 * <p>Garden layout (community-standard values):
 * <ul>
 *   <li>24 plots in a 6-column × 4-row grid.</li>
 *   <li>Each plot is {@value #PLOT_SIZE} × {@value #PLOT_SIZE} blocks.</li>
 *   <li>Grid origin (north-west corner of plot 1) is at X={@value #ORIGIN_X}, Z={@value #ORIGIN_Z}.</li>
 * </ul>
 *
 * <p>Plots are numbered left-to-right, top-to-bottom:
 * <pre>
 *  1  2  3  4  5  6
 *  7  8  9 10 11 12
 * 13 14 15 16 17 18
 * 19 20 21 22 23 24
 * </pre>
 */
public final class GardenPlot {

    /** Number of blocks along each side of a plot. */
    public static final int PLOT_SIZE = 96;

    /** Number of plot columns in the garden grid. */
    public static final int COLS = 6;

    /** Number of plot rows in the garden grid. */
    public static final int ROWS = 4;

    /** X coordinate of the north-west corner of plot 1 (approximate). */
    public static final int ORIGIN_X = -240;

    /** Z coordinate of the north-west corner of plot 1 (approximate). */
    public static final int ORIGIN_Z = -160;

    /** Minimum Y for rendering plot borders (just below farm surface). */
    public static final int MIN_Y = 63;

    /** Maximum Y for rendering plot borders (above typical crop height). */
    public static final int MAX_Y = 128;

    private GardenPlot() {}

    /**
     * Returns the axis-aligned bounding box for {@code plotNum} (1–24) as
     * {@code [minX, minY, minZ, maxX, maxY, maxZ]}, or {@code null} if
     * {@code plotNum} is out of range.
     */
    public static double[] getBounds(int plotNum) {
        if (plotNum < 1 || plotNum > COLS * ROWS) return null;
        int idx  = plotNum - 1;
        int col  = idx % COLS;
        int row  = idx / COLS;
        double minX = ORIGIN_X + (double) col * PLOT_SIZE;
        double minZ = ORIGIN_Z + (double) row * PLOT_SIZE;
        double maxX = minX + PLOT_SIZE;
        double maxZ = minZ + PLOT_SIZE;
        return new double[]{minX, MIN_Y, minZ, maxX, MAX_Y, maxZ};
    }

    /**
     * Returns the centre X coordinate of a plot's top face (used for label placement).
     */
    public static double getCentreX(int plotNum) {
        int col = (plotNum - 1) % COLS;
        return ORIGIN_X + col * PLOT_SIZE + PLOT_SIZE / 2.0;
    }

    /**
     * Returns the centre Z coordinate of a plot's top face (used for label placement).
     */
    public static double getCentreZ(int plotNum) {
        int row = (plotNum - 1) / COLS;
        return ORIGIN_Z + row * PLOT_SIZE + PLOT_SIZE / 2.0;
    }
}
