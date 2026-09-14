package com.singularity_iteration.mio_icif.energy;

import com.singularity_iteration.mio_icif.api.energy.IEnergyStorageAccess;
import com.singularity_iteration.mio_icif.api.energy.tile.IExplosionPowerOverride;
import com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier;
import com.singularity_iteration.mio_icif.energy.EnergyUnit.IEUEnergyStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

@SuppressWarnings("null")
public class CustomEUEnergyStorage implements IEUEnergyStorage, IEnergyStorageAccess {

    protected long energy;
    protected long capacity;
    protected long maxReceive;
    protected long maxExtract;
    protected final CableTier cableTier;

    protected BlockPos pos;
    protected Level level;
    
    protected long powerOutput = 0;
    protected boolean isPowerSource = false;
    protected boolean outputEnabled = true; // 红石控制输出开关

    // Independent-network ownership is explicit and fixed by the containing
    // block's development policy before its saved balance is read.
    private boolean scexNetworkControlled;
    private dev.scex.energy.NetworkCell scexCell;
    public final boolean scexNetworkControlled() { return scexNetworkControlled; }
    public final void scexSetNetworkControlled(boolean controlled) {
        if (controlled == scexNetworkControlled) return;
        if (scexCell != null) { scexCell.retire(); scexCell = null; }
        scexNetworkControlled = controlled;
    }

    public record NetworkQuote(Level ownerLevel, BlockPos ownerPosition, long amount, long capacity, long maxReceive, long maxExtract,
                               long output, boolean source, boolean outputEnabled, dev.scex.energy.NetworkCell.Quote cell) { }
    public record NetworkWrite(CustomEUEnergyStorage storage, NetworkQuote expected, long nextAmount) { }

    public final NetworkQuote scexNetworkQuote() {
        dev.scex.energy.NetworkCell.Quote cellQuote = null;
        if (scexNetworkControlled) {
            if (!(level instanceof ServerLevel world) || !world.getServer().isSameThread())
                throw new IllegalStateException("Controlled quote requires server thread");
            if (scexCell == null) scexCell = new dev.scex.energy.NetworkCell(energy);
            cellQuote = scexCell.quote();
            if (cellQuote.amount() != energy) throw new IllegalStateException("Independent balance mirror diverged");
        }
        return new NetworkQuote(level, pos == null ? null : pos.immutable(), energy, capacity, maxReceive, maxExtract, powerOutput, isPowerSource, outputEnabled, cellQuote);
    }

    /**
     * An independently authored, all-or-nothing main-thread balance commit.
     * Validation and conservation precede every write. The mutation loop only
     * assigns primitive fields; dirty marking follows the complete commit.
     * No old grid/API method is invoked and nominal capacity is not a clamp.
     */
    public static boolean scexCommitNetwork(java.util.List<NetworkWrite> requested,
                                            long dissipated, java.util.function.BooleanSupplier current) {
        return scexCommitNetwork(requested, java.util.List.of(), dissipated, current);
    }

