// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.entity.HUEntity.HuGenerator.mio_icif_heat_generator_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_fermenter_elc;
import com.singularity_iteration.mio_icif.api.capability.IMioIcifCapabilities;
import dev.scex.energy.EnergyAmount;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Actual registered heater, capability faces, exact EU, real recipient and cold save, without test quotes. */
public final class HeaterWorldProbe {
    private static final int[] COILS={0,1,2,5,10};
    private final boolean restart;
    private final List<String> groups=new ArrayList<>();
    private final long[] pushEu=new long[6],pushHu=new long[6];
    private int checks;
    public HeaterWorldProbe()throws Exception {
        restart=JsonParser.parseString(Files.readString(Path.of("heater-world.json"))).getAsJsonObject().get("phase").getAsString().equals("restart");
    }
    private void check(boolean value,String why){checks++;if(!value)throw new AssertionError("R106 heater: "+why);}
    private BlockPos pos(int i){return new BlockPos(4600+i*6,80,100);}
    private Direction side(int i){return Direction.from3DDataValue(i%6);}
    private mio_icif_heat_generator_elc machine(ServerLevel world,int i){
        var value=world.getBlockEntity(pos(i));check(value instanceof mio_icif_heat_generator_elc,"Registered heater "+i);return (mio_icif_heat_generator_elc)value;
    }
    private ItemStack coil(){return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:resource/item_coil")));}
    private List<Map<String,Object>> snapshot(ServerLevel world){
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<30;i++){var m=machine(world,i);rows.add(Map.of("index",i,"eu",m.getEnergyStorageInternal().getAmount(),
            "fraction",m.getEnergyStorageInternal().scexSavedFraction(),"hu",m.getHeatStored(),"coils",m.getCoilCount(),"uncertain",m.getUncertainHeat()));}
        return rows;
    }
    private void compareCheckpoint(ServerLevel world)throws Exception{
        var expected=JsonParser.parseString(Files.readString(Path.of("world/scex-heater-checkpoint.json"))).getAsJsonArray();
        var actual=new Gson().toJsonTree(snapshot(world)).getAsJsonArray();check(expected.equals(actual),"New JVM restores all exact paid EU fractions/HU/coil balances without new debit");
    }
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception{
        if(restart){
            if(tick==20){compareCheckpoint(world);groups.add("cold-jvm-exact-paid-balances");
                for(int i=6;i<30;i++){var m=machine(world,i);check(m.extractHeat(1,false)==1,"One HU after cold load");}}
            if(tick==21){
                var before=JsonParser.parseString(Files.readString(Path.of("world/scex-heater-checkpoint.json"))).getAsJsonArray();
                for(int i=6;i<30;i++){var m=machine(world,i);var row=before.get(i).getAsJsonObject();
                    check(m.getHeatStored()==row.get("hu").getAsLong() && m.getEnergyStorageInternal().getAmount()==row.get("eu").getAsLong()-1
                        && m.getEnergyStorageInternal().scexSavedFraction()==EnergyAmount.UNITS/2,"One paid HU refill after restart");}
                groups.add("cold-jvm-resumed-conversion-once");}
            if(tick==40)return finish(world);
            return null;
        }
        if(tick==20){
            var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:hugenerator/block_heat_generator_elc"));
            check(block!=Blocks.AIR,"Registered heater block");
            for(int i=0;i<30;i++){
                check(world.setBlockAndUpdate(pos(i),block.defaultBlockState().setValue(BlockStateProperties.FACING,side(i))),"Place heater");
                var m=machine(world,i);check(m.getItemHandler().getSlots()==11,"Retained 11-slot ABI");
                check(m.getEnergyStorageInternal().scexNetworkControlled(),"Heater belongs to the independent electrical owner");
                var fe=world.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,pos(i),Direction.UP);
                check(fe!=null && fe.canReceive() && !fe.canExtract(),"Actual receive-only FE capability");
                check(fe.receiveEnergy(4,true)==4 && m.getEnergyStorageInternal().getAmount()==0,"Simulated FE input leaves shared EU unchanged");
                check(fe.receiveEnergy(4,false)==4 && m.getEnergyStorageInternal().getAmount()==1,"Actual FE input credits the one owned EU balance");
                check(m.getItemHandler().insertItem(1,new ItemStack(Items.STONE),false).getCount()==1,"Coil slot rejects noncoil");
                for(int j=0;j<COILS[i/6];j++)check(m.getItemHandler().insertItem(j+1,coil(),false).isEmpty(),"Normal coil insertion");
                check(m.getCoilCount()==COILS[i/6],"Actual coils");
                m.getEnergyStorageInternal().setEnergy(9000);m.getEnergyStorageInternal().scexLoadFraction(EnergyAmount.UNITS/2);
            }
        }
        if(tick==60){
            for(int i=0;i<30;i++){var m=machine(world,i);int buffer=10*COILS[i/6];
                check(m.getHeatStored()==buffer && m.getEnergyStorageInternal().getAmount()==9000-buffer,"Forty idle ticks spend only the measured coil buffer");
                check(m.getEnergyStorageInternal().scexSavedFraction()==EnergyAmount.UNITS/2,"Idle conversion preserves exact fractional EU");
                for(var side:Direction.values()){
                    var cap=world.getCapability(IMioIcifCapabilities.HEAT_STORAGE_BLOCK,pos(i),side);
                    check((cap!=null)==(side==side(i)),"Actual capability registration has one heat output face");
                }
            }
            groups.add("reference-idle-coil-buffers-and-fractional-eu");
        }
        if(tick>=60 && tick<80)for(int i=0;i<30;i++){
            var m=machine(world,i);var cap=world.getCapability(IMioIcifCapabilities.HEAT_STORAGE_BLOCK,pos(i),side(i));
            long before=m.getHeatStored();long expected=i<6?0:7;
            check(cap.extractHeat(7,true)==expected && m.getHeatStored()==before,"Simulate does not debit heat");
            check(cap.extractHeat(7,false)==expected,"Normal directional output receipt");
        }
        if(tick==80){
            for(int i=0;i<30;i++){
                var m=machine(world,i);int rate=10*COILS[i/6];
                check(m.getHeatStored()==rate && m.getEnergyStorageInternal().getAmount()==9000-rate-(i<6?0:140),"Twenty partial loads debit exactly delivered HU");
                var saved=m.saveWithFullMetadata(world.registryAccess());var copy=(mio_icif_heat_generator_elc)BlockEntity.loadStatic(pos(i),m.getBlockState(),saved,world.registryAccess());
                check(copy!=null && copy.getHeatStored()==m.getHeatStored() && copy.getEnergyStorageInternal().scexExactAmount().equals(m.getEnergyStorageInternal().scexExactAmount()),"Factory copy preserves actual heat and fractional EU");
                var cached=world.getCapability(IMioIcifCapabilities.HEAT_STORAGE_BLOCK,pos(i),side(i));var state=m.getBlockState();
                world.setBlockAndUpdate(pos(i),state.setValue(BlockStateProperties.FACING,side(i).getOpposite()));
                check(world.getBlockEntity(pos(i))==m && cached.extractHeat(1,false)==0 && !cached.canExtractHeat(),"Cached face revoked after rotation");
                check(world.getCapability(IMioIcifCapabilities.HEAT_STORAGE_BLOCK,pos(i),side(i))==null,"No fallback capability bypass after rotation");
                world.setBlockAndUpdate(pos(i),state);
                m.setHeat(0);m.getEnergyStorageInternal().setEnergy(2);m.getEnergyStorageInternal().scexLoadFraction(EnergyAmount.UNITS/2);
            }
            groups.add("partial-load-simulation-save-and-live-facing");
        }
        if(tick==82){
            for(int i=0;i<30;i++){var m=machine(world,i);
                check(m.getHeatStored()==(i<6?0:2) && m.getEnergyStorageInternal().getAmount()==(i<6?2:0)
                    && m.getEnergyStorageInternal().scexSavedFraction()==EnergyAmount.UNITS/2,"Partial whole EU never consumes unpaid fraction");
                m.setCapacity(0);m.getEnergyStorageInternal().setEnergy(90);}
        }
        if(tick==90){
            for(int i=0;i<30;i++){var m=machine(world,i);check(m.getEnergyStorageInternal().getAmount()==90 && m.getHeatStored()==(i<6?0:2),"Zero capacity preserves existing paid heat without new debit");
                m.setCapacity(100);m.setHeat(1000);var saved=m.saveWithFullMetadata(world.registryAccess());
                var copy=(mio_icif_heat_generator_elc)BlockEntity.loadStatic(pos(i),m.getBlockState(),saved,world.registryAccess());
                check(copy.getHeatStored()==1000,"Over-capacity saved owned heat retained without minting EU");
                m.setHeat(0);m.getEnergyStorageInternal().setEnergy(9000);m.getEnergyStorageInternal().scexLoadFraction(EnergyAmount.UNITS/2);}
            groups.add("partial-eu-and-conservative-owned-save");
        }
        if(tick==110){
            var fermenter=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:producer/block_fermenter_elc"));
            check(fermenter!=Blocks.AIR,"Registered real heat recipient");
            for(int j=0;j<6;j++){int i=24+j;var m=machine(world,i);pushEu[j]=m.getEnergyStorageInternal().getAmount();pushHu[j]=m.getHeatStored();
                var p=pos(i).relative(side(i));check(world.setBlockAndUpdate(p,fermenter.defaultBlockState().setValue(BlockStateProperties.FACING,side(i).getOpposite())),"Place real front recipient");
                check(world.getBlockEntity(p) instanceof mio_icif_fermenter_elc,"Actual recipient factory");}
        }
        if(tick==114){
            for(int j=0;j<6;j++){int i=24+j;var m=machine(world,i);var p=pos(i).relative(side(i));var f=(mio_icif_fermenter_elc)world.getBlockEntity(p);
                long received=f.getHeatStored();check(received>=100 && received<=500,"Real recipient received bounded directional heat");
                check(pushEu[j]-m.getEnergyStorageInternal().getAmount()+pushHu[j]==m.getHeatStored()+received,"Actual EU/HU conservation with front recipient");
                check(m.getUncertainHeat()==0,"No uncertain transfer in ordinary receipt");world.removeBlock(p,false);}
            groups.add("six-real-front-recipients-conserve-hu");
        }
        if(tick==140){Files.writeString(Path.of("world/scex-heater-checkpoint.json"),new Gson().toJson(snapshot(world)));groups.add("cold-checkpoint-written");}
        if(tick==150){compareCheckpoint(world);return finish(world);}
        return null;
    }
    private Map<String,Object> finish(ServerLevel world)throws Exception {
        var result=Map.<String,Object>of("passed",true,"phase",restart?"restart":"initial","cases",30,"checks",checks,
            "groups",groups,"machines",snapshot(world),"scope","Scoped reference scalar and SI adapter lifecycle; no full converter, third-party exception, GUI or multiplayer parity claim.");
        Files.writeString(Path.of("heater-world-result.json"),new Gson().toJson(result));return result;
    }
}
