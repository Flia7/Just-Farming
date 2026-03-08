package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import net.minecraft.client.MinecraftClient;
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
     * Detect when a GUI screen is closed ({@code screen == null}) and start the
     * 1-second cursor-ungrab grace period in {@link MouseMixin}.  This prevents
     * Minecraft from immediately re-grabbing the cursor as the screen closes,
     * which causes a perceptible micro-stutter in mouse input.
     */
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void onSetScreen(Screen screen, CallbackInfo ci) {
        if (screen == null) {
            MouseMixin.notifyGuiClosed();
        }
    }

    /**
     * Suppress vanilla's {@code handleBlockBreaking()} call whenever the farming
     * macro is actively breaking blocks.
     *
     * <p>The macro calls {@link MacroManager#directBreakBlock()} directly every
     * tick to send break packets to the server, which is reliable regardless of
     * whether a GUI screen is open or closed.  Allowing vanilla's
     * {@code handleBlockBreaking()} to also run would cause it to either call
     * {@code cancelBlockBreaking()} (when {@code breaking=false}, e.g. because a
     * screen is open) and send an {@code ABORT_DESTROY_BLOCK} packet that resets
     * server-side break progress, or to call {@code updateBlockBreakingProgress()}
     * a second time in the same tick and produce double-break artifacts.
     * Suppressing it here gives the macro full ownership of the break loop.
     */
    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void onHandleBlockBreaking(boolean breaking, CallbackInfo ci) {
        MacroManager mm = JustFarming.getMacroManager();
        if (mm != null && mm.shouldBreak()) {
            ci.cancel();
        }
    }
}
