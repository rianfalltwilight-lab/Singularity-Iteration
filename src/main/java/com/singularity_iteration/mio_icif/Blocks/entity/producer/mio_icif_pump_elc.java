// SPDX-License-Identifier: Apache-2.0
package com.singularity_iteration.mio_icif.Blocks.entity.producer;

import com.mojang.authlib.GameProfile;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_block_entities;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_producer;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.SlotLayout;
import com.singularity_iteration.mio_icif.Items.Cell.mio_icif_cells;
import com.singularity_iteration.mio_icif.Menu.Producer.PumpElcMenu;
import com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier;
import dev.scex.si.energy.ContainerToTank;
import dev.scex.si.processing.FluidSourceSearch;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.Nullable;

/**
 * Independent pump. R49 establishes the unupgraded adjacent-water 20 paid steps / next-step output.
 * Bounded connected search, lava, automation and miner requests are SI candidate behavior.
 * The excluded predecessor was archived without inspecting its implementation.
 */
public class mio_icif_pump_elc extends mio_icif_producer {
    public static final int SLOT_BATTERY = 0, SLOT_UPGRADE_START = 1, SLOT_UPGRADE_COUNT = 4,
        SLOT_UPGRADE_END = 5, SLOT_EMPTY_CONTAINER = 5, SLOT_OUTPUT = 6, TOTAL_SLOTS = 7;
    public static final long DEFAULT_CAPACITY = 20, DEFAULT_MAX_RECEIVE = 32, DEFAULT_MAX_EXTRACT = 0,
        DEFAULT_ENERGY_PER_TICK = 1, ENERGY_PER_1000MB = 20;
    public static final int DEFAULT_WORK_TIME = 20, FLUID_CAPACITY = 16000, FLUID_PER_OPERATION = 1000;
    public static final int IDLE_RETRY_TICKS = 20;
    private static final String SAVE_KEY = "scex_pump_v1";
    private static final SlotLayout LAYOUT = SlotLayout.builder().battery().upgrade(4).input(1).output(1).build();
    private static final GameProfile ACTOR = new GameProfile(
        UUID.nameUUIDFromBytes("mio_icif:automated_pump".getBytes(StandardCharsets.UTF_8)), "[SI Pump]");
    private final FluidTank tank = new FluidTank(FLUID_CAPACITY) {
        @Override protected void onContentsChanged() { ContainerToTank.markUnsaved(mio_icif_pump_elc.this); }
    };
    private final IFluidHandler fluidPort;
    private final FluidSourceSearch search = new FluidSourceSearch();
    private List<BlockPos> path = List.of();
    private BlockPos searchOrigin;
    private Fluid searchFluid = Fluids.EMPTY;
    private long retryAt, lastCompletion = Long.MIN_VALUE;
    private int paidWork;
    private boolean changing;
    private CompoundTag unmappedLegacy, uncertainRemoval;
    private final int[] clientData = new int[7];

