package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Shadow public int attackCooldown;

    /**
     * Reset {@code attackCooldown} to zero at the start of input processing
     * while the macro is in a block-breaking phase.
     *
     * <p>Minecraft sets {@code attackCooldown = 10000} every tick that a GUI
     * screen is open.  Because the macro keeps {@code attackKey.isPressed()}
     * {@code true}, {@code handleBlockBreaking} receives {@code breaking=true},
     * which means the vanilla {@code "if (!breaking) attackCooldown = 0"} reset
     * path never fires.  Without this injection, after any screen closes the
     * cooldown must drain naturally (up to 10 000 ticks ≈ 8 minutes) before
     * {@code handleBlockBreaking} can call
     * {@code updateBlockBreakingProgress} again.
     */
    @Inject(method = "handleInputEvents", at = @At("HEAD"))
    private void resetAttackCooldownWhileMacroBreaking(CallbackInfo ci) {
        MacroManager mm = JustFarming.getMacroManager();
        if (mm != null && mm.shouldBreak()) {
            this.attackCooldown = 0;
        }
    }

    /**
     * When the macro is in a block-breaking phase, change the {@code breaking}
     * argument passed to {@code handleBlockBreaking} to {@code true}.
     *
     * <p>Vanilla computes this argument as:
     * <pre>currentScreen == null &amp;&amp; !bl3 &amp;&amp; attackKey.isPressed() &amp;&amp; isCursorLocked()</pre>
     * When no screen is open this evaluates to {@code true} naturally (the macro
     * holds {@code attackKey} pressed and {@link MouseMixin} makes
     * {@code isCursorLocked()} return {@code true}).  The override acts as a
     * safety net for any edge case where the expression might still be
     * {@code false} (e.g. {@code bl3} set by a physical key-press coinciding
     * with a macro tick).
     */
    @ModifyArg(
            method = "handleInputEvents",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;handleBlockBreaking(Z)V"),
            index = 0
    )
    private boolean forceBlockBreakingWhileMacroRunning(boolean breaking) {
        MacroManager mm = JustFarming.getMacroManager();
        if (mm != null && mm.shouldBreak()) {
            return true;
        }
        return breaking;
    }
}
