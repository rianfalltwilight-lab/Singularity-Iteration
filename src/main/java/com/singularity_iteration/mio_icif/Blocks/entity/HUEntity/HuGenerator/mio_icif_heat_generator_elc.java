// SPDX-License-Identifier: Apache-2.0
package com.singularity_iteration.mio_icif.Blocks.entity.HUEntity.HuGenerator;

import com.singularity_iteration.mio_icif.Blocks.HUGenerator.mio_icif_block_heat_generator_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_block_entities;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_producer;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.MachineItemHandler;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.SlotLayout;
import com.singularity_iteration.mio_icif.Items.Resource.mio_icif_resources;
import com.singularity_iteration.mio_icif.Menu.HUEntity.HeatGeneratorElcMenu;
import com.singularity_iteration.mio_icif.api.capability.IMioIcifCapabilities;
import com.singularity_iteration.mio_icif.api.machine.IHeatGeneratorBlock;
import com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier;
import dev.scex.si.energy.ContainerToTank;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Independent R106 adapter. R102 normal fixed-binary observations specify 1 EU/HU and 10 HU/coil/tick.
 * R104 public SI ABI specifies inventory/menu integration. The predecessor body was never opened. */
public class mio_icif_heat_generator_elc extends mio_icif_producer
        implements IHeatGeneratorBlock, IMioIcifCapabilities.IHeatStorage {
    public static final int BATTERY_SLOT=0, COIL_SLOT_START=1, COIL_SLOT_COUNT=10, TOTAL_SLOTS=11;
    private static final int MAX_HEAT=100;
    private long heat,capacity=MAX_HEAT,uncertainHeat;
    private String transferFailure="";
    private boolean transferring;
    private int heatOutput;
    private long outputFrame=Long.MIN_VALUE,frameExtracted;
    private final FacePort[] ports=new FacePort[6];

    public mio_icif_heat_generator_elc(BlockPos pos,BlockState state) {
        super(pos,state,mio_icif_block_entities.HEAT_GENERATOR_ELC.get(),10000,2048,0,1,
                SlotLayout.builder().battery().coil(COIL_SLOT_COUNT).build(),0,CableTier.EV);
        for (var side:Direction.values()) ports[side.get3DDataValue()]=new FacePort(side);
    }
    @Override protected MachineItemHandler createItemHandler(SlotLayout layout) {
        var result=new MachineItemHandler(layout) {
            @Override protected void onContentsChanged(int slot) { dirty(); }
        };
        result.setValidator(this);return result;
    }
    @Override protected boolean isItemValidForSlot(int slot,ItemStack stack) {
        return slot==BATTERY_SLOT ? isBattery(stack)
                : slot>=COIL_SLOT_START && slot<TOTAL_SLOTS && stack.is(mio_icif_resources.COIL.get());
    }
    @Override protected int[] getSlotsForDirection(Direction side) { return new int[]{BATTERY_SLOT}; }
    @Override protected boolean canInsertItem(int slot,ItemStack stack,Direction side) {
        return slot==BATTERY_SLOT && isItemValidForSlot(slot,stack);
    }
    @Override protected boolean canExtractItem(int slot,Direction side) { return slot==BATTERY_SLOT; }
    @Override protected boolean canWork() { return false; }
    @Override protected void doWork() { }
    private void dirty() { setChanged();if(level!=null && !level.isClientSide)ContainerToTank.markUnsaved(this); }
    private boolean live() {
        if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread() || isRemoved())return false;
        var chunk=server.getChunkSource().getChunkNow(worldPosition.getX()>>4,worldPosition.getZ()>>4);
        return chunk!=null && chunk.getBlockEntity(worldPosition,LevelChunk.EntityCreationType.CHECK)==this;
    }
    private Direction front() { return getBlockState().getValue(mio_icif_block_heat_generator_elc.FACING); }
    private void frame() {
        long now=level==null ? 0 : level.getGameTime();
        if(now!=outputFrame){outputFrame=now;frameExtracted=0;}
    }
    public static void tick(Level level,BlockPos pos,BlockState state,mio_icif_heat_generator_elc m) {
        if(!m.live() || m.transferring)return;
        m.frame();m.handleBatterySlot();
        int made=0;
        if(m.uncertainHeat==0) {
            long target=Math.min(m.capacity,m.getHeatGeneration());
            long budget=Math.min(m.getEnergyStorageInternal().getAmount(),Math.max(0,target-m.heat));
            if(budget>0) {
                long paid=m.getEnergyStorageInternal().consumeEnergyInternal(budget,false);
                m.heat+=paid;made=(int)paid;m.dirty();
            }
            m.pushFront();
        }
        m.heatOutput=made;m.isWorking=made>0;m.progress=m.isWorking?1:0;
        var actual=m.getBlockState();
        if(actual.getValue(mio_icif_block_heat_generator_elc.ACTIVE)!=m.isWorking)
            level.setBlock(pos,actual.setValue(mio_icif_block_heat_generator_elc.ACTIVE,m.isWorking),3);
    }
    /** One reserved transfer. An invalid receipt retains the unknown amount and blocks further conversion. */
    private void pushFront() {
        if(heat==0 || frameExtracted>=MAX_HEAT || !(level instanceof ServerLevel server))return;
        var side=front();var targetPos=worldPosition.relative(side);
        if(server.getChunkSource().getChunkNow(targetPos.getX()>>4,targetPos.getZ()>>4)==null)return;
        var target=server.getCapability(IMioIcifCapabilities.HEAT_STORAGE_BLOCK,targetPos,side.getOpposite());
        if(target==null || target==this)return;
        transferring=true;
        try {
            if(!target.canReceiveHeat())return;
            long offered=Math.min(heat,MAX_HEAT-frameExtracted);
            long proposed=target.receiveHeat(offered,true);
            if(proposed<=0 || proposed>offered)return;
            heat-=proposed;uncertainHeat=proposed;dirty();
            long accepted=target.receiveHeat(proposed,false);
            if(accepted<0 || accepted>proposed) {
                transferFailure="Receiver returned an invalid HU receipt";dirty();return;
            }
            heat+=proposed-accepted;uncertainHeat=0;frameExtracted+=accepted;
            transferFailure="";dirty();
        } catch(RuntimeException failure) {
            if(uncertainHeat>0){transferFailure=failure.getClass().getName();dirty();}
        } finally {transferring=false;}
    }
    public int getCoilCount() {
        int count=0;
        for(int slot=COIL_SLOT_START;slot<TOTAL_SLOTS;slot++) {
            var stack=itemHandler.getStackInSlot(slot);
            if(stack.getCount()==1 && stack.is(mio_icif_resources.COIL.get()))count++;
        }
        return count;
    }
    public IMioIcifCapabilities.IHeatStorage getHeatStorage(){return this;}
    public IMioIcifCapabilities.IHeatStorage getHeatStorageCapability(Direction side) {
        return side==null ? this : side==front() ? ports[side.get3DDataValue()] : null;
    }
    @Override public long getHeatStored(){return heat;}
    @Override public long getMaxHeatStored(){return capacity;}
    @Override public long receiveHeat(long amount,boolean simulate){return 0;}
    @Override public long extractHeat(long amount,boolean simulate) {
        if(transferring || amount<=0)return 0;
        frame();long taken=Math.min(Math.min(amount,heat),Math.max(0,MAX_HEAT-frameExtracted));
        if(!simulate && taken>0){heat-=taken;frameExtracted+=taken;dirty();}return taken;
    }
    @Override public boolean canExtractHeat(){frame();return !transferring && heat>0 && frameExtracted<MAX_HEAT;}
    @Override public boolean canReceiveHeat(){return false;}
    @Override public int getTemperature(){return dev.scex.energy.BoundedUnits.gauge(heat,capacity,20,1000);}
    @Override public boolean isOverheated(){return getTemperature()>=800;}
    @Override public long getHeatLossPerTick(){return 0;}
    @Override public long getMaxReceive(){return 0;}
    @Override public long getMaxExtract(){return MAX_HEAT;}
    @Override public void setHeat(long value){if(transferring)return;long next=Math.max(0,value);if(next!=heat){heat=next;dirty();}}
    @Override public void setCapacity(long value){if(transferring)return;long next=Math.min(MAX_HEAT,Math.max(0,value));if(next!=capacity){capacity=next;dirty();}}
    @Override public long applyHeatLoss(){return 0;}
    @Override public long consumeHeatInternal(long amount,boolean simulate){return extractHeat(amount,simulate);}
    @Override public long generateHeatInternal(long amount,boolean simulate) {
        if(transferring || amount<=0)return 0;long accepted=Math.min(amount,Math.max(0,capacity-heat));
        if(!simulate && accepted>0){heat+=accepted;dirty();}return accepted;
    }
    public int getHeatGeneration(){return getCoilCount()*getHeatPerCoil();}
    public static int getHeatPerCoil(){return 10;}
    public static int getMaxHeatGeneration(){return MAX_HEAT;}
    public float getEfficiency(){return getCoilCount()/10.0f;}
    public long getHeatCapacity(){return capacity;}
    public float getHeatProgress(){return capacity==0?0:(float)Math.min(1.0,(double)heat/capacity);}
    @Override public Component getDisplayName(){return Component.translatable("block.mio_icif.hugenerator.block_heat_generator_elc");}
    @Override public AbstractContainerMenu createMenu(int id,Inventory inventory,Player player){return new HeatGeneratorElcMenu(id,inventory,this);}
    @Override public int getHeatOutput(){return heatOutput;}
    @Override public boolean isGenerating(){return isWorking;}
    @Override public int getBurnTime(){return 0;}
    @Override public int getBurnDuration(){return 0;}
    @Override public int getHeatGenerationRate(){return getHeatGeneration();}
    public long getUncertainHeat(){return uncertainHeat;}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider provider) {
        super.saveAdditional(tag,provider);var saved=new CompoundTag();saved.putInt("version",1);
        saved.putLong("heat",heat);saved.putLong("capacity",capacity);saved.putLong("uncertain",uncertainHeat);
        saved.putString("failure",transferFailure);tag.put("scex_electric_heater",saved);
    }
    @Override public void loadAdditional(CompoundTag tag,HolderLookup.Provider provider) {
        super.loadAdditional(tag,provider);heat=0;capacity=MAX_HEAT;uncertainHeat=0;transferFailure="";
        if(tag.contains("scex_electric_heater",Tag.TAG_COMPOUND)) {
            var saved=tag.getCompound("scex_electric_heater");
            if(saved.getInt("version")!=1)throw new IllegalArgumentException("Unsupported electric heater save version");
            heat=Math.max(0,saved.getLong("heat"));capacity=Math.min(MAX_HEAT,Math.max(0,saved.getLong("capacity")));
            uncertainHeat=Math.max(0,saved.getLong("uncertain"));transferFailure=saved.getString("failure");
        }
        frameExtracted=0;outputFrame=Long.MIN_VALUE;transferring=false;heatOutput=0;
    }
    private final class FacePort implements IMioIcifCapabilities.IHeatStorage {
        private final Direction side;
        FacePort(Direction side){this.side=side;}
        private boolean usable(){return live() && side==front();}
        @Override public long getHeatStored(){return heat;}
        @Override public long getMaxHeatStored(){return capacity;}
        @Override public long receiveHeat(long amount,boolean simulate){return 0;}
        @Override public long extractHeat(long amount,boolean simulate){return usable()?mio_icif_heat_generator_elc.this.extractHeat(amount,simulate):0;}
        @Override public boolean canExtractHeat(){return usable() && mio_icif_heat_generator_elc.this.canExtractHeat();}
        @Override public boolean canReceiveHeat(){return false;}
        @Override public int getTemperature(){return mio_icif_heat_generator_elc.this.getTemperature();}
        @Override public boolean isOverheated(){return mio_icif_heat_generator_elc.this.isOverheated();}
        @Override public long getHeatLossPerTick(){return 0;}
        @Override public long getMaxReceive(){return 0;}
        @Override public long getMaxExtract(){return usable()?MAX_HEAT:0;}
        @Override public long consumeHeatInternal(long amount,boolean simulate){return extractHeat(amount,simulate);}
        @Override public long generateHeatInternal(long amount,boolean simulate){return 0;}
    }
}
