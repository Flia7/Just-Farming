package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ensures that the macro's movement inputs remain active even when a GUI
 * screen is open.
 *
 * <p>Minecraft's {@link KeyboardInput#tick()} resets (or skips updating)
 * {@code playerInput} and {@code movementVector} whenever
 * {@code MinecraftClient.currentScreen != null}, regardless of which keys are
 * programmatically held via {@link net.minecraft.client.option.KeyBinding#setPressed(boolean)}.
 * This causes the player to stop moving for as long as any screen is visible.
 *
 * <p>By injecting at {@code RETURN} – which fires even on early-return paths –
 * we re-apply the correct {@code playerInput} and pre-computed
 * {@code movementVector} after any screen-induced reset, so that the player
 * entity's movement physics in the same tick continue uninterrupted.
 *
 * <p>This mixin extends {@link Input} so that the compiler can resolve the
 * {@code playerInput} and {@code movementVector} fields, which are declared
 * in {@link Input} (the superclass of {@link KeyboardInput}) rather than in
 * {@link KeyboardInput} itself.  The {@code extends} clause is purely a
 * compile-time hint; at runtime the mixin body is merged into
 * {@link KeyboardInput}, which already extends {@link Input}.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {

    @Inject(method = "tick", at = @At("RETURN"))
    private void onTickReturn(CallbackInfo ci) {
        MacroManager mm = JustFarming.getMacroManager();
        if (mm == null || !mm.shouldBreak()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.currentScreen == null) return;

        boolean forward   = mm.isMovingForward();
        boolean back      = mm.isMovingBack();
        boolean strafeLeft  = mm.isStrafeLeft();
        boolean strafeRight = mm.isStrafeRight();

        // Re-apply the movement intent as a PlayerInput record.
        this.playerInput = new PlayerInput(
                forward,
                back,
                strafeLeft,
                strafeRight,
                false,      // jump
                false,      // sneak
                false       // sprint
        );

        // Also update movementVector so that getMovementInput() returns the
        // correct Vec2f when tickMovement() asks for it.
        // KeyboardInput.tick() computes this as:
        //   x = getMovementMultiplier(leftKey.isPressed(), rightKey.isPressed())
        //   y = getMovementMultiplier(forwardKey.isPressed(), backKey.isPressed())
        // With leftKey=true, rightKey=false → x = +1.0f
        // With forwardKey=true, backKey=false → y = +1.0f (forward)
        // With forwardKey=false, backKey=true  → y = -1.0f (backward)
        // Normalize to match vanilla KeyboardInput.tick() behavior.
        float x = strafeLeft ? 1.0f : (strafeRight ? -1.0f : 0.0f);
        float y = forward    ? 1.0f : (back        ? -1.0f : 0.0f);
        this.movementVector = (x == 0f && y == 0f) ? new Vec2f(0f, 0f) : new Vec2f(x, y).normalize();

        // Also refresh the KeyBinding press states so that any code path that reads
        // isPressed() directly (e.g. handleInputEvents in the same or next tick) sees
        // the correct values even if another unpressAll() fired after onTick().
        mm.reapplyMovementKeys();
    }
}
