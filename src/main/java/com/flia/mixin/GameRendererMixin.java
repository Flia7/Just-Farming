package com.flia.mixin;

import com.flia.Flia;
import com.flia.MacroManager;
import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the first-person hand/item rendering while freelook is active,
 * so the held item is hidden just as it would be in a true third-person view.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void onRenderHand(float tickDelta, boolean bl, Matrix4f matrix, CallbackInfo ci) {
        MacroManager mm = Flia.getMacroManager();
        if (mm != null && mm.isFreelookActive()) {
            ci.cancel();
        }
    }
}
