package com.justfarming.render;

import com.justfarming.config.FarmingConfig;
import com.justfarming.pest.GardenPlot;
import com.justfarming.pest.PestDetector;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders highlighted plot borders for Hypixel Skyblock Garden plots that
 * contain pests, following SkyHanni's rendering approach:
 * <ul>
 *   <li>3D line borders around each infested plot</li>
 *   <li>Corner vertical lines at the 4 corners of each plot</li>
 *   <li>Horizontal border lines every 4 blocks in height</li>
 *   <li>Vertical edge lines every 4 blocks along the borders</li>
 *   <li>Red/DarkRed colours when the player is inside an infested plot,
 *       Gold/Red when outside</li>
 * </ul>
 *
 * <p>Registered as a
 * {@link net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents#AFTER_ENTITIES}
 * callback in {@link com.justfarming.JustFarming}.
 */
public class OverlayRenderer {

    // Colours as packed ARGB ints
    private static final int COLOR_RED      = 0xFFFF0000;
    private static final int COLOR_DARK_RED = 0xFF8B0000;
    private static final int COLOR_GOLD     = 0xFFFFAD00;

    // Label colour (ARGB)
    private static final int LABEL_COLOR = 0xFFFF3333;

    private final FarmingConfig config;
    private final PestDetector  pestDetector;

    public OverlayRenderer(FarmingConfig config, PestDetector pestDetector) {
        this.config       = config;
        this.pestDetector = pestDetector;
    }

    /** Called by the WorldRenderEvents.AFTER_ENTITIES callback. */
    public void render(WorldRenderContext context) {
        if (!config.pestHighlightEnabled) return;

        Set<String> pestPlots = pestDetector.getPestPlots();
        if (pestPlots.isEmpty()) return;

        Camera camera = context.gameRenderer().getCamera();
        if (camera == null) return;
        Vec3d camPos = camera.getPos();

        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) return;

        double cx = camPos.x, cy = camPos.y, cz = camPos.z;

        // Determine which plot the player is in (if any)
        String playerPlot = GardenPlot.getPlotNameAt(camPos.x, camPos.z);

        List<Map.Entry<String, double[]>> validPlots = new ArrayList<>();
        for (String plotName : pestPlots) {
            double[] b = GardenPlot.getBounds(plotName);
            if (b != null) validPlots.add(Map.entry(plotName, b));
        }
        if (validPlots.isEmpty()) return;

        // Render all plot borders first using a single LINES buffer, then render
        // all labels. Mixing consumers.getBuffer(otherLayer) calls (e.g. text)
        // inside the same loop would cause the Immediate provider to flush and
        // end the LINES BufferBuilder, leading to "Not building!" crashes on
        // subsequent iterations.
        VertexConsumer lineBuffer = consumers.getBuffer(RenderLayer.getLines());

        for (Map.Entry<String, double[]> e : validPlots) {
            double[] b = e.getValue();
            boolean playerInside = e.getKey().equals(playerPlot);
            int lineColor   = playerInside ? COLOR_RED      : COLOR_GOLD;
            int cornerColor = playerInside ? COLOR_DARK_RED  : COLOR_RED;
            renderPlotBorders(matrices, lineBuffer, b, cx, cy, cz,
                    lineColor, cornerColor);
        }

        // Draw floating plot-number labels after all border geometry is submitted
        // so that requesting a text render layer does not prematurely flush the
        // LINES buffer that was used above.
        for (Map.Entry<String, double[]> e : validPlots) {
            double[] b = e.getValue();
            double labelX = (b[0] + b[3]) / 2.0 - cx;
            double labelY =  b[4] + 2.0          - cy;
            double labelZ = (b[2] + b[5]) / 2.0 - cz;
            DebugRenderer.drawString(matrices, consumers,
                    "Plot " + e.getKey(), labelX, labelY, labelZ, LABEL_COLOR);
        }
    }

    /**
     * Renders SkyHanni-style plot borders with 3D lines.
     */
    private void renderPlotBorders(MatrixStack matrices, VertexConsumer lines,
                                   double[] b, double cx, double cy, double cz,
                                   int lineColor, int cornerColor) {
        int plotSize = GardenPlot.PLOT_SIZE;
        int minH = GardenPlot.MIN_Y;
        int maxH = GardenPlot.MAX_Y;

        double minX = b[0] - cx;
        double minZ = b[2] - cz;
        double maxX = b[3] - cx;
        double maxZ = b[5] - cz;

        MatrixStack.Entry entry = matrices.peek();

        // --- Corner vertical lines (thick appearance via 4 corners) ---
        drawLine(entry, lines, minX, minH - cy, minZ, minX, maxH - cy, minZ, cornerColor);
        drawLine(entry, lines, maxX, minH - cy, minZ, maxX, maxH - cy, minZ, cornerColor);
        drawLine(entry, lines, minX, minH - cy, maxZ, minX, maxH - cy, maxZ, cornerColor);
        drawLine(entry, lines, maxX, minH - cy, maxZ, maxX, maxH - cy, maxZ, cornerColor);

        // --- Vertical edge lines every 4 blocks along X on front/back edges ---
        for (int dx = 4; dx < plotSize; dx += 4) {
            double x = minX + dx;
            drawLine(entry, lines, x, minH - cy, minZ, x, maxH - cy, minZ, lineColor);
            drawLine(entry, lines, x, minH - cy, maxZ, x, maxH - cy, maxZ, lineColor);
        }

        // --- Vertical edge lines every 4 blocks along Z on left/right edges ---
        for (int dz = 4; dz < plotSize; dz += 4) {
            double z = minZ + dz;
            drawLine(entry, lines, minX, minH - cy, z, minX, maxH - cy, z, lineColor);
            drawLine(entry, lines, maxX, minH - cy, z, maxX, maxH - cy, z, lineColor);
        }

        // --- Horizontal border rectangles every 4 blocks in height ---
        for (int y = minH; y <= maxH; y += 4) {
            double ry = y - cy;
            drawLine(entry, lines, minX, ry, minZ, minX, ry, maxZ, lineColor);
            drawLine(entry, lines, minX, ry, maxZ, maxX, ry, maxZ, lineColor);
            drawLine(entry, lines, maxX, ry, maxZ, maxX, ry, minZ, lineColor);
            drawLine(entry, lines, maxX, ry, minZ, minX, ry, minZ, lineColor);
        }
    }

    /**
     * Draws a single 3D line segment using the LINES render layer.
     */
    private void drawLine(MatrixStack.Entry entry, VertexConsumer lines,
                          double x1, double y1, double z1,
                          double x2, double y2, double z2,
                          int color) {
        // Compute direction for the normal
        float dx = (float) (x2 - x1);
        float dy = (float) (y2 - y1);
        float dz = (float) (z2 - z1);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6f) return;
        float nx = dx / len;
        float ny = dy / len;
        float nz = dz / len;

        lines.vertex(entry, (float) x1, (float) y1, (float) z1)
                .color(color)
                .normal(entry, nx, ny, nz);
        lines.vertex(entry, (float) x2, (float) y2, (float) z2)
                .color(color)
                .normal(entry, nx, ny, nz);
    }
}
