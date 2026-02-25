package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.MathHelper;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Overrides the camera's yaw and pitch when freelook is active so the player
 * can look around freely without the farming macro changing their movement direction.
 */
@Mixin(Camera.class)
public class CameraMixin {

    @Shadow private float yaw;
    @Shadow private float pitch;
    @Shadow @Final private Quaternionf rotation;

    @Inject(method = "update", at = @At("TAIL"))
    private void overrideCameraForFreelook(CallbackInfo ci) {
        MacroManager mm = JustFarming.getMacroManager();
        if (mm == null || !mm.isFreelookActive()) return;

        float newYaw   = mm.getFreelookCameraYaw();
        float newPitch = mm.getFreelookCameraPitch();

        this.yaw   = newYaw;
        this.pitch = newPitch;
        this.rotation.rotationYXZ(
                -newYaw   * MathHelper.RADIANS_PER_DEGREE,
                 newPitch * MathHelper.RADIANS_PER_DEGREE,
                 0.0f);
    }
}
