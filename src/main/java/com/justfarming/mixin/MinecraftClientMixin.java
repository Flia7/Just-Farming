package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import com.justfarming.config.FarmingConfig;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Shadow public int attackCooldown;

    /**
     * Reset {@code attackCooldown} to zero at the start of input processing
     * while the macro is in a block-breaking phase.
     *
     * <p>This early reset is a safety-net for any code path in
     * {@code handleInputEvents} that might re-set the cooldown to 10 000 before
     * reaching the {@code handleBlockBreaking} call.  The definitive reset that
     * guarantees correctness is performed inside
     * {@link #forceBlockBreakingWhileMacroRunning}, which fires <em>immediately</em>
     * before {@code handleBlockBreaking} is invoked.
     */
    @Inject(method = "handleInputEvents", at = @At("HEAD"))
    private void resetAttackCooldownWhileMacroBreaking(CallbackInfo ci) {
        MacroManager mm = JustFarming.getMacroManager();
        if (mm != null && mm.shouldBreak()) {
            this.attackCooldown = 0;
        }
    }

    /**
     * When the macro is in a block-breaking phase:
     * <ol>
     *   <li>Reset {@code attackCooldown} to 0 right before the call so that
     *       any intervening assignment of 10 000 (which Minecraft performs when
     *       a GUI screen is open) does not prevent {@code handleBlockBreaking}
     *       from advancing the break progress.</li>
     *   <li>Change the {@code breaking} argument to {@code true} so that
     *       {@code handleBlockBreaking} actually tries to break the targeted
     *       block even when {@code currentScreen != null}.</li>
     * </ol>
     *
     * <p>Vanilla computes the argument as:
     * <pre>currentScreen == null &amp;&amp; !bl3 &amp;&amp; attackKey.isPressed() &amp;&amp; isCursorLocked()</pre>
     * This is {@code false} whenever a screen is open, but we override it to
     * keep breaking crops while the macro is active and
     * {@link FarmingConfig#macroEnabledInGui} is enabled.
     * {@link MacroManager#shouldBreak()} already returns {@code false} when
     * {@code macroEnabledInGui} is disabled, so no additional check is needed here.
     */
    @ModifyArg(
            method = "handleInputEvents",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;handleBlockBreaking(Z)V"),
            index = 0
    )
    private boolean forceBlockBreakingWhileMacroRunning(boolean breaking) {
        MacroManager mm = JustFarming.getMacroManager();
        if (mm != null && mm.shouldBreak()) {
            this.attackCooldown = 0; // ensure cooldown is 0 right at the call site
            return true;
        }
        return breaking;
    }

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
