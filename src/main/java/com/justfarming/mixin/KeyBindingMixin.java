package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyBinding.class)
public class KeyBindingMixin {

    /**
     * After {@link KeyBinding#unpressAll()} resets every key-binding state,
     * immediately re-press the movement and attack keys that the macro holds.
     *
     * <p>Minecraft calls {@code unpressAll()} from {@code MinecraftClient.setScreen()}
     * every time a GUI screen is opened (inventory, ESC menu, chat, etc.).  This
     * releases every held key, including the attack and movement keys the macro
     * programmatically holds via {@code setPressed(true)}.  The macro normally
     * re-presses them on the next END_CLIENT_TICK, but the one-tick gap causes the
     * player to stop moving for ~50 ms whenever a screen opens.
     *
     * <p>By re-applying only the keys the macro explicitly controls (and letting
     * {@code unpressAll()} clear everything else normally), we eliminate that brief
     * pause without interfering with other key-binding state expected by the game
     * or other mods.
     */
    @Inject(method = "unpressAll", at = @At("RETURN"))
    private static void onUnpressAllReturn(CallbackInfo ci) {
        MacroManager mm = JustFarming.getMacroManager();
        if (mm != null) {
            mm.reapplyMovementKeys();
        }
    }
}
