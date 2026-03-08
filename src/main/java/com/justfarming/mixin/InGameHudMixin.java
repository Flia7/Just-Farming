package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.config.FarmingConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the vanilla scoreboard sidebar renderer when the custom
 * "Just Farming" scoreboard is enabled via {@link FarmingConfig#customScoreboardEnabled}.
 */
@Mixin(InGameHud.class)
public class InGameHudMixin {

    @Inject(
            method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onRenderScoreboardSidebar(DrawContext context, RenderTickCounter tickCounter,
                                           CallbackInfo ci) {
        FarmingConfig cfg = JustFarming.getConfig();
        if (cfg != null && cfg.customScoreboardEnabled) {
            ci.cancel();
        }
    }
}
