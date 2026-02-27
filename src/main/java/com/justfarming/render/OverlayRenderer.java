package com.justfarming.render;

import com.justfarming.config.FarmingConfig;
import com.justfarming.pest.GardenPlot;
import com.justfarming.pest.PestDetector;
import com.justfarming.pest.PestEntityDetector;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
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
    private static final int COLOR_GREEN    = 0xFF00FF00;
    private static final int COLOR_ESP      = 0xFFFF4444;

    // Label colour (ARGB)
    private static final int LABEL_COLOR = 0xFFFF3333;

    // Semi-transparent black background behind floating text for readability
    private static final int TEXT_BG_COLOR = 0x40000000;

    // See-through wireframe ESP: lines rendered with no depth test (through blocks)
    private static final RenderLayer PEST_ESP_SEE_THROUGH_LINES = RenderLayer.of(
            "pest_esp_see_through_lines",
            1536,
            RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
                    .withLocation("just-farming/pest_esp_see_through_lines")
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .build(),
            RenderLayer.MultiPhaseParameters.builder()
                    .layering(RenderPhase.VIEW_OFFSET_Z_LAYERING)
                    .target(RenderPhase.ITEM_ENTITY_TARGET)
                    .lineWidth(new RenderPhase.LineWidth(OptionalDouble.empty()))
                    .build(false));


    private final FarmingConfig       config;
    private final PestDetector        pestDetector;
    private final PestEntityDetector  pestEntityDetector;

    // Cached data for 2D HUD rendering (set every frame by render())
    private volatile Matrix4f cachedMvp    = null;
    private volatile Vec3d    cachedCamPos = null;

    public OverlayRenderer(FarmingConfig config, PestDetector pestDetector,
                           PestEntityDetector pestEntityDetector) {
        this.config             = config;
        this.pestDetector       = pestDetector;
        this.pestEntityDetector = pestEntityDetector;
    }

    /** Called by the WorldRenderEvents.AFTER_ENTITIES callback. */
    public void render(WorldRenderContext context) {
        Camera camera = context.gameRenderer().getCamera();
        if (camera == null) return;
        Vec3d camPos = camera.getPos();

        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) return;

        double cx = camPos.x, cy = camPos.y, cz = camPos.z;

        // ── Pest entity ESP & Tracer ──────────────────────────────────────────
        List<PestEntityDetector.PestEntity> pests = pestEntityDetector.getDetectedPests();

        // Cache projection+view matrix for this frame so renderHud() can use it.
        // Uses the same MVP construction as Minecraft's own world rendering.
        float fov = (float) MinecraftClient.getInstance().options.getFov().getValue();
        Matrix4f projection = context.gameRenderer().getBasicProjectionMatrix(fov);
        Quaternionf viewRot = camera.getRotation().conjugate(new Quaternionf());
        cachedMvp    = projection.mul(new Matrix4f().rotation(viewRot), new Matrix4f());
        cachedCamPos = camPos;

        if (!pests.isEmpty() && (config.pestEspEnabled || config.pestTracerEnabled)) {
            boolean seeThrough = config.pestEspSeeThrough;

            RenderLayer linesLayer = seeThrough ? PEST_ESP_SEE_THROUGH_LINES : RenderLayer.getLines();
            VertexConsumer pestLines = consumers.getBuffer(linesLayer);
            MatrixStack.Entry entry = matrices.peek();

            for (PestEntityDetector.PestEntity pest : pests) {
                if (config.pestEspEnabled) {
                    renderEspBox(entry, pestLines, halfSizeBox(pest.boundingBox()), cx, cy, cz, COLOR_ESP);
                }
                if (config.pestTracerEnabled) {
                    drawLine(entry, pestLines,
                            0, 0, 0, // camera-relative origin (player eye)
                            pest.position().x - cx,
                            pest.position().y - cy,
                            pest.position().z - cz,
                            COLOR_GREEN);
                }
            }
        }

        // ── Plot borders & labels ─────────────────────────────────────────────
        if (!config.pestHighlightEnabled) return;

        Set<String> pestPlots = pestDetector.getPestPlots();
        if (pestPlots.isEmpty()) return;

        // Determine which plot the player is in (if any)
        String playerPlot = GardenPlot.getPlotNameAt(camPos.x, camPos.z);

        List<Map.Entry<String, double[]>> validPlots = new ArrayList<>();
        for (String plotName : pestPlots) {
            double[] b = GardenPlot.getBounds(plotName);
            if (b != null) validPlots.add(Map.entry(plotName, b));
        }
        if (validPlots.isEmpty()) return;

        // Render all plot borders using a single LINES buffer
        VertexConsumer lineBuffer = consumers.getBuffer(RenderLayer.getLines());

        for (Map.Entry<String, double[]> e : validPlots) {
            double[] b = e.getValue();
            boolean playerInside = e.getKey().equals(playerPlot);
            int lineColor   = playerInside ? COLOR_RED      : COLOR_GOLD;
            int cornerColor = playerInside ? COLOR_DARK_RED  : COLOR_RED;
            renderPlotBorders(matrices, lineBuffer, b, cx, cy, cz,
                    lineColor, cornerColor);
        }

        // Draw large floating title and smaller label for each infested plot
        if (config.pestLabelsEnabled) {
            MinecraftClient mc = MinecraftClient.getInstance();
            Map<String, Integer> pestCounts = pestDetector.getPestCounts();
            float titleScale = config.pestTitleScale;
            for (Map.Entry<String, double[]> e : validPlots) {
                double[] b = e.getValue();
                double centreX = (b[0] + b[3]) / 2.0;
                double centreZ = (b[2] + b[5]) / 2.0;
                Integer count = pestCounts.get(e.getKey());

                // --- Large title: "Plot <N>" in the middle of the plot ---
                String title = "Plot " + e.getKey();
                double titleY = (b[1] + b[4]) / 2.0 + 5.0;
                matrices.push();
                matrices.translate(centreX - cx, titleY - cy, centreZ - cz);
                matrices.multiply(camera.getRotation());
                matrices.scale(titleScale, -titleScale, titleScale);
                org.joml.Matrix4f titleMatrix = matrices.peek().getPositionMatrix();
                float titleHalf = mc.textRenderer.getWidth(title) / 2.0f;
                mc.textRenderer.draw(title, -titleHalf, 0, LABEL_COLOR, false,
                        titleMatrix, consumers, TextRenderer.TextLayerType.SEE_THROUGH,
                        TEXT_BG_COLOR, 0xF000F0);
                matrices.pop();

                // --- Pest count subtitle below the title ---
                if (count != null) {
                    String subtitle = PestDetector.formatPestCount(count);
                    double subtitleY = titleY - 3.0;
                    matrices.push();
                    matrices.translate(centreX - cx, subtitleY - cy, centreZ - cz);
                    matrices.multiply(camera.getRotation());
                    float subtitleScale = titleScale * 0.6f;
                    matrices.scale(subtitleScale, -subtitleScale, subtitleScale);
                    org.joml.Matrix4f subMatrix = matrices.peek().getPositionMatrix();
                    float subHalf = mc.textRenderer.getWidth(subtitle) / 2.0f;
                    mc.textRenderer.draw(subtitle, -subHalf, 0, 0xFFFFAA00, false,
                            subMatrix, consumers, TextRenderer.TextLayerType.SEE_THROUGH,
                            TEXT_BG_COLOR, 0xF000F0);
                    matrices.pop();
                }
            }
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

    /**
     * Renders a wireframe bounding box (ESP) around a pest entity.
     */
    private void renderEspBox(MatrixStack.Entry entry, VertexConsumer lines,
                              Box box, double cx, double cy, double cz,
                              int color) {
        double x0 = box.minX - cx, y0 = box.minY - cy, z0 = box.minZ - cz;
        double x1 = box.maxX - cx, y1 = box.maxY - cy, z1 = box.maxZ - cz;

        // Bottom face
        drawLine(entry, lines, x0, y0, z0, x1, y0, z0, color);
        drawLine(entry, lines, x1, y0, z0, x1, y0, z1, color);
        drawLine(entry, lines, x1, y0, z1, x0, y0, z1, color);
        drawLine(entry, lines, x0, y0, z1, x0, y0, z0, color);

        // Top face
        drawLine(entry, lines, x0, y1, z0, x1, y1, z0, color);
        drawLine(entry, lines, x1, y1, z0, x1, y1, z1, color);
        drawLine(entry, lines, x1, y1, z1, x0, y1, z1, color);
        drawLine(entry, lines, x0, y1, z1, x0, y1, z0, color);

        // Vertical edges
        drawLine(entry, lines, x0, y0, z0, x0, y1, z0, color);
        drawLine(entry, lines, x1, y0, z0, x1, y1, z0, color);
        drawLine(entry, lines, x1, y0, z1, x1, y1, z1, color);
        drawLine(entry, lines, x0, y0, z1, x0, y1, z1, color);
    }

    /**
     * Returns a copy of {@code box} contracted to half its original dimensions
     * (25% shrink on each side), centred at the same point.
     */
    private static Box halfSizeBox(Box box) {
        return box.contract(
                box.getLengthX() * 0.25,
                box.getLengthY() * 0.25,
                box.getLengthZ() * 0.25);
    }

    /**
     * Draws 2D screen-space ESP boxes around detected pest entities on the HUD.
     *
     * <p>This replaces the old buggy 3D filled-quad approach: by projecting the
     * bounding-box corners to NDC space and drawing a flat coloured rectangle on
     * the HUD, we avoid the "transparent inside" artefact that plagued the
     * previous translucent-quad render layer.
     *
     * @param context    the HUD draw context
     * @param width      the scaled screen width in pixels
     * @param height     the scaled screen height in pixels
     */
    public void renderHud(DrawContext context, int width, int height) {
        if (!config.pestEspEnabled || !config.pestEspFilled) return;

        Matrix4f mvp     = cachedMvp;
        Vec3d    camPos  = cachedCamPos;
        if (mvp == null || camPos == null) return;

        List<PestEntityDetector.PestEntity> pests = pestEntityDetector.getDetectedPests();
        if (pests.isEmpty()) return;

        double cx = camPos.x, cy = camPos.y, cz = camPos.z;

        for (PestEntityDetector.PestEntity pest : pests) {
            Box box = halfSizeBox(pest.boundingBox());

            float minSx = Float.MAX_VALUE,  minSy = Float.MAX_VALUE;
            float maxSx = -Float.MAX_VALUE, maxSy = -Float.MAX_VALUE;
            boolean anyVisible = false;

            double[][] corners = {
                {box.minX, box.minY, box.minZ},
                {box.maxX, box.minY, box.minZ},
                {box.minX, box.maxY, box.minZ},
                {box.maxX, box.maxY, box.minZ},
                {box.minX, box.minY, box.maxZ},
                {box.maxX, box.minY, box.maxZ},
                {box.minX, box.maxY, box.maxZ},
                {box.maxX, box.maxY, box.maxZ},
            };

            for (double[] c : corners) {
                float rx = (float) (c[0] - cx);
                float ry = (float) (c[1] - cy);
                float rz = (float) (c[2] - cz);
                Vector4f clip = mvp.transform(rx, ry, rz, 1.0f, new Vector4f());
                if (clip.w <= 0f) continue; // behind camera – skip this corner
                anyVisible = true;
                float sx = (clip.x / clip.w + 1.0f) * 0.5f * width;
                float sy = (1.0f - clip.y / clip.w) * 0.5f * height;
                if (sx < minSx) minSx = sx;
                if (sy < minSy) minSy = sy;
                if (sx > maxSx) maxSx = sx;
                if (sy > maxSy) maxSy = sy;
            }

            if (!anyVisible || minSx >= maxSx || minSy >= maxSy) continue;

            int x0 = (int) Math.max(0,      minSx);
            int y0 = (int) Math.max(0,      minSy);
            int x1 = (int) Math.min(width,  maxSx);
            int y1 = (int) Math.min(height, maxSy);

            // Semi-transparent fill so the entity is still visible through the box
            context.fill(x0, y0, x1, y1, 0x50FF4444);
            // Solid opaque border
            context.fill(x0,     y0,     x1,     y0 + 1, 0xFFFF4444);
            context.fill(x0,     y1 - 1, x1,     y1,     0xFFFF4444);
            context.fill(x0,     y0,     x0 + 1, y1,     0xFFFF4444);
            context.fill(x1 - 1, y0,     x1,     y1,     0xFFFF4444);
        }
    }
}
