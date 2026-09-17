// SPDX-License-Identifier: Apache-2.0
package com.singularity_iteration.mio_icif.Blocks.entity.producer;

import com.singularity_iteration.mio_icif.Blocks.Environment.fluid.mio_icif_fluids;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_block_entities;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_producer;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.SlotLayout;
import com.singularity_iteration.mio_icif.Items.Cell.mio_icif_cells;
import com.singularity_iteration.mio_icif.Menu.Producer.CondenserMenu;
import com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier;
import dev.scex.si.energy.ContainerToTank;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.Nullable;

/**
 * Independent condenser: R50 public input/output observations, no predecessor body used.
 * Unaccelerated steam conversion is implemented. Vent acceleration and legacy tank migration
 * need their own evidence; holding their inventory/data does not assert those features complete.
 */
public class mio_icif_condenser extends mio_icif_producer {
    public static final int TOTAL_SLOTS = 8;
    public static final int VENT_SLOT_1 = 0, VENT_SLOT_2 = 1, VENT_SLOT_3 = 2, VENT_SLOT_4 = 3;
    public static final int INPUT_BUCKET_SLOT = 4, EMPTY_BUCKET_SLOT = 5, BATTERY_SLOT = 6, UPGRADE_SLOT = 7;
    // Capacities and slots preserve the SI public integration contract, not measured reference maxima.
    public static final int STEAM_TANK_CAPACITY = 10000, DISTILLED_TANK_CAPACITY = 10000;
    public static final int BASE_CONDENSE_RATE = 100, MAX_PROGRESS = 10000;
    public static final long ENERGY_PER_TICK = 0;
    public static final int VENT_BONUS = 0;
    private static final int PRODUCT_PER_BATCH = 100;
    private static final String SAVE_KEY = "scex_condenser_v1";
    private static final SlotLayout LAYOUT = SlotLayout.builder().extra(4).input(1).output(1).battery().upgrade(1).build();
    private final FluidTank steamTank;
    private final FluidTank distilledTank;
    private final IFluidHandler fluidPort;
    private long steamCredit;
    private boolean changing;
    private CompoundTag unmappedLegacy;
    private final int[] clientData = new int[6];

    public mio_icif_condenser(BlockPos pos, BlockState state) {
        this(pos, state, mio_icif_block_entities.CONDENSER_ENTITY_TYPE.get());
    }
    public mio_icif_condenser(BlockPos pos, BlockState state, BlockEntityType<?> type) {
        super(pos, state, type, 10000, 32, 0, MAX_PROGRESS, LAYOUT, ENERGY_PER_TICK, CableTier.LV);
        steamTank = new FluidTank(STEAM_TANK_CAPACITY, fluid -> fluid.getFluid() == steamFluid()) {
            @Override protected void onContentsChanged() { ContainerToTank.markUnsaved(mio_icif_condenser.this); }
        };
        distilledTank = new FluidTank(DISTILLED_TANK_CAPACITY, fluid -> fluid.getFluid() == distilledFluid()) {
            @Override protected void onContentsChanged() { ContainerToTank.markUnsaved(mio_icif_condenser.this); }
        };
        fluidPort = new IFluidHandler() {
            @Override public int getTanks() { return 2; }
            @Override public FluidStack getFluidInTank(int tank) { return tank(tank).getFluid().copy(); }
            @Override public int getTankCapacity(int tank) { return tank(tank).getCapacity(); }
            @Override public boolean isFluidValid(int tank, FluidStack fluid) {
                return tank >= 0 && tank < 2 && !fluid.isEmpty() && fluid.getFluid() == (tank == 0 ? steamFluid() : distilledFluid());
            }
            @Override public int fill(FluidStack fluid, FluidAction action) {
                return changing || hasUnmappedLegacy() ? 0 : steamTank.fill(fluid, action);
            }
            @Override public FluidStack drain(FluidStack fluid, FluidAction action) {
                return changing || hasUnmappedLegacy() ? FluidStack.EMPTY : distilledTank.drain(fluid, action);
            }
            @Override public FluidStack drain(int amount, FluidAction action) {
                return changing || hasUnmappedLegacy() ? FluidStack.EMPTY : distilledTank.drain(amount, action);
            }
            private FluidTank tank(int index) {
                if (index < 0 || index > 1) throw new IndexOutOfBoundsException(index);
                return index == 0 ? steamTank : distilledTank;
            }
        };
    }
    protected Fluid steamFluid() { return mio_icif_fluids.STEAM.get(); }
    protected Fluid distilledFluid() { return mio_icif_fluids.DISTILLEDWATER.get(); }
    public long getReservedSteam() { return steamCredit; }
    public boolean hasUnmappedLegacy() { return unmappedLegacy != null; }

