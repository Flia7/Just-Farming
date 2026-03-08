package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import com.justfarming.config.FarmingConfig;
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
     * When the macro is running, allow Minecraft's {@code unlockCursor()} call
     * to proceed so the OS cursor becomes visible when a GUI is opened.
     *
     * <p>The {@link #onIsCursorLocked} override still returns {@code true} while
     * the macro is running, ensuring all game inputs (attack key, movement) are
     * processed normally even with the cursor visible.  This gives the user a
     * visible cursor inside any open screen without causing the macro to pause.
     *
     * <p>When {@code unlockedMouseEnabled} is {@code true} the cursor is already
     * permanently unlocked via the {@link #onLockCursor} suppression, so no
     * extra handling is needed here.
     */
    @Inject(method = "unlockCursor", at = @At("HEAD"), cancellable = true)
    private void onUnlockCursor(CallbackInfo ci) {
        // Allow unlockCursor to run: cursor appears on screen when a GUI opens.
        // onIsCursorLocked() keeps returning true so game inputs are not blocked.
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
        MacroManager mm = JustFarming.getMacroManager();
        FarmingConfig cfg = JustFarming.getConfig();
        if (mm != null && cfg != null && cfg.unlockedMouseEnabled
                && (mm.isRunning() || mm.isWaitingForVisitors() || mm.isWaitingForPestKiller())) {
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
     * <p>When the {@code macroEnabledInGui} option is <em>disabled</em> and a
     * <em>blocking</em> GUI screen is open (i.e. not the player's own inventory
     * and not the chat screen), we do <em>not</em> override the value.  This lets
     * Minecraft's own logic suppress game inputs while the screen is visible,
     * which is consistent with
     * {@link com.justfarming.MacroManager#isGuiBlocking()}.  The inventory and
     * chat screens are deliberately exempt so the macro continues without any
     * movement pause when the player checks their bags or types a message.
     */
    @Inject(method = "isCursorLocked", at = @At("HEAD"), cancellable = true)
    private void onIsCursorLocked(CallbackInfoReturnable<Boolean> cir) {
        MacroManager mm = JustFarming.getMacroManager();
        if (mm != null && mm.isRunning()) {
            MinecraftClient client = MinecraftClient.getInstance();
            FarmingConfig cfg = JustFarming.getConfig();
            boolean enabledInGui = cfg != null && cfg.macroEnabledInGui;
            if (!enabledInGui && client.currentScreen != null) {
                // Inventory and chat never block the macro – keep game inputs alive.
                if (MacroManager.isNonBlockingScreen(client.currentScreen)) {
                    cir.setReturnValue(true);
                    return;
                }
                // Other screens with macroEnabledInGui disabled → let vanilla return false.
                return;
            }
            cir.setReturnValue(true);
        }
    }
}
