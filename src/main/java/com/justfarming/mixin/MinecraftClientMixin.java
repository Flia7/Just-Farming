package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.client.option.KeyBinding;
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
     * resetting block-break progress when a screen is <em>closed</em>.  Because
     * the macro presses keys programmatically (not via physical hardware), GLFW
     * never sends a matching "key released" hardware event, so skipping the call
     * is safe: no physical key state is lost and the macro continues without
     * interruption.
     *
     * <p>The macro's own {@code tickMoving}/{@code isGuiBlocking()} logic is
     * solely responsible for releasing keys while a screen is open
     * ({@code macroEnabledInGui = false}) or keeping them held
     * ({@code macroEnabledInGui = true}).  Allowing {@code unpressAll()} to run
     * on GUI <em>close</em> would release the attack key for one tick, resetting
     * cocoa-bean and multi-hit break progress before the next tick re-presses it.
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
     * When a GUI screen closes (setScreen called with null) and the macro is
     * actively breaking blocks, immediately re-press the attack key so that
     * {@code handleBlockBreaking()} fires on the same tick the screen closes.
     *
     * <p>Without this, there is a one-tick window where the attack key is released
     * (because {@code tickMoving/releaseKeys} ran while the GUI was open) and
     * {@code handleBlockBreaking()} has already checked the key state before
     * {@code END_CLIENT_TICK} gives {@code tickMoving} a chance to re-press it.
     * The result is that block-break progress resets every time a GUI is closed
     * while the macro is running.
     */
    @Inject(method = "setScreen", at = @At("TAIL"))
    private void onSetScreen(Screen screen, CallbackInfo ci) {
        if (screen != null) return; // only act when a screen is being closed
        MacroManager mm = JustFarming.getMacroManager();
        if (mm != null && mm.shouldBreak()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.options != null) {
                mc.options.attackKey.setPressed(true);
            }
        }
    }
}
