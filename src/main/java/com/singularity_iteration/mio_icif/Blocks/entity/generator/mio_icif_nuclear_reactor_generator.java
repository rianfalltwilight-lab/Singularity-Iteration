// SPDX-License-Identifier: Apache-2.0
package com.singularity_iteration.mio_icif.Blocks.entity.generator;

import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Generator;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_block_entities;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.MachineItemHandler;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.SlotLayout;
import com.singularity_iteration.mio_icif.Blocks.generator.mio_icif_Block_Nuclear_Reactor_Generator;
import com.singularity_iteration.mio_icif.Items.Reactor.mio_icif_reactor;
import com.singularity_iteration.mio_icif.Menu.Generator.NuclearReactorGeneratorMenu;
import com.singularity_iteration.mio_icif.Menu.Generator.FluidReactorMenu;
import com.singularity_iteration.mio_icif.api.reactor.IReactorController;
import com.singularity_iteration.mio_icif.api.reactor.IReactorAPI;
import com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage;
import com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier;
import com.singularity_iteration.mio_icif.energy.grid.IEnergyAcceptor;
import com.singularity_iteration.mio_icif.energy.grid.IEnergyTile;
import com.singularity_iteration.mio_icif.energy.heat.HeatStorage;
import com.singularity_iteration.mio_icif.multiblock.mio_icif_multiblock_manager;
import com.singularity_iteration.mio_icif.multiblock.mio_icif_fluid_reactor_validator;
import dev.scex.energy.EnergyAmount;
import dev.scex.si.energy.ContainerToTank;
import dev.scex.si.energy.DemandEnergySource;
import dev.scex.si.energy.IndependentSiEnergy;
import dev.scex.si.energy.PlatformHeatStorage;
import dev.scex.si.reactor.ReactorCycle;
import dev.scex.si.reactor.ReactorInventory;
import dev.scex.si.reactor.FluidReactorCycle;
import dev.scex.si.reactor.ReactorAccidentLatch;
import com.singularity_iteration.mio_icif.Singularity_Iteration_Config;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/** Independently authored from R116/R117 finite reference behavior and SI public factory/save contracts. */
public class mio_icif_nuclear_reactor_generator extends mio_icif_Energy_Generator implements IReactorController,DemandEnergySource {
    public static final int SLOT_COUNT=54,BASE_COLUMNS=3,MAX_COLUMNS=9,ROWS=6,HEAT_CAPACITY_BASE=10000,
        HEAT_CAPACITY_PER_CHAMBER=0,HEAT_MAX_RECEIVE=0,HEAT_MAX_EXTRACT=1000,HEAT_BASE_TEMP=20,HEAT_MAX_TEMP=5000;
    public static final float HEAT_LOSS_FACTOR=0;
    public static final long ENERGY_GENERATION_RATE=0,ENERGY_CAPACITY=1000000,MAX_RECEIVE=0,MAX_EXTRACT=8192;
    private final PlatformHeatStorage heat=new PlatformHeatStorage(10000,0,1000,20,5000,0);
    private final dev.scex.si.reactor.GuardedReactorHeat heatPort=new dev.scex.si.reactor.GuardedReactorHeat(heat,this::operational,this::dirty);
    private final mio_icif_fluid_reactor_handler fluid=new mio_icif_fluid_reactor_handler(this::fluidAvailable,this::dirty);
    private mio_icif_multiblock_manager<mio_icif_fluid_reactor_validator> structure;
    private mio_icif_reactor_mode mode=mio_icif_reactor_mode.GENERATOR;
    private int cycleRemaining=19,lastHeat;
    private EnergyAmount rate=EnergyAmount.ZERO,frameUsed=EnergyAmount.ZERO;
    private long frameAt=Long.MIN_VALUE;
    private boolean ready,cycleVerified;
    private ReactorAccidentLatch accident=new ReactorAccidentLatch();
    private static final String LEGACY_ACCIDENT_FAILURE="Reactor heat reached capacity; accident integration pending";
    private CompoundTag hold=new CompoundTag();
    private String failure="";
    private final IItemHandler automation=new Automation();
    public mio_icif_nuclear_reactor_generator(BlockPos pos,BlockState state){this(pos,state,mio_icif_block_entities.NUCLEAR_REACTOR_GENERATOR_ENTITY_TYPE.get());}
    public mio_icif_nuclear_reactor_generator(BlockPos pos,BlockState state,BlockEntityType<?> type){
        super(pos,state,type,SlotLayout.builder().extra(54).build(),0,ENERGY_CAPACITY,0,MAX_EXTRACT,CableTier.EV);
        setAsPowerSource(MAX_EXTRACT);
        energyStorage.setOutputEnabled(false);
    }
    @Override protected MachineItemHandler createItemHandler(SlotLayout layout){return new ReactorItems(layout);}
    private final class ReactorItems extends MachineItemHandler {
        ReactorItems(SlotLayout layout){super(layout);}
        NonNullList<ItemStack> contents(){return stacks;}
        @Override public int getSlotLimit(int slot){return 1;}
        @Override public boolean isItemValid(int slot,ItemStack stack){return slot>=0&&slot<54&&slot%9<getAvailableColumns()&&(stack.isEmpty()||stack.getItem() instanceof mio_icif_reactor);}
        @Override protected void onContentsChanged(int slot){dirty();}
    }
    private boolean live(){
        if(!(level instanceof ServerLevel server)||!server.getServer().isSameThread()||isRemoved())return false;
        var chunk=server.getChunkSource().getChunkNow(worldPosition.getX()>>4,worldPosition.getZ()>>4);
        return chunk!=null&&chunk.getBlockState(worldPosition).is(getBlockState().getBlock())
            &&chunk.getBlockEntity(worldPosition,LevelChunk.EntityCreationType.CHECK)==this;
    }
    private void dirty(){setChanged();if(level!=null&&!level.isClientSide)ContainerToTank.markUnsaved(this);}
    private boolean operational(){return live()&&ready&&hold.isEmpty()&&accident.mayOperateOrExport();}
    private boolean exportAllowed(){return operational()&&cycleVerified&&mode==mio_icif_reactor_mode.GENERATOR;}
    private void syncOutputGate(){energyStorage.setOutputEnabled(exportAllowed());}
    private void closeAccess(){ready=false;cycleVerified=false;rate=EnergyAmount.ZERO;lastHeat=0;frameUsed=EnergyAmount.ZERO;energyStorage.setOutputEnabled(false);}
    private String accidentFailure(){return "Reactor accident "+accident.state().name().toLowerCase(java.util.Locale.ROOT)
        +": "+(accident.trigger().effect()==ReactorAccidentLatch.Effect.LOCAL_MACHINE?"local machine":"nuclear terrain");}
    public boolean hasReactorAccident(){return !accident.mayOperateOrExport();}
    public String getAccidentState(){return accident.state().name();}
    private void updateActive(){
        if(!live())return;
        var server=(ServerLevel)level;
        var chunk=server.getChunkSource().getChunkNow(worldPosition.getX()>>4,worldPosition.getZ()>>4);
        if(chunk==null||chunk.getBlockEntity(worldPosition,LevelChunk.EntityCreationType.CHECK)!=this)return;
        var current=chunk.getBlockState(worldPosition);boolean running=operational()&&cycleVerified&&isRunning();
        if(current.hasProperty(mio_icif_Block_Nuclear_Reactor_Generator.ACTIVE)
            &&current.getValue(mio_icif_Block_Nuclear_Reactor_Generator.ACTIVE)!=running)
            server.setBlock(worldPosition,current.setValue(mio_icif_Block_Nuclear_Reactor_Generator.ACTIVE,running),3);
    }
    private void frame(){long now=level.getGameTime();if(frameAt!=now){frameAt=now;frameUsed=EnergyAmount.ZERO;}}
    private boolean enabled(){
        if(level.hasNeighborSignal(worldPosition))return true;
        for(Direction side:Direction.values()){
            var at=worldPosition.relative(side);if(isChamber(at)&&level.hasNeighborSignal(at))return true;
        }
        if(structure!=null&&structure.isValid())for(var at:structure.getRedstonePorts())if(level.hasNeighborSignal(at))return true;
        return false;
    }
    public boolean isLiveReactor(){return live();}
    private boolean isChamber(BlockPos at){
        if(!(level instanceof ServerLevel w)||!w.getServer().isSameThread())return false;
        var c=w.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4);
        return c!=null&&c.getBlockEntity(at,LevelChunk.EntityCreationType.CHECK) instanceof com.singularity_iteration.mio_icif.Blocks.entity.reactor.mio_icif_reactor_chamber chamber&&chamber.getConnectedReactor()==this;
    }
    public List<BlockPos> electricalContactPositions(){
        if(!live())return List.of();var out=new ArrayList<BlockPos>();out.add(worldPosition);
        for(var side:Direction.values()){var at=worldPosition.relative(side);if(isChamber(at))out.add(at);}return List.copyOf(out);
    }
    private boolean fluidAvailable(){return operational()&&mode==mio_icif_reactor_mode.FLUID&&isValidFluidReactorStructure();}
    private void refreshStructure(){
        var validator=new mio_icif_fluid_reactor_validator();boolean valid=validator.validate(level,worldPosition).isValid();
        if(structure!=null&&structure.isValid()){
            if(!valid)structure.invalidateStructure(level);
        }else if(valid){new mio_icif_multiblock_manager<>(worldPosition,validator).tryForm(level);}
        else if(mode==mio_icif_reactor_mode.FLUID)setReactorMode(mio_icif_reactor_mode.GENERATOR);
    }
    public static void tick(Level world,BlockPos pos,BlockState state,mio_icif_nuclear_reactor_generator m){
        if(!m.live())return;m.ready=true;
        if(!m.operational()){m.dispatchAccident();if(m.live()){m.syncOutputGate();m.updateActive();}return;}
        m.frame();
        if(m.cycleRemaining==19)m.refreshStructure();
        if(!m.operational())return;
        if(m.fluidAvailable())m.fluid.processContainers();
        if(!m.operational())return;
        if(--m.cycleRemaining<0){m.cycleRemaining=19;m.operate();}
        if(!m.live())return;
        m.syncOutputGate();m.updateActive();
        if(m.live())m.dirty();
    }
    private void operate(){
        if(!operational())return;
        rate=EnergyAmount.ZERO;lastHeat=0;cycleVerified=false;syncOutputGate();
        refreshStructure();
        if(!operational())return;
        boolean liquid=mode==mio_icif_reactor_mode.FLUID;
        if(liquid&&!fluid.canConvert()){failure="Saved fluid state requires recovery";return;}
        int columns=getAvailableColumns();ItemStack[] expected=new ItemStack[54],replacement=new ItemStack[54];int[] slots=new int[54];
        ReactorCycle.Part[] parts=new ReactorCycle.Part[54];
        try{
            for(int i=0;i<54;i++){slots[i]=i;expected[i]=itemHandler.getStackInSlot(i).copy();if(i%9<columns)parts[i]=ReactorInventory.read(expected[i]);}
            FluidReactorCycle.Result converted=liquid?FluidReactorCycle.step(parts,columns,heat.getHeatStored(),enabled(),fluid.getInputFluidAmount(),fluid.getOutputFluidAmount(),fluid.FLUID_CAPACITY):null;
            ReactorCycle.Result result=liquid?converted.cycle():ReactorCycle.step(parts,columns,heat.getHeatStored(),enabled());
            var next=result.parts();var depleted=result.depletedFuel();
            for(int i=0;i<54;i++)replacement[i]=i%9<columns?ReactorInventory.write(expected[i],parts[i],next[i],depleted[i]):expected[i].copy();
            // Decide from the final fluid-adjusted result. Never reject the pre-cooling input heat.
            int explosiveCells=0,containmentPlates=0;
            for(var part:parts)if(part!=null&&part.profile().kind()==ReactorCycle.Kind.FUEL&&part.remaining()>0)
                explosiveCells=Math.addExact(explosiveCells,part.profile().cells());
            else if(part!=null&&part.profile().kind()==ReactorCycle.Kind.PLATING&&part.profile().extraHull()==500)
                containmentPlates=Math.incrementExact(containmentPlates);
            var trigger=ReactorAccidentLatch.decide(result.hullHeat(),result.maxHullHeat(),level.getGameTime(),
                Singularity_Iteration_Config.ENABLE_NUCLEAR_EXPLOSION.get(),explosiveCells,containmentPlates);
            int committedHeat=Math.toIntExact(result.generatedHeat());
            var committedRate=trigger==null?EnergyAmount.fromDouble(result.euPerTick()):EnergyAmount.ZERO;
            if(!operational())return;
            if(!itemHandler.scexCommitSlots(slots,expected,replacement,()->{
                heat.setCapacity(result.maxHullHeat());heat.setHeat(result.hullHeat());lastHeat=committedHeat;
                if(converted!=null)fluid.commitCycle(converted.coolant(),converted.hotCoolant(),converted.converted());
                accident.armAfterCommit(trigger);cycleVerified=true;rate=committedRate;
                if(hasReactorAccident())frameUsed=EnergyAmount.ZERO;
                failure=hasReactorAccident()?accidentFailure():"";syncOutputGate();
            })){failure="Reactor inventory changed during cycle";rate=EnergyAmount.ZERO;cycleVerified=false;syncOutputGate();}
            if(hasReactorAccident()&&live())IndependentSiEnergy.changed(this);
        }catch(IllegalArgumentException|ArithmeticException error){failure=error.getMessage();rate=EnergyAmount.ZERO;cycleVerified=false;syncOutputGate();}
        if(live())dirty();
    }
    private void dispatchAccident(){
        if(accident.state()!=ReactorAccidentLatch.State.PENDING||!live())return;
        var trigger=accident.trigger();var server=(ServerLevel)level;
        float power=trigger.effect()==ReactorAccidentLatch.Effect.LOCAL_MACHINE?1.5F:trigger.terrainPower();
        if(trigger.effect()==ReactorAccidentLatch.Effect.NUCLEAR_TERRAIN&&!loadedBlastWindow(server,power))return;
        if(!accident.claimEffect())return;
        failure=accidentFailure();rate=EnergyAmount.ZERO;frameUsed=EnergyAmount.ZERO;cycleVerified=false;syncOutputGate();dirty();
        try{
            var interaction=trigger.effect()==ReactorAccidentLatch.Effect.LOCAL_MACHINE
                ?Level.ExplosionInteraction.NONE:Level.ExplosionInteraction.BLOCK;
            server.explode(null,worldPosition.getX()+0.5,worldPosition.getY()+0.5,worldPosition.getZ()+0.5,power,interaction);
            if(trigger.effect()==ReactorAccidentLatch.Effect.LOCAL_MACHINE&&live())server.removeBlock(worldPosition,false);
            accident.closeEffect();
            if(live()){failure=accidentFailure();dirty();}
        }catch(RuntimeException|Error effectFailure){
            accident.markUncertain();
            if(live()){failure=accidentFailure()+": "+effectFailure.getClass().getSimpleName();dirty();}
        }
    }
    private boolean loadedBlastWindow(ServerLevel server,float power){
        int radius=(int)Math.ceil(power*2.0F);
        int minX=(worldPosition.getX()-radius)>>4,maxX=(worldPosition.getX()+radius)>>4;
        int minZ=(worldPosition.getZ()-radius)>>4,maxZ=(worldPosition.getZ()+radius)>>4;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)if(server.getChunkSource().getChunkNow(x,z)==null)return false;
        return true;
    }
    @Override public CustomEUEnergyStorage ownedEnergy(){return energyStorage;}
    @Override public int outputFaces(){return exportAllowed()?63:0;}
    private EnergyAmount allowance(){if(!exportAllowed())return EnergyAmount.ZERO;frame();return frameUsed.compareTo(rate)>=0?EnergyAmount.ZERO:rate.subtract(frameUsed);}
    @Override public EnergyAmount potentialEnergy(){if(!exportAllowed())return EnergyAmount.ZERO;var balance=energyStorage.scexExactAmount();return balance.add(allowance().min(balance.roomBelow(ENERGY_CAPACITY)));}
    @Override public void prepareEnergy(EnergyAmount requested){
        if(!exportAllowed()||!energyStorage.scexNetworkControlled())return;var available=allowance();var balance=energyStorage.scexExactAmount();
        if(requested.compareTo(balance)<=0||available.isZero())return;
        var credit=requested.subtract(balance).min(available).min(balance.roomBelow(ENERGY_CAPACITY));
        var accepted=energyStorage.scexGenerateEnergy(credit,false);frameUsed=frameUsed.add(accepted);if(!accepted.isZero())dirty();
    }
    @Override public void onLoad(){ready=false;cycleVerified=false;energyStorage.setOutputEnabled(false);super.onLoad();IndependentSiEnergy.changed(this);}
    @Override public void setRemoved(){closeAccess();super.setRemoved();IndependentSiEnergy.changed(this);}
    @Override public void clearRemoved(){ready=false;cycleVerified=false;energyStorage.setOutputEnabled(false);super.clearRemoved();IndependentSiEnergy.changed(this);}
    @Override public void onChunkUnloaded(){closeAccess();super.onChunkUnloaded();IndependentSiEnergy.changed(this);}
    @Override public double getOfferedEnergy(){return 0;}
    @Override public void drawEnergy(double amount){}
    @Override public boolean emitsEnergyTo(IEnergyAcceptor acceptor,Direction side){return false;}
    public long extractPowerForConsumer(long amount,boolean simulate){return 0;}
    public int getPacketCount(){return 1;}
    @Override public int getSourceTier(){return 4;}
    @Override public long getPowerOutput(){return MAX_EXTRACT;}
    @Override public int getFuelBurnTime(ItemStack stack){return 0;}
    public HeatStorage getHeatStorage(){return heat;}
    public dev.scex.si.reactor.GuardedReactorHeat getHeatStorageCapability(Direction side){return heatPort;}
    @Override public long getCurrentHeat(){return heat.getHeatStored();}
    @Override public long getMaxHeat(){return heat.getMaxHeatStored();}
    @Override public double getCurrentTemperature(){return heat.getTemperature();}
    public int getCurrentHeatGeneration(){return lastHeat;}
    @Override public long getCurrentEnergyGeneration(){return rate.whole();}
    public double getExactEnergyGeneration(){return rate.toDouble();}
    public int getCurrentOutput(){return (int)Math.min(Integer.MAX_VALUE,rate.whole());}
    @Override public boolean isBurning(){return isRunning();}
    public boolean isRunning(){return !hasReactorAccident()&&!isRemoved()&&(!rate.isZero()||mode==mio_icif_reactor_mode.FLUID&&lastHeat>0)&&failure.isEmpty();}
    public String getReactorFailure(){return failure;}
    public boolean hasLegacyHold(){return !hold.isEmpty();}
    @Override public int getAvailableColumns(){int n=3;for(var side:Direction.values())if(isChamber(worldPosition.relative(side)))n++;return n;}
    public int getCurrentSlotCount(){return 6*getAvailableColumns();}
    @Override public List<IEnergyTile> getSubTiles(){
        var out=new ArrayList<IEnergyTile>();out.add(this);
        if(level!=null)for(var side:Direction.values()){var at=worldPosition.relative(side);if(isChamber(at)&&level.getBlockEntity(at) instanceof IEnergyTile tile)out.add(tile);}return List.copyOf(out);
    }
    public NonNullList<ItemStack> getReactorItems(){return ((ReactorItems)itemHandler).contents();}
    @Override public int getMaxStackSize(){return 1;}
    @Override public int[] getSlotsForFace(Direction side){return java.util.stream.IntStream.range(0,54).toArray();}
    @Override public boolean canPlaceItemThroughFace(int slot,ItemStack stack,Direction side){return operational()&&itemHandler.isItemValid(slot,stack);}
    @Override public boolean canTakeItemThroughFace(int slot,ItemStack stack,Direction side){return operational();}
    @Override public IItemHandler getItemHandlerCapability(Direction side){return automation;}
    public IItemHandler getReactorItemHandler(){return itemHandler;}
    private final class Automation implements IItemHandler {
        @Override public int getSlots(){return 54;}
        @Override public ItemStack getStackInSlot(int slot){return itemHandler.getStackInSlot(slot).copy();}
        @Override public ItemStack insertItem(int slot,ItemStack stack,boolean simulate){return operational()?itemHandler.insertItem(slot,stack,simulate):stack;}
        @Override public ItemStack extractItem(int slot,int amount,boolean simulate){return operational()?itemHandler.extractItem(slot,amount,simulate):ItemStack.EMPTY;}
        @Override public int getSlotLimit(int slot){return 1;}
        @Override public boolean isItemValid(int slot,ItemStack stack){return operational()&&itemHandler.isItemValid(slot,stack);}
    }
    public void setFluidReactorMultiblock(mio_icif_multiblock_manager<mio_icif_fluid_reactor_validator> manager){structure=manager;setReactorMode(manager!=null&&manager.isValid()?mio_icif_reactor_mode.FLUID:mio_icif_reactor_mode.GENERATOR);}
    public void onMultiblockBroken(){structure=null;setReactorMode(mio_icif_reactor_mode.GENERATOR);}
    public mio_icif_multiblock_manager<mio_icif_fluid_reactor_validator> getFluidReactorMultiblock(){return structure;}
    public mio_icif_reactor_mode getReactorMode(){return mode;}
    @Override public IReactorAPI.ReactorMode getApiReactorMode(){return mode==mio_icif_reactor_mode.FLUID?IReactorAPI.ReactorMode.FLUID:IReactorAPI.ReactorMode.GENERATOR;}
    public void setReactorMode(mio_icif_reactor_mode next){mode=java.util.Objects.requireNonNull(next);rate=EnergyAmount.ZERO;syncOutputGate();dirty();}
    @Override public boolean isValidFluidReactorStructure(){return structure!=null&&structure.isValid()&&level!=null&&new mio_icif_fluid_reactor_validator().validate(level,worldPosition).isValid();}
    @Override public mio_icif_fluid_reactor_handler getFluidHandler(){return fluid;}
    public IFluidHandler getFluidHandlerCapability(Direction side){return fluidAvailable()?fluid:null;}
    public int getInputFluidAmount(){return fluid.getInputFluidAmount();}
    public int getOutputFluidAmount(){return fluid.getOutputFluidAmount();}
    @Override public Component getDisplayName(){return Component.translatable("block.mio_icif.generator.block_nuclear_reactor_generator");}
    @Override public AbstractContainerMenu createMenu(int id,Inventory inventory,Player player){return mode==mio_icif_reactor_mode.FLUID?new FluidReactorMenu(id,inventory,this):new NuclearReactorGeneratorMenu(id,inventory,this,itemHandler,getContainerData());}
    public ContainerData getContainerData(){return new ContainerData(){
        @Override public int get(int i){return switch(i){case 0->(int)energyStorage.getAmount();case 1->(int)ENERGY_CAPACITY;case 2->(int)Math.min(Integer.MAX_VALUE,getCurrentHeat());case 3->(int)getMaxHeat();case 4->getCurrentOutput();case 5->getAvailableColumns();default->0;};}
        @Override public void set(int i,int value){}
        @Override public int getCount(){return 6;}
    };}
    public ContainerData getGeneratorContainerData(){return getContainerData();}
    public ContainerData getFluidContainerData(){return new FluidData();}
    private final class FluidData implements ContainerData {
        @Override public int get(int i){return switch(i){case 0->(int)Math.min(Integer.MAX_VALUE,getCurrentHeat());case 1->(int)getMaxHeat();case 2->(int)getCurrentTemperature();case 3->getInputFluidAmount();case 4->getOutputFluidAmount();default->0;};}
        @Override public void set(int i,int value){}
        @Override public int getCount(){return 5;}
    }
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider provider){
        super.saveAdditional(tag,provider);tag.putLong("HeatStored",heat.getHeatStored());tag.putLong("MaxHeatStored",heat.getMaxHeatStored());
        tag.putInt("CurrentHeatGeneration",lastHeat);tag.putLong("CurrentEnergyGeneration",rate.whole());tag.putBoolean("IsRunning",isRunning());tag.putInt("ReactorCycleTicks",cycleRemaining);tag.putString("ReactorMode",mode.getName());
        ListTag legacy=new ListTag();for(int i=0;i<54;i++)if(!getItem(i).isEmpty()){CompoundTag item=(CompoundTag)getItem(i).save(provider);item.putByte("Slot",(byte)i);legacy.add(item);}tag.put("ReactorItems",legacy);
        CompoundTag fluidTag=new CompoundTag();fluid.saveToNBT(fluidTag,provider);tag.put("FluidHandler",fluidTag);
        CompoundTag saved=new CompoundTag();saved.putInt("version",2);saved.putLong("rate",rate.whole());saved.putLong("rate_fraction",rate.fraction());saved.put("hold",hold.copy());saved.putString("failure",failure);saved.put("accident",saveAccident());tag.put("scex_reactor",saved);
    }
    @Override public void loadAdditional(CompoundTag tag,HolderLookup.Provider provider){
        super.loadAdditional(tag,provider);hold=new CompoundTag();failure="";lastHeat=0;accident=new ReactorAccidentLatch();closeAccess();frameAt=Long.MIN_VALUE;
        long stored=tag.getLong("HeatStored");if(stored<0){hold.put("invalid_heat",tag.get("HeatStored").copy());stored=0;}heat.setHeat(stored);
        long max=tag.getLong("MaxHeatStored");heat.setCapacity(max>0?max:10000);
        cycleRemaining=tag.contains("ReactorCycleTicks")?tag.getInt("ReactorCycleTicks"):19;
        if(cycleRemaining<0||cycleRemaining>19){hold.put("invalid_cycle",tag.get("ReactorCycleTicks").copy());cycleRemaining=19;}
        String savedMode=tag.getString("ReactorMode");mode=mio_icif_reactor_mode.fromString(savedMode);
        if(!savedMode.isEmpty()&&!savedMode.equals("generator")&&!savedMode.equals("fluid"))hold.putString("unknown_mode",savedMode);
        if(tag.contains("FluidHandler",Tag.TAG_COMPOUND))fluid.loadFromNBT(tag.getCompound("FluidHandler"),provider);
        if(!tag.contains("Items",Tag.TAG_COMPOUND)&&tag.contains("ReactorItems",Tag.TAG_LIST)){
            boolean[] seen=new boolean[54];ListTag items=tag.getList("ReactorItems",Tag.TAG_COMPOUND);
            for(int i=0;i<items.size();i++){CompoundTag entry=items.getCompound(i);int slot=entry.getByte("Slot")&255;var stack=ItemStack.parse(provider,entry);
                if(slot>=54||seen[slot]||stack.isEmpty()){hold.put("legacy_inventory",items.copy());continue;}seen[slot]=true;itemHandler.setStackInSlot(slot,stack.get());}
        }
        if(tag.contains("Items",Tag.TAG_COMPOUND)){
            CompoundTag inventory=tag.getCompound("Items");ListTag entries=inventory.getList("Items",Tag.TAG_COMPOUND);boolean[] seen=new boolean[54];
            if(inventory.getInt("Size")!=54||!inventory.getAllKeys().stream().allMatch(k->k.equals("Size")||k.equals("Items")))hold.put("unknown_inventory",inventory.copy());
            for(int i=0;i<entries.size();i++){var entry=entries.getCompound(i);int slot=entry.getInt("Slot");var parsed=ItemStack.parse(provider,entry.getCompound("Item"));
                if(slot<0||slot>=54||seen[slot]||parsed.isEmpty()){hold.put("invalid_inventory",inventory.copy());continue;}seen[slot]=true;
            }
            if(tag.contains("ReactorItems",Tag.TAG_LIST)){
                ItemStack[] legacy=new ItemStack[54];java.util.Arrays.fill(legacy,ItemStack.EMPTY);ListTag items=tag.getList("ReactorItems",Tag.TAG_COMPOUND);boolean valid=true;boolean[] legacySeen=new boolean[54];
                for(int i=0;i<items.size();i++){var entry=items.getCompound(i);int slot=entry.getByte("Slot")&255;var parsed=ItemStack.parse(provider,entry);
                    if(slot>=54||legacySeen[slot]||parsed.isEmpty()){valid=false;continue;}legacySeen[slot]=true;legacy[slot]=parsed.get();}
                for(int i=0;i<54;i++)valid&=ItemStack.matches(legacy[i],itemHandler.getStackInSlot(i));
                if(!valid){hold.put("conflicting_legacy_inventory",items.copy());hold.put("primary_inventory",inventory.copy());}
            }
        }
        if(tag.contains("scex_reactor",Tag.TAG_COMPOUND)){
            CompoundTag own=tag.getCompound("scex_reactor");
            int version=own.getInt("version");
            if(version==1||version==2){
                hold.merge(own.getCompound("hold").copy());failure=own.getString("failure");
                try{rate=new EnergyAmount(own.getLong("rate"),own.getLong("rate_fraction"));if(rate.whole()>8192)throw new IllegalArgumentException("Invalid saved rate");}
                catch(IllegalArgumentException error){hold.put("invalid_rate",own.copy());rate=EnergyAmount.ZERO;}
                if(version==2){
                    if(!own.getAllKeys().equals(java.util.Set.of("version","rate","rate_fraction","hold","failure","accident")))hold.put("unknown_reactor_fields",own.copy());
                    if(!own.contains("version",Tag.TAG_INT)||!own.contains("rate",Tag.TAG_LONG)||!own.contains("rate_fraction",Tag.TAG_LONG)
                        ||!own.contains("hold",Tag.TAG_COMPOUND)||!own.contains("failure",Tag.TAG_STRING))hold.put("invalid_reactor_fields",own.copy());
                    try{
                        if(!own.contains("accident",Tag.TAG_COMPOUND))throw new IllegalArgumentException("Missing accident state");
                        accident=loadAccident(own.getCompound("accident"));
                        var trigger=accident.trigger();
                        if(trigger!=null&&(trigger.finalHeat()!=stored||trigger.finalCapacity()!=heat.getMaxHeatStored()))throw new IllegalArgumentException("Conflicting accident thermal state");
                    }catch(IllegalArgumentException error){hold.put("invalid_accident",own.copy());accident=new ReactorAccidentLatch();}
                }else if(LEGACY_ACCIDENT_FAILURE.equals(failure)){
                    if(hold.isEmpty()&&rate.isZero()&&max>0&&stored>=max){
                        accident.armAfterCommit(ReactorAccidentLatch.decide(stored,max,0,Singularity_Iteration_Config.ENABLE_NUCLEAR_EXPLOSION.get()));
                    }else hold.put("inconsistent_legacy_accident",own.copy());
                }
            }else hold.put("future_reactor",own.copy());
        }else if(tag.contains("scex_reactor"))hold.put("invalid_reactor_tag",tag.get("scex_reactor").copy());
        if(hasReactorAccident()){rate=EnergyAmount.ZERO;failure=accidentFailure();}
        if(!hold.isEmpty()){rate=EnergyAmount.ZERO;failure="Saved reactor state requires recovery";}
        setAsPowerSource(MAX_EXTRACT);energyStorage.setOutputEnabled(false);
    }
    private CompoundTag saveAccident(){
        CompoundTag tag=new CompoundTag();tag.putInt("version",2);tag.putString("state",accident.state().name());
        var trigger=accident.trigger();
        if(trigger!=null){tag.putLong("final_heat",trigger.finalHeat());tag.putLong("final_capacity",trigger.finalCapacity());
            tag.putLong("game_time",trigger.gameTime());tag.putString("effect",trigger.effect().name());
            tag.putInt("explosive_cells",trigger.explosiveCells());tag.putInt("containment_plates",trigger.containmentPlates());}
        return tag;
    }
    private static ReactorAccidentLatch loadAccident(CompoundTag tag){
        if(!tag.contains("version",Tag.TAG_INT)||(tag.getInt("version")!=1&&tag.getInt("version")!=2)||!tag.contains("state",Tag.TAG_STRING))throw new IllegalArgumentException("Invalid accident schema");
        int version=tag.getInt("version");
        var state=ReactorAccidentLatch.State.valueOf(tag.getString("state"));
        if(state==ReactorAccidentLatch.State.OPEN){
            if(!tag.getAllKeys().equals(java.util.Set.of("version","state")))throw new IllegalArgumentException("Unexpected open accident payload");
            return new ReactorAccidentLatch();
        }
        var expected=version==1?java.util.Set.of("version","state","final_heat","final_capacity","game_time","effect")
            :java.util.Set.of("version","state","final_heat","final_capacity","game_time","effect","explosive_cells","containment_plates");
        if(!tag.getAllKeys().equals(expected)
            ||!tag.contains("final_heat",Tag.TAG_LONG)||!tag.contains("final_capacity",Tag.TAG_LONG)
            ||!tag.contains("game_time",Tag.TAG_LONG)||!tag.contains("effect",Tag.TAG_STRING))throw new IllegalArgumentException("Invalid accident trigger payload");
        if(version==2&&(!tag.contains("explosive_cells",Tag.TAG_INT)||!tag.contains("containment_plates",Tag.TAG_INT)))throw new IllegalArgumentException("Invalid accident profile payload");
        var trigger=new ReactorAccidentLatch.Trigger(tag.getLong("final_heat"),tag.getLong("final_capacity"),tag.getLong("game_time"),ReactorAccidentLatch.Effect.valueOf(tag.getString("effect")),
            version==2?tag.getInt("explosive_cells"):0,version==2?tag.getInt("containment_plates"):0);
        return ReactorAccidentLatch.restore(new ReactorAccidentLatch.Saved(state,trigger));
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider provider){return saveWithoutMetadata(provider);}
    @Override public void handleUpdateTag(CompoundTag tag,HolderLookup.Provider provider){loadAdditional(tag,provider);}
}
