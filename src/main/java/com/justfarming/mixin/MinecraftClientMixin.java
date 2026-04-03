package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    /**
     * When the macro is running, skip the {@link KeyBinding#unpressAll()} call
     * that {@code setScreen()} makes every time a GUI is opened <em>or closed</em>.
     *
     * <p>Normally {@code setScreen()} calls {@code unpressAll()} to release every
     * held key before showing a new screen.  For the macro this creates a brief
     * moment (one or more game ticks) where all movement and attack keys appear
     * unpressed, causing a visible micro-stutter on open and—more importantly—
     * releasing movement keys when a screen is opened or closed.  Because the macro
     * presses keys programmatically (not via physical hardware), GLFW never sends a
     * matching "key released" hardware event, so skipping the call is safe: no
     * physical key state is lost and the macro continues without interruption.
     *
     * <p>When the macro is idle the call is made explicitly so vanilla behaviour
     * is preserved.
     */
    @Redirect(
            method = "setScreen",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/KeyBinding;unpressAll()V")
    )
    private void redirectUnpressAll() {
        MacroManager mm = JustFarming.getMacroManager();
        if (mm != null && mm.isRunning()) {
            return; // skip unpressAll whenever the macro is active
        }
        KeyBinding.unpressAll();
    }

    /**
     * Suppress the game-pause screen ({@link GameMenuScreen}) while the farming
     * macro is running so that pressing Escape or Alt-Tab focus-loss cannot
     * interrupt the macro.
     */
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void onSetScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof GameMenuScreen) {
            if (JustFarming.isAnyMacroRoutineActive()) {
                ci.cancel();
            }
        }
    }

    /**
     * When the game window gains or loses focus while any macro is running:
     * <ul>
     *   <li><em>Focus lost</em> (alt-tab out): unlock the cursor so the player can
     *       interact with other windows on their desktop.</li>
     *   <li><em>Focus gained</em> (re-tab in): re-lock the cursor so farming can
     *       continue with the captured cursor.  If
     *       {@link com.justfarming.config.FarmingConfig#unlockedMouseEnabled} is
     *       {@code true}, the re-lock will be cancelled by
     *       {@code MouseMixin.onLockCursor}, keeping the cursor unlocked.</li>
     * </ul>
     */
    @Inject(method = "onWindowFocusChanged", at = @At("HEAD"))
    private void justFarming$onWindowFocusChanged(boolean focused, CallbackInfo ci) {
        if (!JustFarming.isAnyMacroRoutineActive()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        if (!focused) {
            client.mouse.unlockCursor();
        } else {
            client.mouse.lockCursor();
        }
    }
}
