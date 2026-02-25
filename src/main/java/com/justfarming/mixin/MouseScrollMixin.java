package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts mouse scroll events to implement zoom while freelook is active.
 * Scrolling up zooms in (decreases FOV) and scrolling down zooms out.
 */
@Mixin(Mouse.class)
public class MouseScrollMixin {

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true, require = 0)
    private void onScrollFreelook(long window, double horizontal, double vertical, CallbackInfo ci) {
        MacroManager mm = JustFarming.getMacroManager();
        if (mm == null || !mm.isFreelookActive()) return;

        // Scroll up (positive vertical) = zoom in; scroll down = zoom out
        float factor = (float) Math.pow(1.15, vertical);
        mm.setFreelookZoom(mm.getFreelookZoom() * factor);
        ci.cancel(); // prevent normal hotbar scroll while freelooking
    }
}
