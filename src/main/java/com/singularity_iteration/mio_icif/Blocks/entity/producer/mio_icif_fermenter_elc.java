package com.singularity_iteration.mio_icif.Blocks.entity.producer;

import com.singularity_iteration.mio_icif.Blocks.entity.HUEntity.mio_icif_HeatU_Block;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_block_entities;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.SlotLayout;
import com.singularity_iteration.mio_icif.Blocks.Environment.fluid.mio_icif_fluids;
import com.singularity_iteration.mio_icif.Items.Cell.mio_icif_cells;
import com.singularity_iteration.mio_icif.Items.Resource.mio_icif_resources;
import com.singularity_iteration.mio_icif.api.capability.IMioIcifCapabilities;
import dev.scex.si.energy.ContainerToTank;
import dev.scex.si.processing.OwnedFluidConversion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

/**
 * ??��?�机?��??��?��??
 * 使用 HU ??��?��?��?��?��?�质为沼气�??并产?��??��??
 *
 * 工�?��?��??�?
 * - 从正?��?���?HU ??��?��?????�?100 HU/t�?
 * - �???��?��?�质量?0mB/次�?��?��?��?��??4000 HU/次�?�产?��沼�?��??00mB/次�??
 * - 每累计算??�?500mB ??��?�质额�?�产�?1 个�?��??
 * - ??��?��?��?��??工�?��?�度越快
 */
@SuppressWarnings("null")
public class mio_icif_fermenter_elc extends mio_icif_HeatU_Block {

    private static final SlotLayout LAYOUT = SlotLayout.builder()
        .extra(2)
        .output(2)
        .upgrade(4)
        .extra(1)
        .build();

    // 槽位?��?��?��????? builder �??��顺�?��?�EXTRA=0-1, OUTPUT=2-3, UPGRADE=4-7, EXTRA=8�?
    public static final int BIOMASS_CELL_SLOT = 0;         // ??��?�质??��??输�?�槽 (EXTRA)
    public static final int EMPTY_CELL_SLOT = 1;           // ??��?�质??��??�???��?��??空气?��??输出�?(EXTRA)
    public static final int BIOGAS_CELL_SLOT = 2;          // 沼�?��?��??输出�?(OUTPUT)
    public static final int FERTILIZER_SLOT = 3;           // ??��?��?�出�?(OUTPUT)
    public static final int UPGRADE_SLOT_START = 4;        // ???级槽起�??(UPGRADE)
    public static final int UPGRADE_SLOT_COUNT = 4;        // ???级槽?���?
    public static final int EMPTY_CELL_INPUT_SLOT = 8;     // 沼�?��?��??专用???空气?��??输�?��??(EXTRA)
    public static final int TOTAL_SLOTS = 9;               // ??�槽位数

    // ??��?��?�置
    public static final int HEAT_CAPACITY = 10000;    // ??��?�容??? 10000 HU
    public static final int MAX_HEAT_RECEIVE = 100;   // ???大接受?100 HU/t
    public static final int MAX_HEAT_EXTRACT = 0;     // 不�?�出??��??
    public static final int MAX_TEMP = 1000;          // ???高温�?
    public static final float HEAT_LOSS_FACTOR = 0.01f;

    // �?体�?�置
    public static final int BIOMASS_TANK_CAPACITY = 10000; // ??��?�质量? 10000 mB
    public static final int BIOGAS_TANK_CAPACITY = 2000;   // 沼�?��??2000 mB
    public static final int BIOMASS_PER_OPERATION = 20;    // 每次�?�??20 mB ??��?��??
    public static final int BIOGAS_PER_OPERATION = 400;    // 每次产出 400 mB 沼�??
    public static final int HEAT_PER_OPERATION = 4000;     // 每次�?�??4000 HU
    public static final int BIOMASS_PER_FERTILIZER = 500;  // �?500 mB ??��?�质产�??1 ??��??

    // 进度??�置�??���? 100 HU/t ?���?40 ticks 完成?��??次�?��?��??
    public static final int BASE_PROGRESS_REQUIRED = HEAT_PER_OPERATION; // ??��?�度???�?= 4000 HU

    // �?体�?��??
    protected final FluidTank biomassTank;
    protected final FluidTank biogasTank;

    // 工�?��?�度�?使用??��?�累计算??
    private long workCredit;
    private int maxProgress;

    // 累计算?????????��?�质量??��于�?��?�产?���?
    private long biomassProcessed;

    // ?��?���??��工�??
    private boolean isWorking;