    /**
     * Join independently owned endpoint cells to the same numeric transaction.
     * Their platform identity and policy snapshots must be covered by current;
     * NetworkCell additionally enforces thread ownership and revision validity.
     * No extra cell is wrapped through a legacy energy interface or setter.
     */
    public static boolean scexCommitNetwork(java.util.List<NetworkWrite> requested,
                                            java.util.List<dev.scex.energy.NetworkCell.Write> additional,
                                            long dissipated, java.util.function.BooleanSupplier current) {
        var writes = java.util.List.copyOf(requested);
        var extraWrites = java.util.List.copyOf(additional);
        if (dissipated < 0) throw new IllegalArgumentException("Negative dissipation");
        var identities = new java.util.IdentityHashMap<CustomEUEnergyStorage, Boolean>();
        var balance = java.math.BigInteger.valueOf(dissipated);
        for (var write : writes) {
            var storage = java.util.Objects.requireNonNull(write.storage());
            if (write.nextAmount() < 0 || identities.put(storage, Boolean.TRUE) != null)
                throw new IllegalArgumentException("Negative balance or duplicate storage");
            if (storage.pos == null || !(storage.level instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread())
                throw new IllegalStateException("Network commit requires a server-owned storage");
            balance = balance.add(java.math.BigInteger.valueOf(write.nextAmount()))
                .subtract(java.math.BigInteger.valueOf(write.expected().amount()));
        }
        for (var write : extraWrites) {
            balance = balance.add(java.math.BigInteger.valueOf(write.nextAmount()))
                .subtract(java.math.BigInteger.valueOf(write.expected().amount()));
        }
        if (balance.signum() != 0) throw new IllegalArgumentException("Nonconserving network commit");
        if (!current.getAsBoolean()) return false;
        for (var write : writes) {
            if (!write.storage().scexNetworkControlled || !write.storage().scexNetworkQuote().equals(write.expected())) return false;
            var storage = write.storage(); var world = (ServerLevel) storage.level;
            var chunk = world.getChunkSource().getChunkNow(storage.pos.getX() >> 4, storage.pos.getZ() >> 4);
            if (chunk == null || !world.shouldTickBlocksAt(net.minecraft.world.level.ChunkPos.asLong(storage.pos))) return false;
            var tile = chunk.getBlockEntity(storage.pos, net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.CHECK);
            if (!(tile instanceof com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block machine)
                || tile.isRemoved() || machine.getEnergyStorageInternal() != storage) return false;
        }
        var cellWrites = new java.util.ArrayList<dev.scex.energy.NetworkCell.Write>();
        for (var write : writes) cellWrites.add(new dev.scex.energy.NetworkCell.Write(write.expected().cell(), write.nextAmount()));
        cellWrites.addAll(extraWrites);
        if (!dev.scex.energy.NetworkCell.commit(cellWrites, dissipated, () -> true)) return false;
        // Mirror the completed owned-state transaction without invoking setters
        // or callbacks; existing save and local machine readers still use energy.
        for (var write : writes) write.storage().energy = write.nextAmount();
        for (var write : writes) {
            var storage = write.storage();
            if (storage.energy == write.expected().amount()) continue;
            var serverLevel = (ServerLevel) storage.level;
            var chunk = serverLevel.getChunkSource().getChunkNow(storage.pos.getX() >> 4, storage.pos.getZ() >> 4);
            if (chunk != null) chunk.setUnsaved(true);
        }
        return true;
    }

    public CustomEUEnergyStorage(long capacity, long maxReceive, long maxExtract, CableTier cableTier) {
        this.capacity = capacity;
        this.maxReceive = maxReceive;
        this.maxExtract = maxExtract;
        this.cableTier = cableTier;
        this.energy = 0;
    }

    public CustomEUEnergyStorage(long capacity, long maxTransfer, CableTier cableTier) {
        this(capacity, maxTransfer, maxTransfer, cableTier);
    }

    // Explicitly override to resolve duplicate default method conflict between IEnergyStorageAccess and ILongEnergyStorage
    @Override
    public int getEnergyStored() {
        return (int) Math.min(energy, Integer.MAX_VALUE);
    }

    @Override
    public int getMaxEnergyStored() {
        return (int) Math.min(capacity, Integer.MAX_VALUE);
    }

    public void setBlockContext(Level level, BlockPos pos) {
        this.level = level;
        this.pos = pos;
    }

    public void setAsPowerSource(long powerOutput) {
        this.isPowerSource = true;
        this.powerOutput = powerOutput;
    }

    public long getPowerOutput() {
        return isPowerSource ? powerOutput : 0;
    }

    public boolean isPowerSource() {
        return isPowerSource;
    }
    
    /**
     * 设置能量输出是否启用（红石控制）
     * @param enabled true=允许输出, false=禁止输出
     */
    public void setOutputEnabled(boolean enabled) {
        this.outputEnabled = enabled;
    }
    
    /**
     * 检查能量输出是否启用
     */
    public boolean isOutputEnabled() {
        return this.outputEnabled;
    }

    public long getPowerRating() {
        return cableTier.getPowerRating();
    }

    public long extractPowerForConsumer(long amount, boolean simulate) {
        if (!isPowerSource) {
            return 0;
        }
        return extract(amount, simulate);
    }

    public boolean isOverloaded(long gridPower) {
        return gridPower > getPowerRating();
    }

    public void triggerOverloadExplosion() {
        if (level == null || pos == null) {
            return;
        }

        if (!level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof IExplosionPowerOverride override && !override.shouldExplode()) {
                return;
            }

            float explosionPower = calculateExplosionPower();
            if (be instanceof IExplosionPowerOverride override) {
                explosionPower = override.getExplosionPower(cableTier.getTierIndex(), explosionPower);
            }

            if (explosionPower > 0.0F) {
                level.explode(
                    null,
                    pos.getX() + 0.5,
                    pos.getY() + 0.5,
                    pos.getZ() + 0.5,
                    explosionPower,
                    Level.ExplosionInteraction.BLOCK
                );
            }

            level.removeBlock(pos, false);
        }
    }

