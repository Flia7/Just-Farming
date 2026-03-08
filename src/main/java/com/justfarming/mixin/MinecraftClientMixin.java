package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

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
}
