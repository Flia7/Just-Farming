package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import com.justfarming.config.FarmingConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {

    /**
     * When freelook is active, intercept the scroll wheel:
     * <ul>
     *   <li>Scroll up  → zoom in  (camera closer to player)</li>
     *   <li>Scroll down → zoom out (camera farther from player)</li>
     * </ul>
     * If a GUI screen is open (e.g. chat), the event is passed through so the
     * screen can handle it normally (e.g. scrolling chat history).
     * The event is cancelled only when no screen is open so the hotbar slot
     * does not change while freelook is in use.
     */
    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void onMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        MacroManager mm = JustFarming.getMacroManager();
        if (mm != null && mm.isFreelookActive()) {
            MinecraftClient client = MinecraftClient.getInstance();
            // Let any open screen (chat, inventory, etc.) handle the scroll normally.
            if (client != null && client.currentScreen != null) {
                return;
            }
            // No screen open: adjust freelook zoom and swallow the event so the
            // hotbar slot does not change.
            mm.adjustFreelookZoom(-vertical);
            ci.cancel();
        }
    }

    /**
     * When the macro is running with the "unlock mouse" option enabled, suppress
     * Minecraft's automatic cursor re-lock (which normally fires when a screen
     * closes or focus is regained). This lets the user interact with other
     * windows on their desktop while the macro keeps running.
     *
     * <p>The suppression is also active while the farming macro is paused waiting
     * for the visitor or pest-killer routine to finish, so the cursor stays
     * unlocked throughout the entire rewarp–visit–return cycle.
     */
    @Inject(method = "lockCursor", at = @At("HEAD"), cancellable = true)
    private void onLockCursor(CallbackInfo ci) {
        FarmingConfig cfg = JustFarming.getConfig();
        if (cfg != null && cfg.unlockedMouseEnabled && JustFarming.isAnyMacroRoutineActive()) {
            ci.cancel();
        }
    }

    /**
     * When any macro is active, always report the cursor as locked regardless of
     * the actual GLFW cursor state.  This prevents {@code MinecraftClient.tick()}
     * from passing {@code false} to {@code handleBlockBreaking} (which evaluates
     * {@code attackKey.isPressed() && mouse.isCursorLocked()}) in two scenarios:
     * <ol>
     *   <li>The "unlock mouse" option is enabled — the GLFW cursor is free by
     *       design so the player can interact with other windows.</li>
     *   <li>The "unlock mouse" option is <em>disabled</em> but the game window
     *       temporarily loses focus (alt-tab) — {@code onWindowFocusChanged}
     *       calls {@code unlockCursor()} to free the cursor while the window is
     *       unfocused, and it calls {@code lockCursor()} when focus returns.
     *       Without this override, the single-tick gap before re-locking (and
     *       every tick while tabbed out) would abort the block-breaking
     *       sequence.</li>
     * </ol>
     * Actual physical mouse movement is still suppressed by
     * {@code EntityMixin.changeLookDirection}, so the camera never spins.
     */
    @Inject(method = "isCursorLocked", at = @At("RETURN"), cancellable = true)
    private void onIsCursorLocked(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) return; // already locked — nothing to do
        if (JustFarming.isAnyMacroRoutineActive()) {
            cir.setReturnValue(true);
        }
    }
}