    public mio_icif_pump_elc(BlockPos pos, BlockState state) {
        this(pos, state, mio_icif_block_entities.PUMP_ELC_ENTITY_TYPE.get());
    }
    public mio_icif_pump_elc(BlockPos pos, BlockState state, BlockEntityType<?> type) {
        super(pos, state, type, DEFAULT_CAPACITY, DEFAULT_MAX_RECEIVE, DEFAULT_MAX_EXTRACT,
            DEFAULT_WORK_TIME, LAYOUT, DEFAULT_ENERGY_PER_TICK, CableTier.LV);
        fluidPort = new IFluidHandler() {
            private void check(int index) { if (index != 0) throw new IndexOutOfBoundsException(index); }
            @Override public int getTanks() { return 1; }
            @Override public FluidStack getFluidInTank(int index) { check(index); return tank.getFluid().copy(); }
            @Override public int getTankCapacity(int index) { check(index); return FLUID_CAPACITY; }
            @Override public boolean isFluidValid(int index, FluidStack fluid) { check(index); return false; }
            @Override public int fill(FluidStack fluid, FluidAction action) { return 0; }
            @Override public FluidStack drain(FluidStack fluid, FluidAction action) {
                return mayTransfer() ? tank.drain(fluid, action) : FluidStack.EMPTY;
            }
            @Override public FluidStack drain(int amount, FluidAction action) {
                return mayTransfer() ? tank.drain(amount, action) : FluidStack.EMPTY;
            }
        };
    }
    protected boolean operational() {
        return level instanceof ServerLevel server && server.getServer().isSameThread() && !isRemoved()
            && available(server, worldPosition) && server.getBlockEntity(worldPosition) == this;
    }
    private static boolean available(ServerLevel server, BlockPos pos) {
        return !server.isOutsideBuildHeight(pos) && server.getWorldBorder().isWithinBounds(pos)
            && server.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }
    private boolean mayTransfer() { return !changing && !hasHeldState() && operational(); }
    public boolean hasUnmappedLegacy() { return unmappedLegacy != null; }
    public boolean hasUncertainRemoval() { return uncertainRemoval != null; }
    private boolean hasHeldState() { return hasUnmappedLegacy() || hasUncertainRemoval(); }
    protected long currentTick() { return level == null ? 0 : level.getGameTime(); }
    protected Direction getFacing() {
        var property = net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING;
        if (getBlockState().hasProperty(property)) return getBlockState().getValue(property);
        var horizontal = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
        return getBlockState().hasProperty(horizontal) ? getBlockState().getValue(horizontal) : Direction.DOWN;
    }
    protected BlockState sourceState(BlockPos pos) {
        return level instanceof ServerLevel server && available(server, pos) ? server.getBlockState(pos) : Blocks.AIR.defaultBlockState();
    }
    private static Fluid sourceFluid(BlockState state) {
        if (state.is(Blocks.WATER)) return Fluids.WATER;
        if (state.is(Blocks.LAVA)) return Fluids.LAVA;
        return Fluids.EMPTY;
    }
    private int classify(BlockPos pos) {
        var state = sourceState(pos);
        if (sourceFluid(state) != searchFluid) return FluidSourceSearch.BLOCKED;
        return state.getFluidState().isSource() ? FluidSourceSearch.SOURCE : FluidSourceSearch.FLOWING;
    }
    private void forgetSearch(boolean idle) {
        search.reset(); path = List.of(); searchOrigin = null; searchFluid = Fluids.EMPTY;
        retryAt = idle ? currentTick() + IDLE_RETRY_TICKS + Math.floorMod(worldPosition.asLong(), 5) : 0;
    }
    private boolean validPath() {
        if (path.isEmpty() || path.size() > FluidSourceSearch.MAX_PATH
                || !path.getFirst().equals(worldPosition.relative(getFacing()))) return false;
        for (var pos : path) if (classify(pos) == FluidSourceSearch.BLOCKED) return false;
        return classify(path.getLast()) == FluidSourceSearch.SOURCE;
    }
    private boolean findSource() {
        if (!path.isEmpty()) {
            if (validPath()) return true;
            forgetSearch(false);
        }
        if (currentTick() < retryAt) return false;
        var origin = worldPosition.relative(getFacing());
        if (!origin.equals(searchOrigin)) {
            forgetSearch(false); searchOrigin = origin; searchFluid = sourceFluid(sourceState(origin));
            if (searchFluid == Fluids.EMPTY) { forgetSearch(true); return false; }
            search.begin(origin);
        }
        path = search.advance(this::classify, FluidSourceSearch.STEPS_PER_TICK);
        if (!path.isEmpty() && validPath()) return true;
        if (search.exhausted()) forgetSearch(true);
        return false;
    }
    private boolean room(Fluid fluid, int amount) {
        return fluid != Fluids.EMPTY && amount > 0 && amount <= FLUID_CAPACITY
            && tank.fill(new FluidStack(fluid, amount), IFluidHandler.FluidAction.SIMULATE) == amount;
    }
    public static void tick(Level level, BlockPos pos, BlockState state, mio_icif_pump_elc pump) {
        if (!pump.operational() || pump.hasHeldState()) return;
        mio_icif_producer.tick(level, pos, state, pump);
        pump.setLit(pump.isWorking);
    }
    @Override protected void tickProduction() {
        stopWork();
        if (!mayTransfer() || lastCompletion == currentTick()
                || tank.getFluidAmount() > FLUID_CAPACITY - FLUID_PER_OPERATION) return;
        long cost = getEffectiveEnergyPerTick();
        if (paidWork < DEFAULT_WORK_TIME && (cost <= 0 || energyStorage.consumeEnergyInternal(cost, true) != cost)) return;
        if (!findSource()) return;
        if (!room(searchFluid, FLUID_PER_OPERATION)) { forgetSearch(true); return; }
        if (paidWork >= DEFAULT_WORK_TIME) {
            boolean done = collect(path.getLast(), searchFluid);
            forgetSearch(!done); return;
        }
        changing = true;
        try {
            if (energyStorage.consumeEnergyInternal(cost, false) != cost) return;
            paidWork += Math.min(DEFAULT_WORK_TIME - paidWork, Math.max(1, getProgressPerTick()));
            progress = paidWork; isWorking = true; ContainerToTank.markUnsaved(this);
        } finally { changing = false; }
    }
    @Override protected boolean canWork() { return mayTransfer() && room(searchFluid, FLUID_PER_OPERATION); }
    @Override protected void doWork() { tickProduction(); }
    @Override protected void updateProgress() { progress = paidWork; }
    @Override protected boolean shouldResetProgress() { return false; }
    @Override protected void checkInputChanged() { /* Container changes cannot erase paid work. */ }

