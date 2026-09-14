// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy.minecraft;

import dev.scex.energy.NetworkCell;
import dev.scex.energy.TransformerAccounting;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Independently authored platform entity. Registration, mode interpretation,
 * direction routing and replacement of the host block's ticker are supplied by
 * its integration. It neither implements nor calls an old mod energy API.
 *
 * <p>The ordinary buffer/mode tag names come from retained SI runtime saves.
 * Unsupported numeric saves are preserved, not truncated into an energy quote.
 */
public final class IndependentTransformerBlockEntity extends BlockEntity {
    private final long lowPacket;
    private double savedBuffer;
    private int savedMode;
    private NetworkCell cell;
    private boolean modeObserved, observedStepUp;

    public record Snapshot(NetworkCell.Quote energy, BlockState state, int mode,
                           boolean stepUp, TransformerAccounting.Configuration limits) { }

    public IndependentTransformerBlockEntity(BlockEntityType<?> type, BlockPos position,
            BlockState state, long lowPacket, int initialMode) {
        super(type, position, state);
        // Validate the tier using the independently measured numeric policy.
        new TransformerAccounting.Configuration(lowPacket, false);
        this.lowPacket = lowPacket;
        savedMode = initialMode;
    }

    private void requireServerThread() {
        if (!(level instanceof ServerLevel world) || !world.getServer().isSameThread())
            throw new IllegalStateException("Transformer storage requires its server thread");
    }

    /** Caller supplies the interpreted mode; raw mode numbers have no guessed meaning here. */
    public Optional<Snapshot> snapshot(boolean stepUp) {
        requireServerThread();
        if (isRemoved()) return Optional.empty();
        if (cell == null) {
            // Ordinary double saves cannot preserve every integer beyond 2^53.
            // Retain out-of-scope input for a future migration rather than lose it.
            if (!Double.isFinite(savedBuffer) || savedBuffer < 0
                    || savedBuffer > 9_007_199_254_740_992d || Math.rint(savedBuffer) != savedBuffer)
                return Optional.empty();
            cell = new NetworkCell((long) savedBuffer);
        }
        return Optional.of(new Snapshot(cell.quote(), getBlockState(), savedMode, stepUp,
            new TransformerAccounting.Configuration(lowPacket, stepUp)));
    }

    /** World/chunk registration identity must additionally be checked by the engine. */
    public boolean isCurrent(Snapshot expected, boolean currentStepUp) {
        requireServerThread();
        return !isRemoved() && cell != null && expected.energy() == cell.quote()
            && expected.state() == getBlockState() && expected.mode() == savedMode
            && expected.stepUp() == currentStepUp
            && expected.limits().equals(new TransformerAccounting.Configuration(lowPacket, currentStepUp));
    }

    public int savedMode() { return savedMode; }

    /** Existing SI fixed modes remain 0/1. Mode 2 is this independent adapter's automatic mode. */
    public boolean validMode() { return savedMode >= 0 && savedMode <= 2; }

    public boolean stepUpNow() {
        requireServerThread();
        return savedMode == 0 || savedMode == 2 && level.hasNeighborSignal(worldPosition);
    }

    /** Observe public redstone input once per world frame; energy changes do not renew routing. */
    public boolean refreshMode() {
        requireServerThread();
        boolean stepUp = stepUpNow();
        boolean changed = modeObserved && observedStepUp != stepUp;
        modeObserved = true;
        observedStepUp = stepUp;
        if (changed) {
            if (cell != null) cell.replace(cell.quote().amount());
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
        }
        return changed;
    }

    public int routingSignature() { return savedMode * 2 + (stepUpNow() ? 1 : 0); }

    public void setSavedMode(int mode) {
        requireServerThread();
        if (mode == savedMode) return;
        if (cell != null) cell.replace(cell.quote().amount());
        savedMode = mode;
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }

    /** Called after the complete numeric transaction, never from inside its write phase. */
    public void markNetworkChanged() { requireServerThread(); setChanged(); }

    private void revokeCell() {
        if (cell == null) return;
        savedBuffer = cell.quote().amount();
        cell.retire();
        cell = null;
    }

    @Override public void setLevel(Level newLevel) {
        if (level != newLevel) revokeCell();
        super.setLevel(newLevel);
    }

    @Override public void setRemoved() {
        revokeCell();
        super.setRemoved();
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        revokeCell();
        super.loadAdditional(tag, registries);
        savedBuffer = tag.getDouble("buffer");
        if (tag.contains("mode")) savedMode = tag.getInt("mode");
        modeObserved = false;
        observedStepUp = tag.getBoolean("active");
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("buffer", cell == null ? savedBuffer : cell.quote().amount());
        tag.putInt("mode", savedMode);
        tag.putBoolean("active", observedStepUp);
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
