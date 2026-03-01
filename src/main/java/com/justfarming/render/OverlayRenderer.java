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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Renders highlighted plot borders for Hypixel Skyblock Garden plots that
 * contain pests.
 * <ul>
 *   <li>4 vertical corner lines at each plot corner in white</li>
 *   <li>Horizontal rectangle at the bottom (MIN_Y) connecting all corners</li>
 *   <li>Horizontal rectangle at the top (MAX_Y) connecting all corners</li>
 * </ul>
 * <p>This produces a cube-outline overlay (similar to the rewarp block highlight)
 * with no interior grid lines.</p>
 *
 * <p>Registered as a
 * {@link net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents#AFTER_ENTITIES}
 * callback in {@link com.justfarming.JustFarming}.
 */
public class OverlayRenderer {

    // Colours as packed ARGB ints
    private static final int COLOR_WHITE    = 0xFFFFFFFF; // white for ESP, tracer, rewarp, plot corners
    private static final int COLOR_ESP      = COLOR_WHITE;
    private static final int COLOR_REWARP   = COLOR_WHITE; // white rewarp block outline

    // Label colour (ARGB) – white for "Plot N" text
    private static final int LABEL_COLOR = COLOR_WHITE;

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

    // Dedicated see-through layer for the pest tracer lines (always visible through walls)
    private static final RenderLayer PEST_TRACER_SEE_THROUGH_LINES = RenderLayer.of(
            "pest_tracer_see_through_lines",
            1536,
            RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
                    .withLocation("just-farming/pest_tracer_see_through_lines")
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .build(),
            RenderLayer.MultiPhaseParameters.builder()
                    .layering(RenderPhase.VIEW_OFFSET_Z_LAYERING)
                    .target(RenderPhase.ITEM_ENTITY_TARGET)
                    .lineWidth(new RenderPhase.LineWidth(OptionalDouble.empty()))
                    .build(false));

    // See-through outline for the rewarp block (always visible through walls)
    private static final RenderLayer REWARP_SEE_THROUGH_LINES = RenderLayer.of(
            "rewarp_see_through_lines",
            1536,
            RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
                    .withLocation("just-farming/rewarp_see_through_lines")
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

        // ── Rewarp block see-through outline (always on when rewarp is set) ────
        if (config.rewarpSet) {
            double blockX = Math.floor(config.rewarpX);
            double blockY = Math.floor(config.rewarpY);
            double blockZ = Math.floor(config.rewarpZ);
            VertexConsumer rewarpLines = consumers.getBuffer(REWARP_SEE_THROUGH_LINES);
            MatrixStack.Entry rewarpEntry = matrices.peek();
            renderEspBox(rewarpEntry, rewarpLines,
                    new Box(blockX, blockY, blockZ, blockX + 1, blockY + 1, blockZ + 1),
                    cx, cy, cz, COLOR_REWARP);
        }

        // ── Pest entity ESP & Tracer ──────────────────────────────────────────
        List<PestEntityDetector.PestEntity> entityPests = pestEntityDetector.getDetectedPests();

        if (!entityPests.isEmpty()) {
            MatrixStack.Entry entry = matrices.peek();

            // ESP boxes: see-through wireframe around each detected pest entity.
            if (config.pestEspEnabled) {
                VertexConsumer espLines = consumers.getBuffer(PEST_ESP_SEE_THROUGH_LINES);
                for (PestEntityDetector.PestEntity pest : entityPests) {
                    renderEspBox(entry, espLines, adjustedEspBox(pest.boundingBox()), cx, cy, cz, COLOR_ESP);
                }
            }

            // Tracer lines: always see-through, draw from a point in front of the
            // camera to the centre of each ESP box.
            //
            // Drawing from the exact camera position (0,0,0) clips against the near
            // frustum plane and produces an invisible line.  Following Wurst7's
            // TracerHack approach, we instead start 10 blocks along the camera's
            // look direction so the start vertex is always well inside the frustum.
            if (config.pestTracerEnabled) {
                VertexConsumer tracerLines = consumers.getBuffer(PEST_TRACER_SEE_THROUGH_LINES);

                // Compute camera look direction using yaw/pitch (Minecraft convention:
                //   yaw 0 = south (+Z), pitch positive = looking down).
                float yawRad   = (float) Math.toRadians(camera.getYaw());
                float pitchRad = (float) Math.toRadians(camera.getPitch());
                float cosP     = (float) Math.cos(pitchRad);
                double tracerStartX = -Math.sin(yawRad) * cosP * 10.0;
                double tracerStartY = -Math.sin(pitchRad)       * 10.0;
                double tracerStartZ =  Math.cos(yawRad) * cosP * 10.0;

                for (PestEntityDetector.PestEntity pest : entityPests) {
                    Box espBox = adjustedEspBox(pest.boundingBox());
                    double targetX = (espBox.minX + espBox.maxX) / 2.0 - cx;
                    double targetY = (espBox.minY + espBox.maxY) / 2.0 - cy;
                    double targetZ = (espBox.minZ + espBox.maxZ) / 2.0 - cz;
                    drawLine(entry, tracerLines,
                            tracerStartX, tracerStartY, tracerStartZ,
                            targetX, targetY, targetZ, COLOR_WHITE);
                }
            }
        }

        // ── Plot borders & labels ─────────────────────────────────────────────
        if (!config.pestHighlightEnabled) return;

        Set<String> pestPlots = pestDetector.getPestPlots();
        if (pestPlots.isEmpty()) return;

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
            renderPlotBorders(matrices, lineBuffer, b, cx, cy, cz, COLOR_WHITE);
        }

        // Draw large floating title and smaller label for each infested plot
        if (config.pestLabelsEnabled) {
            MinecraftClient mc = MinecraftClient.getInstance();
            Map<String, Integer> pestCounts = pestDetector.getPestCounts();

            // Count pests per plot from entity positions (more reliable than scoreboard)
            Map<String, Integer> entityCounts = new HashMap<>();
            for (PestEntityDetector.PestEntity pest : entityPests) {
                String plotName = GardenPlot.getPlotNameAt(pest.position().x, pest.position().z);
                if (plotName != null) {
                    entityCounts.merge(plotName, 1, Integer::sum);
                }
            }

            float titleScale = config.pestTitleScale;
            for (Map.Entry<String, double[]> e : validPlots) {
                double[] b = e.getValue();
                double centreX = (b[0] + b[3]) / 2.0;
                double centreZ = (b[2] + b[5]) / 2.0;
                // Prefer entity-based count (most accurate); fall back to scoreboard count
                Integer count = entityCounts.containsKey(e.getKey())
                        ? entityCounts.get(e.getKey())
                        : pestCounts.get(e.getKey());

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
                        0, 0xF000F0);
                matrices.pop();

                // --- Pest count subtitle below the title ---
                String subtitle = count != null
                        ? PestDetector.formatPestCount(count)
                        : "Pests: ?";
                // Place subtitle below the title: title text extends down by (font_height * titleScale)
                double subtitleY = titleY - 9.0 * titleScale - 2.0;
                matrices.push();
                matrices.translate(centreX - cx, subtitleY - cy, centreZ - cz);
                matrices.multiply(camera.getRotation());
                float subtitleScale = titleScale * 0.6f;
                matrices.scale(subtitleScale, -subtitleScale, subtitleScale);
                org.joml.Matrix4f subMatrix = matrices.peek().getPositionMatrix();
                float subHalf = mc.textRenderer.getWidth(subtitle) / 2.0f;
                mc.textRenderer.draw(subtitle, -subHalf, 0, LABEL_COLOR, false,
                        subMatrix, consumers, TextRenderer.TextLayerType.SEE_THROUGH,
                        0, 0xF000F0);
                matrices.pop();
            }
        }
    }

    /**
     * Renders a cube-style plot outline: four vertical corner lines plus
     * horizontal rectangles at the top and bottom, all in white.
     */
    private void renderPlotBorders(MatrixStack matrices, VertexConsumer lines,
                                   double[] b, double cx, double cy, double cz,
                                   int color) {
        int minH = GardenPlot.MIN_Y;
        int maxH = GardenPlot.MAX_Y;

        double minX = b[0] - cx;
        double minZ = b[2] - cz;
        double maxX = b[3] - cx;
        double maxZ = b[5] - cz;

        MatrixStack.Entry entry = matrices.peek();

        // --- Four vertical corner lines ---
        drawLine(entry, lines, minX, minH - cy, minZ, minX, maxH - cy, minZ, color);
        drawLine(entry, lines, maxX, minH - cy, minZ, maxX, maxH - cy, minZ, color);
        drawLine(entry, lines, minX, minH - cy, maxZ, minX, maxH - cy, maxZ, color);
        drawLine(entry, lines, maxX, minH - cy, maxZ, maxX, maxH - cy, maxZ, color);

        // --- Horizontal rectangle at the bottom ---
        drawLine(entry, lines, minX, minH - cy, minZ, maxX, minH - cy, minZ, color);
        drawLine(entry, lines, maxX, minH - cy, minZ, maxX, minH - cy, maxZ, color);
        drawLine(entry, lines, maxX, minH - cy, maxZ, minX, minH - cy, maxZ, color);
        drawLine(entry, lines, minX, minH - cy, maxZ, minX, minH - cy, minZ, color);

        // --- Horizontal rectangle at the top ---
        drawLine(entry, lines, minX, maxH - cy, minZ, maxX, maxH - cy, minZ, color);
        drawLine(entry, lines, maxX, maxH - cy, minZ, maxX, maxH - cy, maxZ, color);
        drawLine(entry, lines, maxX, maxH - cy, maxZ, minX, maxH - cy, maxZ, color);
        drawLine(entry, lines, minX, maxH - cy, maxZ, minX, maxH - cy, minZ, color);
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
     * Returns a copy of {@code box} expanded to 1.5× its original dimensions
     * and shifted down by half a block, so the overlay is larger and better
     * centred on the pest entity.
     */
    private static Box adjustedEspBox(Box box) {
        double ex = box.getLengthX() * 0.25;
        double ey = box.getLengthY() * 0.25;
        double ez = box.getLengthZ() * 0.25;
        return box.expand(ex, ey, ez).offset(0, -0.5, 0);
    }
}