    public static void tick(Level level, BlockPos pos, BlockState state, mio_icif_condenser machine) {
        if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread() || machine.hasUnmappedLegacy()) return;
        mio_icif_producer.tick(level, pos, state, machine);
        machine.setLit(machine.isWorking);
    }
    @Override protected int[] getSlotsForDirection(Direction side) { return new int[]{INPUT_BUCKET_SLOT, EMPTY_BUCKET_SLOT}; }
    @Override protected int getBatterySlot() { return BATTERY_SLOT; }
    @Override protected boolean canExtractItem(int slot, @Nullable Direction side) { return slot == EMPTY_BUCKET_SLOT; }
    @Override public boolean isItemValidForSlot(int slot, ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (slot >= VENT_SLOT_1 && slot <= VENT_SLOT_4) {
            var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id.getNamespace().equals("mio_icif") && id.getPath().startsWith("reactor/item_reactor_")
                && (id.getPath().endsWith("_vent") || id.getPath().endsWith("_vent_spread") || id.getPath().endsWith("_vent_core"));
        }
        if (slot == INPUT_BUCKET_SLOT) return stack.is(Items.BUCKET) || mio_icif_cells.isEmptyCell(stack);
        if (slot == BATTERY_SLOT) return apiIsBattery(stack);
        if (slot == UPGRADE_SLOT) {
            var type = getItemAPI().getUpgradeType(stack); return type != null && !type.isEmpty();
        }
        return false;
    }
    @Override protected boolean canWork() {
        if (changing || hasUnmappedLegacy()) return false;
        if (distilledTank.fill(new FluidStack(distilledFluid(), PRODUCT_PER_BATCH), IFluidHandler.FluidAction.SIMULATE) != PRODUCT_PER_BATCH) return false;
        return steamCredit >= MAX_PROGRESS || !steamTank.isEmpty() && steamTank.getFluid().getFluid() == steamFluid();
    }
    @Override protected void doWork() {
        if (!canWork()) { stopWork(); return; }
        changing = true;
        try {
            if (steamCredit >= MAX_PROGRESS) {
                var before = distilledTank.getFluid().copy();
                steamCredit -= MAX_PROGRESS;
                if (distilledTank.fill(new FluidStack(distilledFluid(), PRODUCT_PER_BATCH), IFluidHandler.FluidAction.EXECUTE) != PRODUCT_PER_BATCH) {
                    steamCredit += MAX_PROGRESS; distilledTank.setFluid(before); stopWork(); return;
                }
                // R50 publishes the completed batch on the step after the final steam debit.
                isWorking = false;
            } else {
                int debit = (int) Math.min(MAX_PROGRESS - steamCredit, Math.min(BASE_CONDENSE_RATE, steamTank.getFluidAmount()));
                var before = steamTank.getFluid().copy();
                steamCredit += debit;
                var actual = steamTank.drain(debit, IFluidHandler.FluidAction.EXECUTE);
                if (actual.getAmount() != debit || actual.getFluid() != steamFluid()) {
                    steamCredit -= debit; steamTank.setFluid(before); stopWork(); return;
                }
                isWorking = true;
            }
            updateProgress(); ContainerToTank.markUnsaved(this);
        } finally { changing = false; }
    }
    @Override protected void updateProgress() { progress = (int) Math.min(MAX_PROGRESS, steamCredit); }
    @Override protected boolean shouldResetProgress() { return false; }
    @Override protected void checkInputChanged() { /* Container changes cannot erase consumed steam. */ }
    @Override protected void onTick() {
        if (changing || hasUnmappedLegacy()) return;
        var input = itemHandler.getStackInSlot(INPUT_BUCKET_SLOT);
        if (input.isEmpty()) return;
        boolean cell = mio_icif_cells.isEmptyCell(input);
        if (!cell && !input.is(Items.BUCKET)) return;
        var filled = cell ? mio_icif_cells.getFilledCellForFluidStack(distilledFluid()) : new ItemStack(mio_icif_fluids.DISTILLEDWATER_BUCKET.get());
        if (filled.isEmpty()) return;
        var amount = cell ? mio_icif_cells.getCellFluid(filled.copyWithCount(1)) : new FluidStack(distilledFluid(), 1000);
        changing = true;
        try {
            if (ContainerToTank.drainToContainer(itemHandler, INPUT_BUCKET_SLOT, EMPTY_BUCKET_SLOT, distilledTank, amount, filled))
                ContainerToTank.markUnsaved(this);
        } finally { changing = false; }
    }
    @Override protected void handleAutomationUpgrades() { if (!hasUnmappedLegacy()) super.handleAutomationUpgrades(); }
    @Override public IFluidHandler getFluidHandlerCapability(@Nullable Direction side) { return fluidPort; }
    public FluidTank getSteamTank() { return steamTank; }
    public FluidTank getDistilledTank() { return distilledTank; }
    @Override public int getMaxProgress() { return MAX_PROGRESS; }
    @Override public Component getDisplayName() { return Component.translatable("container.mio_icif.condenser"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return new CondenserMenu(id, inventory, this); }
    public ContainerData getContainerData() {
        return new ContainerData() {
            @Override public int getCount() { return 6; }
            @Override public int get(int index) {
                if (index < 0 || index >= 6) throw new IndexOutOfBoundsException(index);
                if (level != null && level.isClientSide()) return clientData[index];
                return switch (index) {
                    case 0 -> isWorking ? 1 : 0;
                    case 1 -> (int) Math.min(Integer.MAX_VALUE, Math.max(0, getEnergyStorage().getAmount()));
                    case 2 -> (int) Math.min(Integer.MAX_VALUE, Math.max(0, getEnergyStorage().getCapacity()));
                    case 3 -> getProgress();
                    case 4 -> steamTank.getFluidAmount();
                    default -> distilledTank.getFluidAmount();
                };
            }
            @Override public void set(int index, int value) {
                if (index < 0 || index >= 6) throw new IndexOutOfBoundsException(index);
                clientData[index] = value;
            }
        };
    }
    @Override public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        var own = new CompoundTag(); own.putLong("SteamCredit", steamCredit);
        own.put("Steam", steamTank.writeToNBT(registries, new CompoundTag()));
        own.put("Distilled", distilledTank.writeToNBT(registries, new CompoundTag()));
        if (unmappedLegacy != null) own.put("UnmappedLegacy", unmappedLegacy.copy());
        tag.put(SAVE_KEY, own);
    }
    @Override public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        steamCredit = 0; unmappedLegacy = null;
        steamTank.setFluid(FluidStack.EMPTY); distilledTank.setFluid(FluidStack.EMPTY);
        if (tag.contains(SAVE_KEY, Tag.TAG_COMPOUND)) {
            var own = tag.getCompound(SAVE_KEY);
            if (!own.contains("SteamCredit", Tag.TAG_LONG) || own.getLong("SteamCredit") < 0
                    || !own.contains("Steam", Tag.TAG_COMPOUND) || !own.contains("Distilled", Tag.TAG_COMPOUND)) {
                unmappedLegacy = tag.copy(); isWorking = false; updateProgress(); return;
            }
            steamCredit = Math.max(0, own.getLong("SteamCredit"));
            steamTank.readFromNBT(registries, own.getCompound("Steam"));
            distilledTank.readFromNBT(registries, own.getCompound("Distilled"));
            if (own.contains("UnmappedLegacy", Tag.TAG_COMPOUND)) unmappedLegacy = own.getCompound("UnmappedLegacy").copy();
            if ((!own.getCompound("Steam").isEmpty() && steamTank.isEmpty())
                    || (!own.getCompound("Distilled").isEmpty() && distilledTank.isEmpty())
                    || !steamTank.isEmpty() && steamTank.getFluid().getFluid() != steamFluid()
                    || !distilledTank.isEmpty() && distilledTank.getFluid().getFluid() != distilledFluid())
                unmappedLegacy = tag.copy();
        } else if (!tag.isEmpty()) unmappedLegacy = tag.copy();
        isWorking = false; updateProgress();
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag = super.getUpdateTag(registries); saveAdditional(tag, registries); return tag;
    }
}
