package com.justfarming.mixin;

import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for {@link Input} – exposes the {@code playerInput} and
 * {@code movementVector} fields so that {@link KeyboardInputMixin} can
 * override them after the normal keyboard processing.
 */
@Mixin(Input.class)
public interface InputAccessor {

    @Accessor("playerInput")
    void setPlayerInput(PlayerInput playerInput);

    @Accessor("movementVector")
    void setMovementVector(Vec2f movementVector);
}
