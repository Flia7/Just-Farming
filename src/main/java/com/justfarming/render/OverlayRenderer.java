package com.justfarming.render;

import com.justfarming.config.FarmingConfig;
import com.justfarming.pest.GardenPlot;
import com.justfarming.pest.PestDetector;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

import java.util.Set;

/**
 * Renders highlighted plot borders (chunk-level grid) and floating plot-number
 * labels for Hypixel Skyblock Garden plots that contain pests.
 *
 * <p>Registered as a {@link net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents#AFTER_ENTITIES}
 * callback in {@link com.justfarming.JustFarming}.
 */
public class OverlayRenderer {

    // ARGB colour for pest plot outlines (vivid red)
    private static final int BORDER_COLOR = 0xFFFF3333;

    // RGBA floats for DebugRenderer.drawBox (red-ish with high alpha)
    private static final float R = 1.0f;
    private static final float G = 0.2f;
    private static final float B = 0.2f;
    private static final float A = 0.9f;

    private final FarmingConfig config;
    private final PestDetector  pestDetector;

    public OverlayRenderer(FarmingConfig config, PestDetector pestDetector) {
        this.config       = config;
        this.pestDetector = pestDetector;
    }

    /** Called by the WorldRenderEvents.AFTER_ENTITIES callback. */
    public void render(WorldRenderContext context) {
        if (!config.pestHighlightEnabled) return;

        Set<Integer> pestPlots = pestDetector.getPestPlots();
        if (pestPlots.isEmpty()) return;

        Camera   camera    = context.gameRenderer().getCamera();
        if (camera == null) return;
        Vec3d    camPos    = camera.getPos();

        MatrixStack           matrices  = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) return;

        double cx = camPos.x, cy = camPos.y, cz = camPos.z;

        for (int plotNum : pestPlots) {
            double[] b = GardenPlot.getBounds(plotNum);
            if (b == null) continue;

            // Draw a box for the overall plot boundary
            DebugRenderer.drawBox(matrices, consumers,
                    b[0] - cx, b[1] - cy, b[2] - cz,
                    b[3] - cx, b[4] - cy, b[5] - cz,
                    R, G, B, A);

            // Draw 16-block chunk-grid boxes inside the plot
            drawChunkGrid(matrices, consumers, b, cx, cy, cz);

            // Draw the plot number label at the centre-top of the plot
            double labelX = (b[0] + b[3]) / 2.0 - cx;
            double labelY =  b[4] + 2.0         - cy;
            double labelZ = (b[2] + b[5]) / 2.0 - cz;
            DebugRenderer.drawString(matrices, consumers,
                    "Plot " + plotNum, labelX, labelY, labelZ, BORDER_COLOR);
        }
    }

    /**
     * Draws a 16-block Minecraft chunk grid inside the given plot boundary.
     * Each 16x16 cell of the plot is outlined as a box.
     */
    private void drawChunkGrid(MatrixStack matrices, VertexConsumerProvider consumers,
                               double[] b, double cx, double cy, double cz) {
        double plotMinX = b[0], plotMinY = b[1], plotMinZ = b[2];
        double plotMaxX = b[3], plotMaxY = b[4], plotMaxZ = b[5];

        // Iterate over 16-block cells within the plot
        for (double z = plotMinZ; z < plotMaxZ; z += 16.0) {
            for (double x = plotMinX; x < plotMaxX; x += 16.0) {
                double cellMaxX = Math.min(x + 16.0, plotMaxX);
                double cellMaxZ = Math.min(z + 16.0, plotMaxZ);
                DebugRenderer.drawBox(matrices, consumers,
                        x - cx, plotMinY - cy, z - cz,
                        cellMaxX - cx, plotMaxY - cy, cellMaxZ - cz,
                        R, G, B, A * 0.5f); // dimmer for inner cells
            }
        }
    }
}
