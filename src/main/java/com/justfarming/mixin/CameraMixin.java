package com.justfarming.mixin;

import com.justfarming.CameraOverriddenEntity;
import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {

    @Unique
    private boolean firstTime = true;

    @Shadow
    private boolean thirdPerson;

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void moveBy(float x, float y, float z);

    @Inject(method = "update", at = @At("TAIL"))
    private void lockRotation(BlockView area, Entity cameraEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
        MacroManager mm = JustFarming.getMacroManager();
        if (!(cameraEntity instanceof ClientPlayerEntity player)) {
            firstTime = true;
            return;
        }
        if (mm == null || !mm.isFreelookActive()) {
            firstTime = true;
            return;
        }
        CameraOverriddenEntity coe = (CameraOverriddenEntity) cameraEntity;
        if (firstTime) {
            coe.freelook$setCameraPitch(player.getPitch());
            coe.freelook$setCameraYaw(player.getYaw());
            firstTime = false;
        }
        this.setRotation(coe.freelook$getCameraYaw(), coe.freelook$getCameraPitch());
        // Offset camera behind the player at the configured zoom distance.
        // Use the raw zoom value so the camera passes through blocks smoothly
        // instead of snapping/glitching when walls are nearby.
        float dist = (float) mm.getFreelookZoom();
        this.moveBy(-dist, 0.0f, 0.0f);
        // Mark the camera as third-person so Minecraft renders the player entity
        // instead of hiding it (which happens in first-person mode).
        this.thirdPerson = true;
    }
}
