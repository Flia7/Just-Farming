package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import com.justfarming.config.FarmingConfig;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    /**
     * When the macro is running <em>and</em> {@link FarmingConfig#macroEnabledInGui}
     * is enabled, skip the {@link KeyBinding#unpressAll()} call that
     * {@code setScreen()} makes every time a GUI is opened.
     *
     * <p>Normally {@code setScreen()} calls {@code unpressAll()} to release every
     * held key before showing a new screen.  For the macro this creates a brief
     * moment (one or more game ticks) where all movement and attack keys appear
     * unpressed, causing a visible micro-stutter.  Because the macro presses keys
     * programmatically (not via physical hardware), GLFW never sends a matching
     * "key released" hardware event, so skipping the call is safe: no physical
     * key state is lost and the macro continues without interruption.
     *
     * <p>When {@code macroEnabledInGui} is <em>disabled</em> the call proceeds
     * normally so Minecraft releases all keys as expected when a screen opens.
     * The macro's own {@code tickMoving} will also detect the open screen and
     * keep keys released on every subsequent tick.
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
        FarmingConfig cfg = JustFarming.getConfig();
        if (mm != null && mm.isRunning() && cfg != null && cfg.macroEnabledInGui) {
            return; // skip unpressAll only when macroEnabledInGui is active
        }
        KeyBinding.unpressAll();
    }
}
