package com.justfarming.mixin;

import com.justfarming.pest.VacuumParticleTracker;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts client-side particle spawning to feed ANGRY_VILLAGER particles
 * into the {@link VacuumParticleTracker}.
 *
 * <p>When the player left-clicks with a vacuum item in the Hypixel Skyblock
 * Garden, the server fires a vacuum-shot projectile that emits a trail of
 * ANGRY_VILLAGER particles pointing toward the nearest pest.  By capturing the
 * positions of these particles the pest killer can compute the direction to the
 * pest and fly there.
 *
 * <p>In Minecraft 1.21.10, the particle-spawning methods are named
 * {@code addParticleClient} (replacing the old {@code addParticle} overload).
 */
@Mixin(ClientWorld.class)
public class ClientWorldParticleMixin {

    /**
     * Intercepts the 7-argument {@code addParticleClient} overload called for
     * normal server-sent particles.
     */
    @Inject(
            method = "addParticleClient(Lnet/minecraft/particle/ParticleEffect;DDDDDD)V",
            at = @At("HEAD"),
            require = 0
    )
    private void onAddParticleClient7(ParticleEffect parameters, double x, double y, double z,
                                      double velocityX, double velocityY, double velocityZ,
                                      CallbackInfo ci) {
        if (parameters == ParticleTypes.ANGRY_VILLAGER) {
            VacuumParticleTracker.getInstance().onParticle(x, y, z);
        }
    }

    /**
     * Intercepts the 9-argument {@code addParticleClient} overload (two boolean
     * flags: {@code force} and {@code shouldAlwaysPlay}).
     */
    @Inject(
            method = "addParticleClient(Lnet/minecraft/particle/ParticleEffect;ZZDDDDDD)V",
            at = @At("HEAD"),
            require = 0
    )
    private void onAddParticleClient9(ParticleEffect parameters, boolean force, boolean shouldAlwaysPlay,
                                      double x, double y, double z,
                                      double velocityX, double velocityY, double velocityZ,
                                      CallbackInfo ci) {
        if (parameters == ParticleTypes.ANGRY_VILLAGER) {
            VacuumParticleTracker.getInstance().onParticle(x, y, z);
        }
    }
}
