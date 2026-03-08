package com.justfarming.mixin;

import com.justfarming.access.ClientPlayerInteractionManagerExtension;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Exposes a quiet reset for the block-breaking state in
 * {@link ClientPlayerInteractionManager} so the farming macro can ensure a
 * clean packet sequence on each break tick.
 *
 * <p>Minecraft normally tracks the current breaking block in {@code breakingBlock}
 * and {@code currentBreakingPos}.  When these are non-null on the tick that
 * {@link #justFarming$resetBreakingState()} is called, any subsequent
 * {@code attackBlock()} call proceeds as if no previous break was in progress –
 * sending only {@code START_DESTROY_BLOCK} without an {@code ABORT_DESTROY_BLOCK}
 * prefix.  This matches the behaviour observed after a GUI screen is opened and
 * closed (where Minecraft itself resets the state during the screen transition)
 * and eliminates the "weird packets" reported before a GUI has ever been opened.
 */
@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin implements ClientPlayerInteractionManagerExtension {

    @Shadow
    private boolean breakingBlock;

    @Shadow
    private BlockPos currentBreakingPos;

    /**
     * {@inheritDoc}
     *
     * <p>Silently clears the current block-breaking state without sending any
     * {@code ABORT_DESTROY_BLOCK} packet to the server.
     *
     * <p>Called from {@code MacroManager.directBreakBlock()} immediately
     * before each {@code attackBlock()} invocation so that:
     * <ol>
     *   <li>No stale {@code ABORT_DESTROY_BLOCK} is prepended to the
     *       {@code START_DESTROY_BLOCK} for the new crop.</li>
     *   <li>The packet stream seen by Hypixel's server is identical to the
     *       clean stream produced after a GUI is opened and closed.</li>
     * </ol>
     */
    @Override
    @Unique
    public void justFarming$resetBreakingState() {
        breakingBlock      = false;
        currentBreakingPos = null;
    }
}
