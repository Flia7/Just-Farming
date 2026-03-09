package com.justfarming.mixin;

import com.justfarming.JustFarming;
import com.justfarming.MacroManager;
import com.justfarming.access.ClientPlayerInteractionManagerExtension;
import com.justfarming.profit.FarmingProfitTracker;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
 *
 * <p>Also hooks {@link #breakBlock(BlockPos)} to register each successful block
 * break with the farming profit tracker so the BPS (blocks-per-second) counter
 * works correctly when vanilla's {@code handleBlockBreaking} drives the breaking
 * (attack key held) rather than direct {@code attackBlock} calls.
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

    /**
     * Registers a block break with the farming profit tracker each time a block
     * is successfully destroyed while the farming macro is actively breaking crops.
     *
     * <p>This hook fires at the entry point of
     * {@link ClientPlayerInteractionManager#breakBlock(BlockPos)} which vanilla
     * calls when the block's destruction progress reaches 1.0 (i.e., when the
     * block is actually broken).  Only counts breaks when the macro's
     * {@link MacroManager#shouldBreak()} is true so accidental non-macro breaks
     * are not included in the BPS average.
     */
    @Inject(method = "breakBlock", at = @At("HEAD"))
    private void justFarming$onBreakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        MacroManager mm = JustFarming.getMacroManager();
        if (mm == null || !mm.shouldBreak()) return;
        FarmingProfitTracker tracker = JustFarming.getProfitTracker();
        if (tracker != null) {
            tracker.registerBlockBreak();
        }
    }
}
