package com.justfarming.access;

/**
 * Extension interface applied to {@link net.minecraft.client.network.ClientPlayerInteractionManager}
 * by {@link com.justfarming.mixin.ClientPlayerInteractionManagerMixin}.
 *
 * <p>Provides a quiet block-breaking state reset so the farming macro can
 * ensure a clean {@code START_DESTROY_BLOCK} packet sequence on each tick
 * without emitting a spurious {@code ABORT_DESTROY_BLOCK} packet.
 */
public interface ClientPlayerInteractionManagerExtension {

    /**
     * Silently resets the interaction manager's block-breaking state
     * ({@code breakingBlock = false}, {@code currentBreakingPos = null})
     * without sending an {@code ABORT_DESTROY_BLOCK} packet to the server.
     */
    void justFarming$resetBreakingState();
}