    /** The pump owns removal and output together; callers must never remove the source again. */
    public boolean tryCollectForMiner(mio_icif_miner_elc miner, BlockPos pos) {
        if (!mayTransfer() || !(level instanceof ServerLevel server) || miner.getLevel() != server
                || !available(server, miner.getBlockPos()) || server.getBlockEntity(miner.getBlockPos()) != miner
                || worldPosition.distManhattan(miner.getBlockPos()) != 1 || !canWorkRedstone()
                || lastCompletion == currentTick() || paidWork > 0 && paidWork < DEFAULT_WORK_TIME) return false;
        var state = sourceState(pos); var fluid = sourceFluid(state);
        if (!state.getFluidState().isSource() || !room(fluid, FLUID_PER_OPERATION)) return false;
        if (paidWork == 0) {
            if (energyStorage.consumeEnergyInternal(ENERGY_PER_1000MB, true) != ENERGY_PER_1000MB) return false;
            changing = true;
            try {
                if (energyStorage.consumeEnergyInternal(ENERGY_PER_1000MB, false) != ENERGY_PER_1000MB) return false;
                paidWork = DEFAULT_WORK_TIME; progress = paidWork; ContainerToTank.markUnsaved(this);
            } finally { changing = false; }
        }
        boolean result = collect(pos, fluid); forgetSearch(!result); return result;
    }
    private boolean collect(BlockPos pos, Fluid fluid) {
        if (!mayTransfer() || paidWork < DEFAULT_WORK_TIME || !room(fluid, FLUID_PER_OPERATION)) return false;
        var expected = sourceState(pos);
        if (sourceFluid(expected) != fluid || !expected.getFluidState().isSource()) return false;
        changing = true;
        try {
            if (!removeSource(pos, expected, fluid)) return false;
            // Owned tank and accounting have no externally callable mutation window here.
            if (tank.fill(new FluidStack(fluid, FLUID_PER_OPERATION), IFluidHandler.FluidAction.EXECUTE) != FLUID_PER_OPERATION)
                throw new IllegalStateException("Owned pump output admission changed during removal");
            paidWork -= DEFAULT_WORK_TIME; progress = paidWork; uncertainRemoval = null;
            lastCompletion = currentTick(); ContainerToTank.markUnsaved(this); return true;
        } finally { changing = false; }
    }
    /** Vanilla liquid pickup plus the public protection event; no IC2 types or internals. */
    protected boolean removeSource(BlockPos pos, BlockState expected, Fluid fluid) {
        if (!(level instanceof ServerLevel server) || !operational() || sourceState(pos) != expected) return false;
        var actor = FakePlayerFactory.get(server, ACTOR);
        var oldHand = actor.getMainHandItem().copy(); var oldPosition = actor.position();
        try {
            actor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET));
            actor.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
            var event = new BlockEvent.BreakEvent(server, pos, expected, actor); NeoForge.EVENT_BUS.post(event);
            if (event.isCanceled() || !operational() || sourceState(pos) != expected || !room(fluid, FLUID_PER_OPERATION)) return false;
            uncertainRemoval = new CompoundTag(); uncertainRemoval.putLong("Position", pos.asLong());
            uncertainRemoval.putString("Fluid", BuiltInRegistries.FLUID.getKey(fluid).toString());
            ContainerToTank.markUnsaved(this);
            var result = ((BucketPickup) expected.getBlock()).pickupBlock(actor, server, pos, expected);
            // 1.21.1 LiquidBlock returns a bucket even if its setBlock call returns false.
            // Require a loaded, observably changed source before publishing owned fluid.
            if (result.getItem() instanceof BucketItem bucket && bucket.content == fluid && result.getCount() == 1) {
                return available(server, pos) && sourceState(pos) != expected;
            }
            if (result.isEmpty() && available(server, pos) && sourceState(pos) == expected) uncertainRemoval = null;
            ContainerToTank.markUnsaved(this); return false;
        } finally {
            actor.setItemInHand(InteractionHand.MAIN_HAND, oldHand);
            actor.setPos(oldPosition.x, oldPosition.y, oldPosition.z);
        }
    }
    /** Compatibility entry for already supplied material. World callers use tryCollectForMiner. */
    public boolean injectFluid(Fluid fluid, int amount) {
        if (!mayTransfer() || !room(fluid, amount)) return false;
        long cost = ((long) amount * ENERGY_PER_1000MB + FLUID_PER_OPERATION - 1) / FLUID_PER_OPERATION;
        if (energyStorage.consumeEnergyInternal(cost, true) != cost) return false;
        changing = true;
        try {
            if (energyStorage.consumeEnergyInternal(cost, false) != cost) return false;
            int accepted = tank.fill(new FluidStack(fluid, amount), IFluidHandler.FluidAction.EXECUTE);
            if (accepted != amount) throw new IllegalStateException("Owned pump injection changed");
            ContainerToTank.markUnsaved(this); return true;
        } finally { changing = false; }
    }
    public boolean canAcceptFluid(Fluid fluid) { return mayTransfer() && room(fluid, FLUID_PER_OPERATION); }
    @Override protected int getBatterySlot() { return SLOT_BATTERY; }
    @Override protected int[] getSlotsForDirection(Direction side) { return new int[]{SLOT_EMPTY_CONTAINER, SLOT_OUTPUT}; }
    @Override protected boolean canExtractItem(int slot, @Nullable Direction side) { return slot == SLOT_OUTPUT; }
    @Override public boolean isItemValidForSlot(int slot, ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (slot == SLOT_EMPTY_CONTAINER) return stack.is(Items.BUCKET) || mio_icif_cells.isEmptyCell(stack);
        if (slot == SLOT_BATTERY) return isBattery(stack);
        if (slot >= SLOT_UPGRADE_START && slot < SLOT_UPGRADE_END) {
            var type = getItemAPI().getUpgradeType(stack); return type != null && !type.isEmpty();
        }
        return false;
    }
    @Override protected void onTick() {
        if (!mayTransfer() || tank.getFluidAmount() < FLUID_PER_OPERATION) return;
        var input = itemHandler.getStackInSlot(SLOT_EMPTY_CONTAINER);
        if (input.isEmpty()) return;
        var fluid = tank.getFluid().getFluid(); ItemStack filled;
        if (input.is(Items.BUCKET)) filled = new ItemStack(fluid.getBucket());
        else if (mio_icif_cells.isEmptyCell(input)) filled = mio_icif_cells.getFilledCellForFluidStack(fluid);
        else return;
        if (filled.isEmpty() || filled.is(Items.BUCKET)) return;
        var content = input.is(Items.BUCKET) ? new FluidStack(fluid, FLUID_PER_OPERATION) : mio_icif_cells.getCellFluid(filled.copyWithCount(1));
        if (content.getFluid() != fluid || content.getAmount() <= 0) return;
        changing = true;
        try { ContainerToTank.drainToContainer(itemHandler, SLOT_EMPTY_CONTAINER, SLOT_OUTPUT, tank, content, filled); }
        finally { changing = false; }
    }
    @Override protected void handleAutomationUpgrades() { if (!hasHeldState() && !changing) super.handleAutomationUpgrades(); }
    public IFluidHandler getFluidHandler() { return fluidPort; }
    @Override public IFluidHandler getFluidHandlerCapability(@Nullable Direction side) { return fluidPort; }
    public int getFluidAmount() { return tank.getFluidAmount(); }
    public int getFluidCapacity() { return FLUID_CAPACITY; }
    public FluidStack getFluid() { return tank.getFluid().copy(); }
    public int getFluidProgress() { return getFluidAmount() * 100 / FLUID_CAPACITY; }
    public String getFluidTypeName() { return tank.isEmpty() ? "" : tank.getFluid().getHoverName().getString(); }
    @Override public Component getDisplayName() { return Component.translatable("container.mio_icif.pump_elc"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return new PumpElcMenu(id, inventory, this); }
    public ContainerData getContainerData() {
        return new ContainerData() {
            private void check(int i) { if (i < 0 || i >= 7) throw new IndexOutOfBoundsException(i); }
            @Override public int getCount() { return 7; }
            @Override public int get(int i) {
                check(i); if (level != null && level.isClientSide()) return clientData[i];
                return switch (i) {
                    case 0 -> paidWork; case 1 -> DEFAULT_WORK_TIME;
                    case 2 -> (int) Math.min(Integer.MAX_VALUE, energyStorage.getAmount());
                    case 3 -> (int) Math.min(Integer.MAX_VALUE, energyStorage.getCapacity());
                    case 4 -> getFluidAmount(); case 5 -> FLUID_CAPACITY;
                    default -> tank.isEmpty() ? -1 : BuiltInRegistries.FLUID.getId(tank.getFluid().getFluid());
                };
            }
            @Override public void set(int i, int value) { check(i); clientData[i] = value; }
        };
    }
    @Override public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        var own = new CompoundTag(); own.putInt("PaidWork", paidWork);
        own.put("Tank", tank.writeToNBT(registries, new CompoundTag()));
        if (unmappedLegacy != null) own.put("UnmappedLegacy", unmappedLegacy.copy());
        if (uncertainRemoval != null) own.put("UncertainRemoval", uncertainRemoval.copy());
        tag.put(SAVE_KEY, own);
    }
    @Override public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        if (changing) throw new IllegalStateException("Cannot load an active pump transaction");
        super.loadAdditional(tag, registries);
        paidWork = 0; tank.setFluid(FluidStack.EMPTY); unmappedLegacy = null; uncertainRemoval = null;
        forgetSearch(false); lastCompletion = Long.MIN_VALUE;
        if (tag.contains(SAVE_KEY, Tag.TAG_COMPOUND)) {
            var own = tag.getCompound(SAVE_KEY);
            if (!own.contains("PaidWork", Tag.TAG_INT) || own.getInt("PaidWork") < 0 || own.getInt("PaidWork") > DEFAULT_WORK_TIME
                    || !own.contains("Tank", Tag.TAG_COMPOUND)
                    || own.contains("UnmappedLegacy") && !own.contains("UnmappedLegacy", Tag.TAG_COMPOUND)
                    || own.contains("UncertainRemoval") && !own.contains("UncertainRemoval", Tag.TAG_COMPOUND)) unmappedLegacy = tag.copy();
            else {
                paidWork = own.getInt("PaidWork"); tank.readFromNBT(registries, own.getCompound("Tank"));
                boolean invalidTank = (!own.getCompound("Tank").isEmpty() && tank.isEmpty()) || tank.getFluidAmount() > FLUID_CAPACITY;
                if (invalidTank) unmappedLegacy = tag.copy();
                else if (own.contains("UnmappedLegacy", Tag.TAG_COMPOUND)) unmappedLegacy = own.getCompound("UnmappedLegacy").copy();
                if (own.contains("UncertainRemoval", Tag.TAG_COMPOUND)) uncertainRemoval = own.getCompound("UncertainRemoval").copy();
            }
        } else if (!tag.isEmpty()) unmappedLegacy = tag.copy();
        isWorking = false; progress = paidWork;
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag = super.getUpdateTag(registries); saveAdditional(tag, registries); return tag;
    }
}
