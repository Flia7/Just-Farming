package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents {@code ClientPlayerEntity.tickMovement()} from zeroing the player's
 * movement inputs while a GUI screen is open and the macro is running.
 *
 * <p>Even after {@link KeyboardInputMixin} re-asserts the correct
 * {@code playerInput} and {@code movementVector} at the end of
 * {@link net.minecraft.client.input.KeyboardInput#tick()}, Minecraft's
 * {@code tickMovement()} may subsequently overwrite
 * {@code input.playerInput} with an all-false {@link PlayerInput} whenever
 * {@code client.currentScreen != null}.  Injecting immediately after each such
 * {@code PUTFIELD} instruction in {@code tickMovement()} and re-writing the
 * correct macro values ensures the movement physics that follow in the same
 * method execute with the intended inputs.
 *
 * <p>{@code require = 0} is intentional: if the target version of Minecraft
 * does not have a {@code PUTFIELD} for {@code Input.playerInput} inside
 * {@code tickMovement()} (because the zeroing was moved to
 * {@link net.minecraft.client.input.KeyboardInput#tick()} instead),
 * the mixin simply does nothing and {@link KeyboardInputMixin} is sufficient.
 */
@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityMixin {

    @Shadow
    public Input input;

    @Inject(
            method = "tickMovement",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/input/Input;playerInput:Lnet/minecraft/util/PlayerInput;",
                    opcode = Opcodes.PUTFIELD,
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void afterPlayerInputZeroed(CallbackInfo ci) {
        MacroManager mm = JustFarming.getMacroManager();
        if (mm == null || !mm.shouldBreak()) return;

        this.input.playerInput = new PlayerInput(
                mm.isMovingForward(),
                mm.isMovingBack(),
                mm.isStrafeLeft(),
                mm.isStrafeRight(),
                false,      // jump
                false,      // sneak
                false       // sprint
        );
    }
}
