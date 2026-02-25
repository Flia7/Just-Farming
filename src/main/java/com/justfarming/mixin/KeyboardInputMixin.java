package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects the macro's desired movement into {@link KeyboardInput} at the end
 * of every tick.  Because we write directly to the {@link net.minecraft.client.input.Input}
 * fields (via {@link InputAccessor}) rather than relying on virtual key-presses,
 * movement continues to work even when a Minecraft screen (e.g. chat) is open.
 *
 * <p>Movement values are provided by {@link MacroManager#getDesiredMovementForward()}
 * and {@link MacroManager#getDesiredMovementSideways()}.  These are set in the
 * macro's tick logic and are zero when the macro is idle.
 */
@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void justFarming$injectMovement(CallbackInfo ci) {
        MacroManager macro = JustFarming.getMacroManager();
        if (macro == null || !macro.isRunning()) return;

        float fwd  = macro.getDesiredMovementForward();
        float side = macro.getDesiredMovementSideways();

        if (fwd == 0f && side == 0f) return;

        boolean goForward  = fwd  > 0f;
        boolean goBackward = fwd  < 0f;
        boolean goLeft     = side < 0f;
        boolean goRight    = side > 0f;

        InputAccessor accessor = (InputAccessor) (Object) this;

        accessor.setPlayerInput(
                new PlayerInput(goForward, goBackward, goLeft, goRight, false, false, false));

        // Replicate KeyboardInput's movement-multiplier logic (positive key → 1f, negative → -1f)
        float sideMultiplier = (goLeft == goRight) ? 0f : (goLeft ? 1f : -1f);
        float fwdMultiplier  = (goForward == goBackward) ? 0f : (goForward ? 1f : -1f);
        accessor.setMovementVector(new Vec2f(sideMultiplier, fwdMultiplier).normalize());
    }
}
