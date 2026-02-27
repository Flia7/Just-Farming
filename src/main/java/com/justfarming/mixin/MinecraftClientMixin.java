package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    /**
     * When the macro is in a block-breaking phase, change the {@code breaking}
     * argument passed to {@code handleBlockBreaking} to {@code true}.
     *
     * <p>Vanilla computes this argument as:
     * <pre>currentScreen == null &amp;&amp; !bl3 &amp;&amp; attackKey.isPressed() &amp;&amp; isCursorLocked()</pre>
     * That expression evaluates to {@code false} whenever a GUI screen is open
     * (inventory, ESC menu, chat, …), which cancels the block-breaking progress
     * every tick the screen is visible.  By overriding the argument here we allow
     * breaking to continue uninterrupted regardless of which screen is open.
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
