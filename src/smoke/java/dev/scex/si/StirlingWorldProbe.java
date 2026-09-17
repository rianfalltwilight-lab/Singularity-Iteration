// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.entity.HUEntity.HuGenerator.mio_icif_heat_generator_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.generator.mio_icif_stirling_generator;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Container;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Actual registered heater -> demand-driven Stirling -> ordinary MFSU, on all six SI orientations. */
public final class StirlingWorldProbe {
    private final boolean restart;
    private int checks;
    private final List<String> groups=new ArrayList<>();
    public StirlingWorldProbe()throws Exception{
        restart=JsonParser.parseString(Files.readString(Path.of("stirling-world.json"))).getAsJsonObject().get("phase").getAsString().equals("restart");
    }
    private void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError("R107 Stirling: "+why);}
    private BlockPos pos(int i){return new BlockPos(5000+i*20,80,100);}
    private Direction side(int i){return Direction.from3DDataValue(i);}
    private mio_icif_stirling_generator machine(ServerLevel w,int i){var be=w.getBlockEntity(pos(i));check(be instanceof mio_icif_stirling_generator,"Actual Stirling factory");return (mio_icif_stirling_generator)be;}
    private mio_icif_heat_generator_elc heater(ServerLevel w,int i){return (mio_icif_heat_generator_elc)w.getBlockEntity(pos(i).relative(side(i).getOpposite()));}
    private mio_icif_Energy_Container sink(ServerLevel w,int i){return (mio_icif_Energy_Container)w.getBlockEntity(pos(i).relative(side(i)));}
    private void place(ServerLevel w,BlockPos pos,String id,Direction side){
        var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:"+id));
        check(w.setBlockAndUpdate(pos,block.defaultBlockState().setValue(BlockStateProperties.FACING,side)),"Normal registered placement "+id);
    }
    private List<Map<String,Object>> snapshot(ServerLevel w){
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<6;i++){var h=heater(w,i);var m=machine(w,i);var s=sink(w,i).getEnergyStorageInternal();
            rows.add(Map.of("index",i,"heater_eu",h.getEnergyStorageInternal().getAmount(),"heater_fraction",h.getEnergyStorageInternal().scexSavedFraction(),
                "hu",h.getHeatStored(),"stirling_eu",m.ownedEnergy().getAmount(),"stirling_fraction",m.ownedEnergy().scexSavedFraction(),
                "sink_eu",s.getAmount(),"sink_fraction",s.scexSavedFraction(),"uncertain",m.getUncertainHeat()));}
        return rows;
    }
    public Map<String,Object> inspect(ServerLevel w,int tick)throws Exception{
        if(restart){
            if(tick==20){
                var expected=JsonParser.parseString(Files.readString(Path.of("world/scex-stirling-checkpoint.json")));
                check(expected.equals(new Gson().toJsonTree(snapshot(w))),"Cold JVM exact idle balances");groups.add("cold-jvm-exact-balances");
                for(int i=0;i<6;i++){var s=sink(w,i).getEnergyStorageInternal();s.setEnergy(s.getCapacity()-1);}
            }
            if(tick==35){
                for(int i=0;i<6;i++){var h=heater(w,i);var s=sink(w,i).getEnergyStorageInternal();
                    check(s.getAmount()==s.getCapacity() && h.getHeatStored()==100 && h.getEnergyStorageInternal().getAmount()==398
                        && h.getEnergyStorageInternal().scexSavedFraction()==EnergyAmount.UNITS/2,"Restart 1 EU demand draws/refills exactly 2 HU");}
                groups.add("cold-jvm-demand-resumes-once");return finish(w);
            }return null;
        }
        if(tick==20)for(int i=0;i<6;i++){
            place(w,pos(i),"generator/block_stirling_generator",side(i));place(w,pos(i).relative(side(i).getOpposite()),"hugenerator/block_heat_generator_elc",side(i));
            var m=machine(w,i);check(m.ownedEnergy().scexNetworkControlled(),"Independent converter owner");heater(w,i).setHeat(100);
        }
        if(tick==60){
            for(int i=0;i<6;i++){check(heater(w,i).getHeatStored()==100 && machine(w,i).ownedEnergy().scexExactAmount().isZero(),"No electrical receiver does not consume source HU");
                place(w,pos(i).relative(side(i)),"wiring/block_mfsu",side(i));var s=sink(w,i).getEnergyStorageInternal();s.setEnergy(s.getCapacity());}
            groups.add("no-receiver-no-heat-draw");
        }
        if(tick==80){
            for(int i=0;i<6;i++){check(heater(w,i).getHeatStored()==100,"Full receiver does not consume HU");var s=sink(w,i).getEnergyStorageInternal();s.setEnergy(s.getCapacity()-1);}
            groups.add("full-receiver-no-heat-draw");
        }
        if(tick==90)for(int i=0;i<6;i++){
            var s=sink(w,i).getEnergyStorageInternal();check(s.getAmount()==s.getCapacity() && heater(w,i).getHeatStored()==98,"One EU room draws exactly two HU");
            heater(w,i).setHeat(1);s.setEnergy(s.getCapacity()-1);
        }
        if(tick==100)for(int i=0;i<6;i++){
            var s=sink(w,i).getEnergyStorageInternal();check(s.getAmount()==s.getCapacity()-1 && s.scexSavedFraction()==EnergyAmount.UNITS/2 && heater(w,i).getHeatStored()==0,"Odd HU delivers exact half EU through native grid");heater(w,i).setHeat(1);
        }
        if(tick==110){
            for(int i=0;i<6;i++){var s=sink(w,i).getEnergyStorageInternal();check(s.getAmount()==s.getCapacity() && s.scexSavedFraction()==0,"Two odd HU pulses accumulate once");heater(w,i).setHeat(100);s.setEnergy(0);}
            groups.add("limited-room-and-odd-hu-native-receipts");
        }
        if(tick==140){
            for(int i=0;i<6;i++){
                var m=machine(w,i);check(sink(w,i).getEnergyStorageInternal().getAmount()==50 && heater(w,i).getHeatStored()==0 && m.ownedEnergy().scexExactAmount().isZero(),"Actual one hundred HU yields fifty EU");
                var legacy=m.saveWithFullMetadata(w.registryAccess());legacy.remove("scex_stirling");legacy.putLong("Energy",137);legacy.putLong("HeatBuffer",31);legacy.putLong("heat",9);
                var copy=(mio_icif_stirling_generator)BlockEntity.loadStatic(pos(i),m.getBlockState(),legacy,w.registryAccess());
                check(copy!=null && copy.getEnergyStored()==137 && copy.getHeatStored()==9 && copy.hasLegacyHold(),"Ambiguous old buffer retained without reinterpretation");
                var saved=copy.saveWithFullMetadata(w.registryAccess());var again=(mio_icif_stirling_generator)BlockEntity.loadStatic(pos(i),m.getBlockState(),saved,w.registryAccess());
                check(again.hasLegacyHold() && again.getEnergyStored()==137,"Legacy hold survives new save");
                var h=heater(w,i);for(int j=1;j<=10;j++)h.getItemHandler().insertItem(j,new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:resource/item_coil"))),false);
                h.getEnergyStorageInternal().setEnergy(1000);h.getEnergyStorageInternal().scexLoadFraction(EnergyAmount.UNITS/2);sink(w,i).getEnergyStorageInternal().setEnergy(0);
            }
            groups.add("scalar-and-conservative-legacy-hold");
        }
        if(tick==180){
            for(int i=0;i<6;i++){var h=heater(w,i);var m=machine(w,i);var s=sink(w,i).getEnergyStorageInternal();
                check(h.getEnergyStorageInternal().getAmount()==0 && h.getEnergyStorageInternal().scexSavedFraction()==EnergyAmount.UNITS/2
                    && h.getHeatStored()==0 && m.ownedEnergy().scexExactAmount().isZero() && s.getAmount()==500,"Real powered chain converts 1000 EU to 500 EU with unpaid half EU retained");
                s.setEnergy(s.getCapacity());h.getEnergyStorageInternal().setEnergy(500);h.getEnergyStorageInternal().scexLoadFraction(EnergyAmount.UNITS/2);}
            groups.add("real-powered-heater-stirling-native-chain");
        }
        if(tick==210){
            for(int i=0;i<6;i++){var h=heater(w,i);check(h.getEnergyStorageInternal().getAmount()==400 && h.getHeatStored()==100,"Full chain idles with one paid heater buffer");}
            Files.writeString(Path.of("world/scex-stirling-checkpoint.json"),new Gson().toJson(snapshot(w)));groups.add("cold-checkpoint");
        }
        if(tick==220)return finish(w);return null;
    }
    private Map<String,Object> finish(ServerLevel w)throws Exception{
        var engine=dev.scex.si.energy.IndependentSiEnergy.current(w.getServer());check(engine!=null && engine.metrics().failure().isEmpty(),"Independent electrical engine healthy");
        var result=Map.<String,Object>of("passed",true,"phase",restart?"restart":"initial","cases",6,"checks",checks,"groups",groups,"machines",snapshot(w));
        Files.writeString(Path.of("stirling-world-result.json"),new Gson().toJson(result));return result;
    }
}
