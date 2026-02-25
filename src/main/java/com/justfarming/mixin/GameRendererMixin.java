package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Modifies the FOV returned by GameRenderer to implement zoom during freelook.
 * A zoom > 1 reduces the FOV (zooms in); zoom < 1 increases FOV (zooms out).
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true, require = 0)
    private void modifyFovForFreelook(Camera camera, float tickDelta, boolean changingFov,
                                      CallbackInfoReturnable<Double> cir) {
        MacroManager mm = JustFarming.getMacroManager();
        if (mm == null || !mm.isFreelookActive()) return;

        float zoom = mm.getFreelookZoom();
        if (zoom == 1.0f) return;

        // Divide FOV by zoom level: higher zoom → narrower FOV
        cir.setReturnValue(cir.getReturnValue() / (double) zoom);
    }
}
