// SPDX-License-Identifier: Apache-2.0
package com.singularity_iteration.mio_icif.Blocks.entity.generator;

import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Generator;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_block_entities;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.SlotLayout;
import com.singularity_iteration.mio_icif.Blocks.mio_icif_entity_block;
import com.singularity_iteration.mio_icif.Blocks.generator.mio_icif_Block_kinetic_generator;
import com.singularity_iteration.mio_icif.Menu.Generator.KineticGeneratorMenu;
import com.singularity_iteration.mio_icif.api.capability.IMioIcifCapabilities;
import com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage;
import com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier;
import com.singularity_iteration.mio_icif.energy.grid.IEnergyAcceptor;
import com.singularity_iteration.mio_icif.energy.kinetic.IKineticStorage;
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
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Independently authored from normal R110/R111 reference observations and the SI public ABI. */
public class mio_icif_kinetic_generator extends mio_icif_Energy_Generator implements DemandEnergySource {
    private long kinetic,lastFrame=Long.MIN_VALUE,frameKu,frameAccepted,uncertainKu,generatedAt=Long.MIN_VALUE,generatedKu,lastOutputKu;
    private boolean acquiring;
    private String failure="";
    private CompoundTag hold=new CompoundTag();
    private final IKineticStorage aggregate=new InputPort(null);
    private final IKineticStorage[] ports=new IKineticStorage[6];
    public mio_icif_kinetic_generator(BlockPos pos,BlockState state){this(pos,state,mio_icif_block_entities.KINETIC_GENERATOR_ENTITY_TYPE.get());}
    public mio_icif_kinetic_generator(BlockPos pos,BlockState state,BlockEntityType<?> type){
        // Retain observed SI EU/KU capacities and two inherited inventory slots. The measured electrical offer reaches 250 EU.
        super(pos,state,type,SlotLayout.builder().extra(2).build(),250,100000,0,250,CableTier.MV);
        for(var side:Direction.values())ports[side.ordinal()]=new InputPort(side);
    }
    private boolean live(){
        if(!(level instanceof ServerLevel server)||!server.getServer().isSameThread()||isRemoved())return false;
        var chunk=server.getChunkSource().getChunkNow(worldPosition.getX()>>4,worldPosition.getZ()>>4);
        return chunk!=null&&chunk.getBlockEntity(worldPosition,LevelChunk.EntityCreationType.CHECK)==this;
    }
    private void dirty(){setChanged();if(level!=null&&!level.isClientSide)ContainerToTank.markUnsaved(this);}
    private Direction input(){return getBlockState().getValue(mio_icif_entity_block.FACING);}
    private void frame(){long now=level==null?0:level.getGameTime();if(lastFrame!=now){lastFrame=now;frameKu=0;frameAccepted=0;generatedKu=0;}}
    private static EnergyAmount eu(long ku){return new EnergyAmount(ku/4,(ku%4)*(EnergyAmount.UNITS/4));}
    private IMioIcifCapabilities.IKineticStorage supplier(){
        if(!(level instanceof ServerLevel server))return null;
        var at=worldPosition.relative(input());
        if(server.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4)==null)return null;
        return server.getCapability(IMioIcifCapabilities.KINETIC_STORAGE_BLOCK,at,input().getOpposite());
    }
    private long available(){
        if(!live()||!energyStorage.scexNetworkControlled()||acquiring||uncertainKu!=0||!hold.isEmpty())return 0;
        frame();long limit=Math.max(0,1000-frameKu),owned=Math.min(limit,kinetic);
        if(owned==limit)return owned;
        try{var src=supplier();if(src==null||!src.canExtractKinetic())return owned;
            long reply=src.extractKinetic(limit-owned,true);return reply>=0&&reply<=limit-owned?owned+reply:owned;
        }catch(RuntimeException unavailable){return owned;}
    }
    @Override public CustomEUEnergyStorage ownedEnergy(){return energyStorage;}
    @Override public int outputFaces(){return 63^(1<<input().ordinal());}
    @Override public EnergyAmount potentialEnergy(){var balance=energyStorage.scexExactAmount();return balance.add(eu(available()).min(balance.roomBelow(energyStorage.getCapacity())));}
    @Override public void prepareEnergy(EnergyAmount request){
        if(!live()||!energyStorage.scexNetworkControlled()||acquiring||uncertainKu!=0||!hold.isEmpty())return;
        var balance=energyStorage.scexExactAmount();if(request.compareTo(balance)<=0)return;frame();
        var room=balance.roomBelow(energyStorage.getCapacity());var need=request.subtract(balance).min(room);
        long count=need.whole()>=250?1000:need.whole()*4+(need.fraction()+EnergyAmount.UNITS/4-1)/(EnergyAmount.UNITS/4);
        count=Math.min(count,Math.max(0,1000-frameKu));
        if(room.whole()<250)count=Math.min(count,room.whole()*4+room.fraction()/(EnergyAmount.UNITS/4));
        if(count<=0)return;long owned=Math.min(count,kinetic);
        if(owned>0){kinetic-=owned;credit(owned);count-=owned;}if(count==0)return;
        acquiring=true;
        try{var src=supplier();if(src==null||!src.canExtractKinetic())return;long proposed=src.extractKinetic(count,true);
            if(proposed<=0||proposed>count)return;uncertainKu=proposed;dirty();
            long received=src.extractKinetic(proposed,false);
            if(received<0||received>proposed){failure="Invalid KU extraction receipt";dirty();return;}
            uncertainKu=0;credit(received);failure="";dirty();
        }catch(RuntimeException error){if(uncertainKu>0){failure=error.getClass().getName();dirty();}}
        finally{acquiring=false;}
    }
    private void credit(long ku){var amount=eu(ku);if(!energyStorage.scexGenerateEnergy(amount,false).equals(amount))throw new IllegalStateException("Reserved KU credit exceeds EU capacity");frameKu+=ku;generatedKu+=ku;if(ku>0){generatedAt=level.getGameTime();lastOutputKu=generatedKu;}dirty();}
    public static void tick(Level level,BlockPos pos,BlockState state,mio_icif_kinetic_generator m){
        if(!m.live())return;m.frame();var actual=m.getBlockState();boolean active=m.isBurning();
        if(actual.getValue(mio_icif_Block_kinetic_generator.ACTIVE)!=active)level.setBlock(pos,actual.setValue(mio_icif_Block_kinetic_generator.ACTIVE,active),3);
    }
    @Override public void onLoad(){super.onLoad();if(energyStorage.scexNetworkControlled())IndependentSiEnergy.changed(this);}
    @Override public void setRemoved(){super.setRemoved();IndependentSiEnergy.changed(this);}
    @Override public void clearRemoved(){super.clearRemoved();IndependentSiEnergy.changed(this);}
    public IKineticStorage getKineticStorage(){return aggregate;}
    public IKineticStorage getKineticStorageCapability(Direction side){return side==null?aggregate:side==input()?ports[side.ordinal()]:null;}
    @Override public CustomEUEnergyStorage getEnergyStorageCapability(Direction side){return side==input()?null:super.getEnergyStorageCapability(side);}
    @Override public int getFuelBurnTime(ItemStack stack){return 0;}
    @Override public boolean isBurning(){return level!=null&&generatedAt!=Long.MIN_VALUE&&level.getGameTime()-generatedAt<=1;}
    public long getLastEnergyOutput(){return (long)getLastProduction();}
    public double getLastProduction(){return isBurning()?lastOutputKu/4.0:0;}
    @Override public long getPowerOutput(){return isBurning()?250:0;}
    @Override public int getSourceTier(){return 2;}
    @Override public double getOfferedEnergy(){return 0;}
    @Override public void drawEnergy(double amount){}
    @Override public boolean emitsEnergyTo(IEnergyAcceptor acceptor,Direction side){return false;}
    public long getUncertainKinetic(){return uncertainKu;}
    public boolean hasLegacyHold(){return !hold.isEmpty();}
    @Override public Component getDisplayName(){return Component.translatable("block.mio_icif.generator.block_kinetic_generator");}
    @Override public AbstractContainerMenu createMenu(int id,Inventory inventory,Player player){return new KineticGeneratorMenu(id,inventory,this,getItemHandler(),new SimpleContainerData(6));}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider provider){
        super.saveAdditional(tag,provider);tag.putLong("KineticStored",kinetic);
        var state=new CompoundTag();state.putInt("version",1);state.putLong("uncertain",uncertainKu);state.putString("failure",failure);state.put("hold",hold.copy());tag.put("scex_kinetic_converter",state);
    }
    @Override public void loadAdditional(CompoundTag tag,HolderLookup.Provider provider){
        super.loadAdditional(tag,provider);hold=new CompoundTag();uncertainKu=0;failure="";
        kinetic=tag.getLong("KineticStored");if(kinetic<0||kinetic>10000){hold.put("KineticStored",tag.get("KineticStored").copy());kinetic=0;}
        if(tag.contains("scex_kinetic_converter",Tag.TAG_COMPOUND)){
            var state=tag.getCompound("scex_kinetic_converter");
            if(state.getInt("version")==1){hold.merge(state.getCompound("hold").copy());uncertainKu=Math.max(0,state.getLong("uncertain"));failure=state.getString("failure");}
            else hold.put("unknown_converter_version",state.copy());
        }
        acquiring=false;lastFrame=Long.MIN_VALUE;frameKu=0;frameAccepted=0;generatedAt=Long.MIN_VALUE;generatedKu=0;setAsPowerSource(250);
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider provider){return saveWithoutMetadata(provider);}
    @Override public void handleUpdateTag(CompoundTag tag,HolderLookup.Provider provider){loadAdditional(tag,provider);}
    private final class InputPort implements IKineticStorage {
        private final Direction side;InputPort(Direction side){this.side=side;}
        private boolean usable(){return live()&&(side==null||side==input())&&!acquiring&&uncertainKu==0&&hold.isEmpty();}
        @Override public long receiveKinetic(long amount,boolean simulate){
            if(!usable()||amount<=0)return 0;frame();long accepted=Math.min(amount,Math.min(Math.max(0,10000-kinetic),Math.max(0,1000-frameAccepted)));
            if(!simulate&&accepted>0){kinetic+=accepted;frameAccepted+=accepted;dirty();}return accepted;
        }
        @Override public long extractKinetic(long amount,boolean simulate){return 0;}
        @Override public long getKineticStored(){return kinetic;}
        @Override public long getMaxKineticStored(){return 10000;}
        @Override public boolean canExtractKinetic(){return false;}
        @Override public boolean canReceiveKinetic(){return usable()&&kinetic<10000;}
        @Override public int getRPM(){return 0;}
        @Override public boolean isOverspeed(){return false;}
        @Override public long getKineticLossPerTick(){return 0;}
        @Override public long getMaxReceive(){return usable()?1000:0;}
        @Override public long getMaxExtract(){return 0;}
    }
}
