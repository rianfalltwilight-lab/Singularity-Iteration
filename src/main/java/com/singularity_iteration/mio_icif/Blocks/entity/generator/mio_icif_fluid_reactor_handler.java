// SPDX-License-Identifier: Apache-2.0
package com.singularity_iteration.mio_icif.Blocks.entity.generator;

import com.singularity_iteration.mio_icif.Blocks.Environment.fluid.mio_icif_fluids;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.MachineItemHandler;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.SlotLayout;
import com.singularity_iteration.mio_icif.Items.Cell.mio_icif_cells;
import dev.scex.si.energy.ContainerToTank;
import dev.scex.si.reactor.FluidReactorCycle;
import java.util.function.BooleanSupplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/** Owned tanks and containers; cycle conversion comes from the R119 ordinary-save reference. */
public class mio_icif_fluid_reactor_handler implements IFluidHandler {
    public static final int FLUID_CAPACITY=10000,INPUT_CELL_SLOT_1=0,OUTPUT_EMPTY_SLOT_1=1,
        INPUT_CELL_SLOT_2=2,OUTPUT_EMPTY_SLOT_2=3,TOTAL_ITEM_SLOTS=4,HEAT_PER_MB=1;
    private final BooleanSupplier available;
    private final Runnable changed;
    private int currentHeatGeneration;
    private CompoundTag hold=new CompoundTag();
    private final FluidTank inputTank=new FluidTank(FLUID_CAPACITY,stack->stack.is(mio_icif_fluids.COOLANT.get()));
    private final FluidTank outputTank=new FluidTank(FLUID_CAPACITY,stack->stack.is(mio_icif_fluids.HOTCOOLANT.get()));
    private final MachineItemHandler itemHandler=new MachineItemHandler(SlotLayout.builder().extra(4).build()){
        @Override protected void onContentsChanged(int slot){if(changed!=null)changed.run();}
        @Override public boolean isItemValid(int slot,ItemStack stack){return slot==0?isColdContainer(stack):slot==2&&isEmptyContainer(stack);}
    };
    public mio_icif_fluid_reactor_handler(){this(()->true,()->{});}
    public mio_icif_fluid_reactor_handler(BooleanSupplier available,Runnable changed){this.available=available;this.changed=changed;}
    private boolean live(){return available.getAsBoolean()&&hold.isEmpty();}
    private static boolean isColdContainer(ItemStack stack){var fluid=mio_icif_cells.getCellFluid(stack);return stack.is(mio_icif_fluids.COOLANT_BUCKET.get())||!fluid.isEmpty()&&fluid.is(mio_icif_fluids.COOLANT.get());}
    private static boolean isEmptyContainer(ItemStack stack){return stack.is(Items.BUCKET)||mio_icif_cells.isEmptyCell(stack);}
    public void processContainers(){
        if(!live())return;
        ItemStack cold=itemHandler.getStackInSlot(0),empty=itemHandler.getStackInSlot(2);
        if(isColdContainer(cold)){
            var content=cold.is(mio_icif_fluids.COOLANT_BUCKET.get())?new FluidStack(mio_icif_fluids.COOLANT.get(),1000):mio_icif_cells.getCellFluid(cold);
            var remainder=cold.is(mio_icif_fluids.COOLANT_BUCKET.get())?new ItemStack(Items.BUCKET):mio_icif_cells.getEmptyCellForStack(cold);
            remainder.applyComponents(cold.getComponentsPatch());
            if(remainder.getItem() instanceof com.singularity_iteration.mio_icif.Items.Cell.mio_icif_dynamic_cell cell)cell.writeFluidToNBT(remainder,FluidStack.EMPTY);
            if((remainder.is(Items.BUCKET)||mio_icif_cells.isEmptyCell(remainder))&&ContainerToTank.transfer(itemHandler,0,1,inputTank,content,remainder))changed.run();
        }
        if(isEmptyContainer(empty)){
            var filled=empty.is(Items.BUCKET)?new ItemStack(mio_icif_fluids.HOTCOOLANT_BUCKET.get()):mio_icif_cells.getFilledCellForFluidStack(mio_icif_fluids.HOTCOOLANT.get());
            var content=empty.is(Items.BUCKET)?new FluidStack(mio_icif_fluids.HOTCOOLANT.get(),1000):mio_icif_cells.getCellFluid(filled);
            filled.applyComponents(empty.getComponentsPatch());
            if((filled.is(mio_icif_fluids.HOTCOOLANT_BUCKET.get())||FluidStack.matches(content,mio_icif_cells.getCellFluid(filled)))
                    &&ContainerToTank.drainToContainer(itemHandler,2,3,outputTank,content,filled))changed.run();
        }
    }
    public boolean hasHold(){return !hold.isEmpty();}
    public boolean canConvert(){return hold.isEmpty()&&validTanks();}
    private boolean validTanks(){return (inputTank.isEmpty()||inputTank.getFluid().is(mio_icif_fluids.COOLANT.get()))
        &&(outputTank.isEmpty()||outputTank.getFluid().is(mio_icif_fluids.HOTCOOLANT.get()))&&getInputFluidAmount()<=FLUID_CAPACITY&&getOutputFluidAmount()<=FLUID_CAPACITY;}
    /** Called inside the owner's inventory commit; no callbacks expose a half-completed cycle. */
    public void commitCycle(int cold,int hot,int converted){
        inputTank.setFluid(cold==0?FluidStack.EMPTY:new FluidStack(mio_icif_fluids.COOLANT.get(),cold));
        outputTank.setFluid(hot==0?FluidStack.EMPTY:new FluidStack(mio_icif_fluids.HOTCOOLANT.get(),hot));currentHeatGeneration=converted;
    }
    /** SI legacy standalone entry point; owner integration uses the atomic cycle commit instead. */
    public int tick(int emittedHeat){
        if(!live()||!canConvert())return 0;processContainers();
        var converted=FluidReactorCycle.convert(emittedHeat,getInputFluidAmount(),getOutputFluidAmount(),FLUID_CAPACITY);
        commitCycle(getInputFluidAmount()-converted.millibuckets(),getOutputFluidAmount()+converted.millibuckets(),converted.millibuckets());changed.run();return converted.millibuckets();
    }
    public FluidTank getInputTank(){return inputTank;}
    public FluidTank getOutputTank(){return outputTank;}
    public int getCurrentHeatGeneration(){return currentHeatGeneration;}
    public int getInputFluidAmount(){return inputTank.getFluidAmount();}
    public int getOutputFluidAmount(){return outputTank.getFluidAmount();}
    public int getInputCapacity(){return FLUID_CAPACITY;}
    public int getOutputCapacity(){return FLUID_CAPACITY;}
    public MachineItemHandler getItemHandler(){return itemHandler;}
    public ItemStack getStackInSlot(int slot){return itemHandler.getStackInSlot(slot);}
    public void setStackInSlot(int slot,ItemStack stack){itemHandler.setStackInSlot(slot,stack);}
    public void saveToNBT(CompoundTag tag,HolderLookup.Provider registries){
        tag.put("InputTank",inputTank.writeToNBT(registries,new CompoundTag()));tag.put("OutputTank",outputTank.writeToNBT(registries,new CompoundTag()));
        tag.putInt("CurrentHeatGen",currentHeatGeneration);tag.put("ItemHandler",itemHandler.serializeNBT(registries));tag.put("scex_fluid_hold",hold.copy());
    }
    public void loadFromNBT(CompoundTag tag,HolderLookup.Provider registries){
        hold=tag.getCompound("scex_fluid_hold").copy();inputTank.readFromNBT(registries,tag.getCompound("InputTank"));outputTank.readFromNBT(registries,tag.getCompound("OutputTank"));
        currentHeatGeneration=Math.max(0,tag.getInt("CurrentHeatGen"));if(tag.contains("ItemHandler"))itemHandler.deserializeNBT(registries,tag.getCompound("ItemHandler"));
        if(!hold.contains("invalid_fluid_state")&&(!validTanks()||!tag.getCompound("InputTank").isEmpty()&&inputTank.isEmpty()||!tag.getCompound("OutputTank").isEmpty()&&outputTank.isEmpty()))hold.put("invalid_fluid_state",tag.copy());
    }
    @Override public int getTanks(){return 2;}
    @Override public FluidStack getFluidInTank(int tank){return (tank==0?inputTank:outputTank).getFluid().copy();}
    @Override public int getTankCapacity(int tank){return FLUID_CAPACITY;}
    @Override public boolean isFluidValid(int tank,FluidStack stack){return tank==0&&stack.is(mio_icif_fluids.COOLANT.get());}
    @Override public int fill(FluidStack stack,FluidAction action){if(!live())return 0;int n=inputTank.fill(stack,action);if(n>0&&action.execute())changed.run();return n;}
    @Override public FluidStack drain(FluidStack stack,FluidAction action){if(!live())return FluidStack.EMPTY;var out=outputTank.drain(stack,action);if(!out.isEmpty()&&action.execute())changed.run();return out;}
    @Override public FluidStack drain(int amount,FluidAction action){if(!live())return FluidStack.EMPTY;var out=outputTank.drain(amount,action);if(!out.isEmpty()&&action.execute())changed.run();return out;}
}
