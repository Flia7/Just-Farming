package com.justfarming.mixin;

import net.minecraft.client.render.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Increases the maximum distance at which entity nametags are rendered,
 * raising it from the default 64 blocks (4096 = 64²) to 256 blocks
 * (65536 = 256²).
 *
 * <p>On the Hypixel Skyblock Garden, pest nametag entities are sent by the
 * server within entity-tracking range (~64 blocks). Once loaded, this mixin
 * ensures their floating name text stays visible across the full width of
 * the Garden (~480 blocks), so the player can see which plots are infested
 * without relying on approximate placeholder tracers.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {

    /**
     * Replaces the 4096.0 (64-block) squared-distance threshold used in
     * {@code EntityRenderer.updateRenderState} with 65536.0 (256 blocks).
     */
    @ModifyConstant(method = "updateRenderState", constant = @Constant(doubleValue = 4096.0))
    private double extendNameTagRenderDistance(double original) {
        return 65536.0;
    }
}