    protected float calculateExplosionPower() {
        return (float) Math.min(cableTier.powerRating / 1024.0D, 2.0D + cableTier.tierIndex * 0.5D);
    }

    /**
     * 检查实现 {@link IExplosionPowerOverride} 的方块是否应爆炸。
     * 未实现该接口时默认返回 true。
     */
    public static boolean shouldTileExplode(BlockEntity tile) {
        return !(tile instanceof IExplosionPowerOverride override) || override.shouldExplode();
    }

    /**
     * 解析实现 {@link IExplosionPowerOverride} 的方块的实际爆炸威力。
     * 未实现该接口时返回 basePower。
     */
    public static float resolveTileExplosionPower(BlockEntity tile, int tier, float basePower) {
        if (tile instanceof IExplosionPowerOverride override) {
            return override.getExplosionPower(tier, basePower);
        }
        return basePower;
    }

    @Override
    public boolean canConnect(CableTier cableTier) {
        return cableTier.powerRating <= this.cableTier.powerRating;
    }

    @Override
    public long receive(long maxReceive, boolean simulate) {
        if (maxReceive <= 0) return 0;
        long energyReceived = Math.min(Math.max(0, capacity - energy), Math.min(this.maxReceive, maxReceive));
        if (!simulate) {
            setEnergy(energy + energyReceived);
        }
        return energyReceived;
    }

    @Override
    public long extract(long maxExtract, boolean simulate) {
        if (maxExtract <= 0) return 0;
        // 如果输出被禁用（红石控制），则不允许提取能量
        if (!this.outputEnabled) {
            return 0;
        }
        long energyExtracted = Math.min(energy, Math.min(this.maxExtract, maxExtract));
        if (!simulate) {
            setEnergy(energy - energyExtracted);
        }
        return energyExtracted;
    }

    @Override
    public long getAmount() {
        return energy;
    }

    @Override
    public long getCapacity() {
        return capacity;
    }

    @Override
    public void setStored(long amount) {
        setEnergy(amount);
    }

    @Override
    public boolean canExtract() {
        return maxExtract > 0;
    }

    @Override
    public boolean canReceive() {
        return maxReceive > 0;
    }

    public void setEnergy(long energy) {
        long updated = Math.max(0, scexNetworkControlled ? energy : Math.min(capacity, energy));
        if (updated == this.energy) return;
        if (scexCell != null) scexCell.replace(updated);
        this.energy = updated;
        // Normal chunk saves skip clean chunks. Energy changes must persist even
        // between completed operations, without serializing NBT or notifying all
        // comparators every tick. Never load a chunk merely to mark it dirty.
        if (level instanceof ServerLevel serverLevel && pos != null) {
            var chunk = serverLevel.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk != null) chunk.setUnsaved(true);
        }
    }

    public long getMaxExtract() {
        return maxExtract;
    }

    public long getMaxReceive() {
        return maxReceive;
    }

    public void setCapacity(long capacity) {
        this.capacity = Math.max(0, capacity);
        setEnergy(this.energy);
    }

    public void setMaxReceive(long maxReceive) {
        this.maxReceive = Math.max(0, maxReceive);
    }

    public void setMaxExtract(long maxExtract) {
        this.maxExtract = Math.max(0, maxExtract);
    }

    public CableTier getCableTier() {
        return cableTier;
    }

    public long consumeEnergyInternal(long amount, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        long energyConsumed = Math.min(this.energy, amount);
        if (!simulate) {
            setEnergy(this.energy - energyConsumed);
        }
        return energyConsumed;
    }

    public long generateEnergyInternal(long amount, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        long energyGenerated = Math.min(Math.max(0, this.capacity - this.energy), amount);
        if (!simulate) {
            setEnergy(this.energy + energyGenerated);
        }
        return energyGenerated;
    }

    @Override
    public long useEnergy(long amount, boolean simulate) {
        return consumeEnergyInternal(amount, simulate);
    }

    @Override
    public long generateEnergy(long amount, boolean simulate) {
        return generateEnergyInternal(amount, simulate);
    }
}