    private final ContainerData containerData = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> getProgress();
                case 1 -> maxProgress;
                case 2 -> isWorking ? 1 : 0;
                case 3 -> (int) heatStorage.getHeatStored();
                case 4 -> (int) heatStorage.getMaxHeatStored();
                case 5 -> biomassTank.getFluidAmount();
                case 6 -> biomassTank.getCapacity();
                case 7 -> biogasTank.getFluidAmount();
                case 8 -> biogasTank.getCapacity();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() { return 9; }
    };

    /**
     * ?????�函�?
     */
    public mio_icif_fermenter_elc(BlockPos pos, BlockState state) {
        this(pos, state, mio_icif_block_entities.FERMENTER_ELC_ENTITY_TYPE.get());
    }

    public mio_icif_fermenter_elc(BlockPos pos, BlockState state, BlockEntityType<?> type) {
        super(type, pos, state, HEAT_CAPACITY, MAX_HEAT_RECEIVE, MAX_HEAT_EXTRACT,
              20, MAX_TEMP, HEAT_LOSS_FACTOR);

        this.workCredit = 0;
        this.maxProgress = BASE_PROGRESS_REQUIRED;
        this.biomassProcessed = 0;
        this.isWorking = false;

        // ??��?��?�槽位置??�???��?��??�?????��
        this.slotLayout = LAYOUT;
        this.itemHandler = createItemHandler(LAYOUT);
        this.itemHandler.setValidator((slot, stack, slotType) -> mio_icif_fermenter_elc.this.isItemValidForSlot(slot, stack));

        // ??��?��?��?��?�质量?体�??
        this.biomassTank = new FluidTank(BIOMASS_TANK_CAPACITY, fluidStack ->
            fluidStack.getFluid() == mio_icif_fluids.BIOMASS.get()) {
                @Override protected void onContentsChanged() { ContainerToTank.markUnsaved(mio_icif_fermenter_elc.this); }
            };

        // ??��?��?�沼气�??体槽
        this.biogasTank = new FluidTank(BIOGAS_TANK_CAPACITY, fluidStack ->
            fluidStack.getFluid() == mio_icif_fluids.BIOGAS.get()) {
                @Override protected void onContentsChanged() { ContainerToTank.markUnsaved(mio_icif_fermenter_elc.this); }
            };
    }

    /**
     * �??��??��???��?��???????��??��??定槽位?
     */
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        if (isUpgradeSlot(slot)) {
            return stack.getItem() instanceof com.singularity_iteration.mio_icif.Items.Upgrade.mio_icif_upgrade;
        }
        return switch (slot) {
            case BIOMASS_CELL_SLOT -> mio_icif_cells.isCellContainingFluid(stack, mio_icif_fluids.BIOMASS.get());
            case EMPTY_CELL_SLOT -> mio_icif_cells.isEmptyCell(stack);
            case EMPTY_CELL_INPUT_SLOT -> mio_icif_cells.isEmptyCell(stack);
            case BIOGAS_CELL_SLOT -> false; // 输出�?
            case FERTILIZER_SLOT -> false;  // 输出�?
            default -> false;
        };
    }

    /**
     * ?��??�正?��?��??��????��?�接?��?���?
     */
    private Direction getFrontSide() {
        BlockState state = getBlockState();
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
            return state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
        }
        return Direction.NORTH;
    }

    /**
     * ??��?��?��?��?��?��?��?��?�只??�正?��?��以接?��??��??
     */
    @Override
    public IMioIcifCapabilities.IHeatStorage getHeatStorageCapability(@Nullable Direction side) {
        if (side == null || side == getFrontSide()) {
            return this;
        }
        return null;
    }

    /**
     * �?tick ?��?��??��??
     */
    public static void tick(Level level, BlockPos pos, BlockState state, mio_icif_fermenter_elc blockEntity) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel server)
                || !server.getServer().isSameThread() || blockEntity.isRemoved()) {
            return;
        }

        blockEntity.recalculateUpgradeStats();

        // 应用??��??�?
        blockEntity.applyHeatLoss();

        // ???�?????��?��??��??级�??�?体弹簧??��??��?��????��???????��??�?填充??
        // ??��?�沼气被优�??�?进�?��?????弹簧?��?�相??��?��??
        blockEntity.handleAutomationUpgrades();

        // �??????��??槽位?��??体槽???交换??
        blockEntity.handleBiomassCellSlot();
        blockEntity.handleBiogasCellSlot();

        // ??��????��?�工�?
        if (blockEntity.canWorkRedstone() && blockEntity.canWork()) {
            blockEntity.doWork();
        } else {
            blockEntity.isWorking = false;
        }

        // ?��?��?��??�状态??
        boolean isLit = state.getValue(com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_fermenter_elc.LIT);
        if (blockEntity.isWorking != isLit) {
            level.setBlock(pos, state.setValue(com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_fermenter_elc.LIT, blockEntity.isWorking), 3);
        }

        blockEntity.setChanged();
    }

    /**
     * �??????��?�质??��??槽位?��????��??中�????��?�质转移??��??体�??
     */
    private void handleBiomassCellSlot() {
        var cell = itemHandler.getStackInSlot(BIOMASS_CELL_SLOT);
        if (cell.isEmpty() || !mio_icif_cells.isCellContainingFluid(cell, mio_icif_fluids.BIOMASS.get())) return;
        var single = cell.copyWithCount(1);
        if (ContainerToTank.transfer(itemHandler, BIOMASS_CELL_SLOT, EMPTY_CELL_SLOT, biomassTank,
                mio_icif_cells.getCellFluid(single), mio_icif_cells.getEmptyCellForStack(single))) setChanged();
    }

    /**
     * �????沼�?��?�出：用空气?��??从沼气槽位?填沼�?
     */
    private void handleBiogasCellSlot() {
        int inputSlot = EMPTY_CELL_INPUT_SLOT;
        if (!mio_icif_cells.isEmptyCell(itemHandler.getStackInSlot(inputSlot))) inputSlot = EMPTY_CELL_SLOT;
        if (!mio_icif_cells.isEmptyCell(itemHandler.getStackInSlot(inputSlot))) return;
        var filled = mio_icif_cells.getFilledCellForFluidStack(mio_icif_fluids.BIOGAS.get());
        if (filled.isEmpty()) return;
        var content = mio_icif_cells.getCellFluid(filled.copyWithCount(1));
        if (content.getFluid() != mio_icif_fluids.BIOGAS.get()) return;
        if (ContainerToTank.drainToContainer(itemHandler, inputSlot, BIOGAS_CELL_SLOT, biogasTank, content, filled)) setChanged();
    }

    /**
     * �??��?��?��?��以工�?
     */
    protected boolean canWork() {
        return (workCredit >= maxProgress || heatStorage.getHeatStored() > 0) && prepareOperation().isPresent();
    }

    private java.util.Optional<OwnedFluidConversion.Prepared> prepareOperation() {
        if (biomassProcessed > Long.MAX_VALUE - BIOMASS_PER_OPERATION) return java.util.Optional.empty();
        boolean fertilizerDue = biomassProcessed + BIOMASS_PER_OPERATION >= BIOMASS_PER_FERTILIZER;
        return OwnedFluidConversion.prepare(biomassTank, new FluidStack(mio_icif_fluids.BIOMASS.get(), BIOMASS_PER_OPERATION),
            biogasTank, new FluidStack(mio_icif_fluids.BIOGAS.get(), BIOGAS_PER_OPERATION), itemHandler,
            FERTILIZER_SLOT, fertilizerDue ? new ItemStack(mio_icif_resources.FERTILIZER.get()) : ItemStack.EMPTY);
    }

    /**
     * ??��????��?�工�?
     */
    protected void doWork() {
        isWorking = false;
        var operation = prepareOperation();
        if (operation.isEmpty()) return;
        if (workCredit < maxProgress) {
            int speed = getProcessingSpeedMultiplier();
            long remaining = maxProgress - workCredit;
            long heatUntilReady = (remaining + speed - 1L) / speed;
            long rate = Math.max(1, (long) Math.ceil(Math.min(MAX_HEAT_RECEIVE, HEAT_PER_OPERATION / 10)
                * getProcessingCostMultiplier()));
            long requested = Math.min(heatUntilReady, Math.min(rate, heatStorage.getHeatStored()));
            long consumed = heatStorage.consumeHeatInternal(requested, false);
            if (consumed <= 0) return;
            // Pay only up to this operation, retaining the final HU's surplus work at high upgrade speed.
            workCredit += consumed * (long) speed;
            isWorking = true;
        }
        if (workCredit >= maxProgress) {
            long creditBefore = workCredit, processedBefore = biomassProcessed;
            workCredit -= maxProgress;
            biomassProcessed += BIOMASS_PER_OPERATION;
            if (biomassProcessed >= BIOMASS_PER_FERTILIZER) biomassProcessed -= BIOMASS_PER_FERTILIZER;
            if (!operation.get().commit()) {
                workCredit = creditBefore; biomassProcessed = processedBefore;
                isWorking = false;
                setChanged();
                return;
            }
            isWorking = true;
        }
        setChanged();
    }

    @Override
    protected int[] getOutputSlots() {
        return new int[]{EMPTY_CELL_SLOT, BIOGAS_CELL_SLOT, FERTILIZER_SLOT};
    }

    @Override
    protected int[] getInputSlots() {
        return new int[]{BIOMASS_CELL_SLOT, EMPTY_CELL_INPUT_SLOT};
    }

    // ==================== Getter ?���? ====================

    public ItemStackHandler getItemHandler() {
        return itemHandler;
    }

    public IFluidHandler getBiomassTank() {
        return biomassTank;
    }

    public IFluidHandler getBiogasTank() {
        return biogasTank;
    }

    public int getBiomassAmount() {
        return biomassTank.getFluidAmount();
    }

    public int getBiomassCapacity() {
        return biomassTank.getCapacity();
    }

    public int getBiogasAmount() {
        return biogasTank.getFluidAmount();
    }

    public int getBiogasCapacity() {
        return biogasTank.getCapacity();
    }

    public int getProgress() {
        return (int) Math.min(maxProgress, workCredit);
    }

    public int getMaxProgress() {
        return maxProgress;
    }

    public long getHeatStored() {
        return heatStorage.getHeatStored();
    }

    public int getHeatCapacity() {
        return (int) heatStorage.getMaxHeatStored();
    }

    public boolean isWorking() {
        return isWorking;
    }

    // ==================== Capability ???�? ====================

    public net.neoforged.neoforge.items.IItemHandler getItemHandlerCapability(@Nullable Direction side) {
        return itemHandler;
    }

    @Override
    public IFluidHandler getFluidHandlerCapability(@Nullable Direction side) {
        // 返回?��??个�?????�?体�??????��，根?���?体类??��????��?��?��?��??
        return new CombinedFluidHandler();
    }

    /**
     * �????�?体�??????��：�?��?��?��?�质??? inputTank，�?�出沼�?��?? outputTank
     */
    private class CombinedFluidHandler implements IFluidHandler {
        @Override
        public int getTanks() {
            return 2;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            return switch (tank) {
                case 0 -> biomassTank.getFluid().copy();
                case 1 -> biogasTank.getFluid().copy();
                default -> FluidStack.EMPTY;
            };
        }

        @Override
        public int getTankCapacity(int tank) {
            return switch (tank) {
                case 0 -> biomassTank.getCapacity();
                case 1 -> biogasTank.getCapacity();
                default -> 0;
            };
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return switch (tank) {
                case 0 -> stack.getFluid() == mio_icif_fluids.BIOMASS.get();
                case 1 -> false; // 沼�?�槽不�??许�?��?��?��??
                default -> false;
            };
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            // ?��??��?��?��?��?��??
            if (resource.getFluid() == mio_icif_fluids.BIOMASS.get()) {
                return biomassTank.fill(resource, action);
            }
            return 0;
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            // ?��??��?�出沼�??
            if (FluidStack.isSameFluid(resource, new FluidStack(mio_icif_fluids.BIOGAS.get(), 1))) {
                return biogasTank.drain(resource, action);
            }
            return FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            // ?��??��?�出沼�??
            return biogasTank.drain(maxDrain, action);
        }
    }

    // ==================== NBT 序�?��??====================

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("inventory", itemHandler.serializeNBT(registries));
        tag.put("biomassTank", biomassTank.writeToNBT(registries, new CompoundTag()));
        tag.put("biogasTank", biogasTank.writeToNBT(registries, new CompoundTag()));
        tag.putInt("progress", getProgress());
        tag.putLong("scex_work_credit", workCredit);
        tag.putLong("biomassProcessed", biomassProcessed);
        tag.putBoolean("isWorking", isWorking);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("inventory")) {
            itemHandler.deserializeNBT(registries, tag.getCompound("inventory"));
        }
        if (tag.contains("biomassTank")) {
            biomassTank.readFromNBT(registries, tag.getCompound("biomassTank"));
        }
        if (tag.contains("biogasTank")) {
            biogasTank.readFromNBT(registries, tag.getCompound("biogasTank"));
        }
        workCredit = Math.max(0, tag.contains("scex_work_credit", net.minecraft.nbt.Tag.TAG_ANY_NUMERIC)
            ? tag.getLong("scex_work_credit") : tag.getLong("progress"));
        biomassProcessed = Math.max(0, tag.getLong("biomassProcessed"));
        isWorking = level != null && level.isClientSide() && tag.getBoolean("isWorking");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }

    // ==================== MenuProvider ====================

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.mio_icif.fermenter_elc");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new com.singularity_iteration.mio_icif.Menu.Producer.FermenterElcMenu(containerId, playerInventory, this);
    }

    public ContainerData getContainerData() { return containerData; }
}
