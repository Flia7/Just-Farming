package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import com.justfarming.config.FarmingConfig;
import com.justfarming.gui.CropSelectScreen;
import com.justfarming.gui.CropSettingsScreen;
import com.justfarming.gui.FarmingConfigScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mouse.class)
public class MouseMixin {

    /**
     * When freelook is active, intercept the scroll wheel:
     * <ul>
     *   <li>Scroll up  → zoom in  (camera closer to player)</li>
     *   <li>Scroll down → zoom out (camera farther from player)</li>
     * </ul>
     * The event is cancelled so the hotbar slot does not change while freelook
     * is in use.
     */
    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void onMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        MacroManager mm = JustFarming.getMacroManager();
        if (mm != null && mm.isFreelookActive()) {
            // Do not adjust zoom while a GUI screen is open.
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.currentScreen != null) {
                ci.cancel();
                return;
            }
            // Scroll up (vertical > 0) → closer; scroll down (vertical < 0) → farther.
            // Negating vertical converts GLFW's upward-positive convention into a
            // zoom-out (positive) / zoom-in (negative) delta for adjustFreelookZoom.
            mm.adjustFreelookZoom(-vertical);
            ci.cancel();
        }
    }

    /**
     * When the macro is running with the "unlock mouse" option enabled, suppress
     * Minecraft's automatic cursor re-lock (which normally fires when a screen
     * closes or focus is regained). This lets the user interact with other
     * windows on their desktop while the macro keeps running.
     */
    @Inject(method = "lockCursor", at = @At("HEAD"), cancellable = true)
    private void onLockCursor(CallbackInfo ci) {
        MacroManager mm = JustFarming.getMacroManager();
        FarmingConfig cfg = JustFarming.getConfig();
        if (mm != null && cfg != null && mm.isRunning() && cfg.unlockedMouseEnabled) {
            ci.cancel();
        }
    }

    /**
     * When the macro is running, report the cursor as locked to Minecraft's
     * input-handling code. Minecraft only processes game inputs (attack key,
     * block breaking, etc.) when {@code isCursorLocked()} returns {@code true}
     * or {@code currentScreen} is {@code null}. By returning {@code true} here
     * we allow all gameplay inputs to be processed even when a GUI screen is
     * open, so the macro keeps breaking crops regardless of which screen the
     * player has open.
     *
     * <p>When the Just Farming config GUI or its sub-screens are open, we do
     * <em>not</em> override the value so that the camera remains stationary
     * while the player adjusts settings.
     */
    @Inject(method = "isCursorLocked", at = @At("HEAD"), cancellable = true)
    private void onIsCursorLocked(CallbackInfoReturnable<Boolean> cir) {
        MacroManager mm = JustFarming.getMacroManager();
        if (mm != null && mm.isRunning()) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.currentScreen instanceof FarmingConfigScreen
                    || client.currentScreen instanceof CropSettingsScreen
                    || client.currentScreen instanceof CropSelectScreen) {
                return;
            }
            cir.setReturnValue(true);
        }
    }
}
