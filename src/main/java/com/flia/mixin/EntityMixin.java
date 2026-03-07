package com.flia.mixin;

import com.flia.CameraOverriddenEntity;
import com.flia.Flia;
import com.flia.MacroManager;
import com.flia.config.FarmingConfig;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class EntityMixin implements CameraOverriddenEntity {

    @Unique
    private float cameraPitch;

    @Unique
    private float cameraYaw;

    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void changeCameraLookDirection(double xDelta, double yDelta, CallbackInfo ci) {
        //noinspection ConstantValue
        if (!((Object) this instanceof ClientPlayerEntity)) return;
        MacroManager mm = Flia.getMacroManager();
        if (mm == null) return;

        FarmingConfig cfg = Flia.getConfig();

        // When the macro is running in ungrab mode, the GLFW cursor is physically
        // free so mouse movement would normally spin the camera.  Suppress all look
        // direction changes so the view stays locked at the configured pitch/yaw.
        if (mm.isRunning() && cfg != null && cfg.unlockedMouseEnabled && !mm.isFreelookActive()) {
            ci.cancel();
            return;
        }

        // When a GUI screen is open (esc, inventory, chat, etc.) suppress camera
        // movement so the player cannot accidentally move the view.
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client != null && client.currentScreen != null) {
            ci.cancel();
            return;
        }

        if (!mm.isFreelookActive()) return;

        double pitchDelta = yDelta * 0.15;
        double yawDelta = xDelta * 0.15;

        this.cameraPitch = MathHelper.clamp(this.cameraPitch + (float) pitchDelta, -90.0f, 90.0f);
        this.cameraYaw += (float) yawDelta;

        ci.cancel();
    }

    @Override
    @Unique
    public float freelook$getCameraPitch() {
        return this.cameraPitch;
    }

    @Override
    @Unique
    public float freelook$getCameraYaw() {
        return this.cameraYaw;
    }

    @Override
    @Unique
    public void freelook$setCameraPitch(float pitch) {
        this.cameraPitch = pitch;
    }

    @Override
    @Unique
    public void freelook$setCameraYaw(float yaw) {
        this.cameraYaw = yaw;
    }
}
