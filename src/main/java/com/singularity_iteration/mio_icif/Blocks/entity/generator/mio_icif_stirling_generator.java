// SPDX-License-Identifier: Apache-2.0
package com.singularity_iteration.mio_icif.Blocks.entity.generator;

import com.singularity_iteration.mio_icif.Blocks.entity.HUEntity.mio_icif_HeatU_Block;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_block_entities;
import com.singularity_iteration.mio_icif.Blocks.generator.mio_icif_block_stirling_generator;
import com.singularity_iteration.mio_icif.Menu.Generator.StirlingGeneratorMenu;
import com.singularity_iteration.mio_icif.api.capability.IMioIcifCapabilities;
import com.singularity_iteration.mio_icif.api.machine.IGeneratorBlock;
import com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage;
import com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier;
import com.singularity_iteration.mio_icif.energy.EnergyUnit.IEUEnergyStorage;
import com.singularity_iteration.mio_icif.energy.grid.IEnergyAcceptor;
import com.singularity_iteration.mio_icif.energy.grid.IEnergySource;
import dev.scex.energy.EnergyAmount;
import dev.scex.si.energy.ContainerToTank;
import dev.scex.si.energy.DemandEnergySource;
import dev.scex.si.energy.IndependentSiEnergy;
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
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Independently authored from normal R102 HU/EU receipts. No predecessor implementation was inspected. */
public class mio_icif_stirling_generator extends mio_icif_HeatU_Block
        implements IEnergySource, IGeneratorBlock, DemandEnergySource {
    private final CustomEUEnergyStorage energy=new CustomEUEnergyStorage(200000,0,128,CableTier.MV);
    private long lastFrame=Long.MIN_VALUE,frameHu,generatedFrame,uncertainHu;
    private long generatedAt=Long.MIN_VALUE,lastOutput;
    private boolean acquiring;
    private CompoundTag legacyHold=new CompoundTag();
    private String failure="";
    public mio_icif_stirling_generator(BlockPos pos,BlockState state){
        super(mio_icif_block_entities.STIRLING_GENERATOR_ENTITY_TYPE.get(),pos,state,20000,1000,0,20,1000,0);
        energy.scexSetNetworkControlled(IndependentSiEnergy.controls(state));energy.setAsPowerSource(128);
    }
    private void dirty(){setChanged();if(level!=null && !level.isClientSide)ContainerToTank.markUnsaved(this);}
    private boolean live(){
        if(!(level instanceof ServerLevel server) || !server.getServer().isSameThread() || isRemoved())return false;
        var chunk=server.getChunkSource().getChunkNow(worldPosition.getX()>>4,worldPosition.getZ()>>4);
        return chunk!=null && chunk.getBlockEntity(worldPosition,LevelChunk.EntityCreationType.CHECK)==this;
    }
    private void frame(){long now=level==null?0:level.getGameTime();if(lastFrame!=now){lastFrame=now;frameHu=0;generatedFrame=0;}}
    private Direction input(){return getBlockState().getValue(mio_icif_block_stirling_generator.FACING).getOpposite();}
    private IMioIcifCapabilities.IHeatStorage supplier(){
        if(!(level instanceof ServerLevel server))return null;
        var side=input();var at=worldPosition.relative(side);
        if(server.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4)==null)return null;
        return server.getCapability(IMioIcifCapabilities.HEAT_STORAGE_BLOCK,at,side.getOpposite());
    }
    private static EnergyAmount eu(long hu){return new EnergyAmount(hu/2,(hu%2)*(EnergyAmount.UNITS/2));}
    private long availableHeat(){
        if(!live() || !energy.scexNetworkControlled() || acquiring || uncertainHu!=0 || !legacyHold.isEmpty())return 0;
        frame();long limit=Math.max(0,1000-frameHu);long owned=Math.min(limit,heatStorage.getHeatStored());
        if(owned==limit)return owned;
        try{
            var source=supplier();if(source==null || source==this || !source.canExtractHeat())return owned;
            long reply=source.extractHeat(limit-owned,true);
            return reply>=0 && reply<=limit-owned ? owned+reply : owned;
        }catch(RuntimeException unavailable){return owned;}
    }
    @Override public CustomEUEnergyStorage ownedEnergy(){return energy;}
    @Override public int outputFaces(){return 63 ^ (1 << input().get3DDataValue());}
    @Override public EnergyAmount potentialEnergy(){
        return energy.scexExactAmount().add(eu(availableHeat()).min(energy.scexExactAmount().roomBelow(energy.getCapacity())));
    }
    @Override public void prepareEnergy(EnergyAmount requested){
        if(!live() || acquiring || uncertainHu!=0 || !legacyHold.isEmpty() || !energy.scexNetworkControlled())return;
        var balance=energy.scexExactAmount();if(requested.compareTo(balance)<=0)return;
        frame();var need=requested.subtract(balance).min(balance.roomBelow(energy.getCapacity()));
        // At most 1000 integer HU per frame. A sub-half-EU route can leave a paid fractional remainder.
        long count=need.whole()>=500?1000:need.whole()*2+(need.fraction()==0?0:need.fraction()<=EnergyAmount.UNITS/2?1:2);
        count=Math.min(count,Math.max(0,1000-frameHu));
        long room=balance.roomBelow(energy.getCapacity()).whole();
        if(room<500)count=Math.min(count,room*2+(balance.roomBelow(energy.getCapacity()).fraction()>=EnergyAmount.UNITS/2?1:0));
        if(count==0)return;
        long owned=Math.min(count,heatStorage.getHeatStored());
        if(owned>0){heatStorage.consumeHeatInternal(owned,false);credit(owned);count-=owned;}
        if(count==0)return;
        acquiring=true;
        try{
            var source=supplier();if(source==null || source==this || !source.canExtractHeat())return;
            long proposed=source.extractHeat(count,true);if(proposed<=0 || proposed>count)return;
            uncertainHu=proposed;dirty();
            long actual=source.extractHeat(proposed,false);
            if(actual<0 || actual>proposed){failure="Invalid external HU extraction receipt";dirty();return;}
            uncertainHu=0;credit(actual);failure="";dirty();
        }catch(RuntimeException problem){if(uncertainHu>0){failure=problem.getClass().getName();dirty();}}
        finally{acquiring=false;}
    }
    private void credit(long hu){
        var value=eu(hu);var got=energy.scexGenerateEnergy(value,false);
        if(!got.equals(value))throw new IllegalStateException("Owned HU exceeds reserved EU capacity");
        frameHu+=hu;generatedFrame+=hu;if(hu>0){generatedAt=level.getGameTime();lastOutput=generatedFrame/2;}dirty();
    }
    public static void tick(Level level,BlockPos pos,BlockState state,mio_icif_stirling_generator m){
        if(!m.live())return;m.frame();
        var actual=m.getBlockState();boolean active=m.isWorking();
        if(actual.getValue(mio_icif_block_stirling_generator.ACTIVE)!=active)
            level.setBlock(pos,actual.setValue(mio_icif_block_stirling_generator.ACTIVE,active),3);
    }
    @Override public void setLevel(Level level){super.setLevel(level);energy.setBlockContext(level,worldPosition);}
    @Override public void onLoad(){super.onLoad();if(energy.scexNetworkControlled())IndependentSiEnergy.changed(this);}
    @Override public void setRemoved(){super.setRemoved();IndependentSiEnergy.changed(this);}
    @Override public void clearRemoved(){super.clearRemoved();if(energy.scexNetworkControlled())IndependentSiEnergy.changed(this);}
    public CustomEUEnergyStorage getEnergyStorage(){return energy;}
    public IEUEnergyStorage getEnergyStorageCapability(Direction side){return side==input()?null:energy;}
    // The actual supplier is pulled only for an electrical delivery. Passive pushes cannot drain a heater at idle.
    @Override public long receiveHeat(long amount,boolean simulate){return 0;}
    @Override public boolean canReceiveHeat(){return false;}
    @Override public long extractHeat(long amount,boolean simulate){return 0;}
    @Override public boolean canExtractHeat(){return false;}
    public IMioIcifCapabilities.IHeatStorage getHeatStorageCapability(Direction side){return side==null?this:side==input()?this:null;}
    public long getHeatBuffer(){return heatStorage.getHeatStored();}
    public long getEnergyStored(){return energy.getAmount();}
    public long getEnergyCapacity(){return energy.getCapacity();}
    public long getEnergyOutput(){return isWorking()?lastOutput:0;}
    public boolean isWorking(){return level!=null && generatedAt!=Long.MIN_VALUE && level.getGameTime()-generatedAt<=1;}
    public long getUncertainHeat(){return uncertainHu;}
    public boolean hasLegacyHold(){return !legacyHold.isEmpty();}
    public long extractPowerForConsumer(long request,boolean simulate){return live()?energy.extract(request,simulate):0;}
    // ABI retained; all actual grid traffic belongs to IndependentSiEnergy.
    @Override public double getOfferedEnergy(){return 0;}
    @Override public void drawEnergy(double amount){}
    @Override public int getSourceTier(){return CableTier.MV.getTier();}
    @Override public boolean emitsEnergyTo(IEnergyAcceptor acceptor,Direction side){return false;}
    @Override public CableTier getCableTier(){return CableTier.MV;}
    @Override public boolean isBurning(){return isWorking();}
    @Override public int getBurnTime(){return 0;}
    @Override public int getMaxBurnTime(){return 0;}
    @Override public long getPowerOutput(){return isWorking()?128:0;}
    @Override public ItemStack getFuelSlotItem(){return ItemStack.EMPTY;}
    @Override public ItemStack getChargeSlotItem(){return ItemStack.EMPTY;}
    @Override public int getDefaultBurnTime(){return 0;}
    @Override public void setBurnTime(int ticks){}
    @Override public Component getDisplayName(){return Component.translatable("block.mio_icif.generator.block_stirling_generator");}
    @Override public AbstractContainerMenu createMenu(int id,Inventory inventory,Player player){
        return new StirlingGeneratorMenu(id,inventory,this,null,new SimpleContainerData(6));
    }
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider provider){
        super.saveAdditional(tag,provider);tag.putLong("Energy",energy.getAmount());tag.putLong("scex_energy_fraction",energy.scexSavedFraction());
        var saved=new CompoundTag();saved.putInt("version",1);saved.putLong("uncertain",uncertainHu);saved.putString("failure",failure);
        saved.put("legacy_hold",legacyHold.copy());tag.put("scex_stirling",saved);
    }
    @Override public void loadAdditional(CompoundTag tag,HolderLookup.Provider provider){
        super.loadAdditional(tag,provider);energy.setEnergy(Math.max(0,tag.getLong("Energy")));energy.scexLoadFraction(tag.getLong("scex_energy_fraction"));
        legacyHold=new CompoundTag();uncertainHu=0;failure="";
        if(tag.contains("scex_stirling",Tag.TAG_COMPOUND)){
            var saved=tag.getCompound("scex_stirling");
            if(saved.getInt("version")==1){legacyHold=saved.getCompound("legacy_hold").copy();uncertainHu=Math.max(0,saved.getLong("uncertain"));failure=saved.getString("failure");}
            else legacyHold.put("unknown_stirling_version",saved.copy());
        }else if(tag.contains("HeatBuffer") && tag.getLong("HeatBuffer")!=0)legacyHold.put("HeatBuffer",tag.get("HeatBuffer").copy());
        acquiring=false;lastFrame=Long.MIN_VALUE;frameHu=0;generatedFrame=0;
    }
}
