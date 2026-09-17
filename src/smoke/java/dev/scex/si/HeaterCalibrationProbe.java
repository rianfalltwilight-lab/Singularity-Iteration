// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.singularity_iteration.mio_icif.Blocks.entity.HUEntity.HuGenerator.mio_icif_heat_generator_elc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Fixed SI public ABI observation. Does not inspect quarantined implementation or assert parity. */
public final class HeaterCalibrationProbe {
    private static final int[] COILS={0,1,2,5,10};
    private final List<Map<String,Object>> observations=new ArrayList<>();
    private int checks;
    private void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private BlockPos pos(int i){return new BlockPos(4000+i*4,80,100);}
    private mio_icif_heat_generator_elc machine(ServerLevel world,int i){
        var tile=world.getBlockEntity(pos(i));check(tile instanceof mio_icif_heat_generator_elc,"Registered electric heater "+i);
        return (mio_icif_heat_generator_elc)tile;
    }
    private Map<String,Object> sample(ServerLevel world,int i,int tick){
        var m=machine(world,i);var row=new LinkedHashMap<String,Object>();
        row.put("index",i);row.put("tick",tick);row.put("requested_coils",COILS[i/6]);row.put("facing",Direction.from3DDataValue(i%6).toString());
        row.put("eu",m.getEnergyStorageInternal().getAmount());row.put("eu_capacity",m.getEnergyStorageInternal().getCapacity());
        row.put("actual_coils",m.getCoilCount());row.put("hu",m.getHeatStored());row.put("hu_capacity",m.getMaxHeatStored());
        row.put("hu_receive_limit",m.getMaxReceive());row.put("hu_extract_limit",m.getMaxExtract());row.put("hu_loss",m.getHeatLossPerTick());
        row.put("generation",m.getHeatGeneration());row.put("generation_rate",m.getHeatGenerationRate());row.put("output",m.getHeatOutput());
        row.put("efficiency",m.getEfficiency());row.put("working",m.isGenerating());
        row.put("nbt",m.saveWithFullMetadata(world.registryAccess()).toString());return row;
    }
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception{
        if(tick==20){
            var id=ResourceLocation.parse("mio_icif:hugenerator/block_heat_generator_elc");var block=BuiltInRegistries.BLOCK.get(id);
            check(block!=Blocks.AIR,"Exact registered electric heater block");
            var coilId=ResourceLocation.parse("mio_icif:resource/item_coil");check(BuiltInRegistries.ITEM.containsKey(coilId),"Observed coil identity");
            for(int i=0;i<30;i++){
                check(world.getBlockState(pos(i)).isAir(),"Empty isolated heater position");
                var state=block.defaultBlockState();check(state.hasProperty(BlockStateProperties.FACING),"Six-way heater property");
                check(world.setBlockAndUpdate(pos(i),state.setValue(BlockStateProperties.FACING,Direction.from3DDataValue(i%6))),"Registered block placement");
                var m=machine(world,i);check(m.getItemHandler().getSlots()==11,"Published11-slot ABI");
                for(int n=0;n<COILS[i/6];n++)check(m.getItemHandler().insertItem(1+n,new ItemStack(BuiltInRegistries.ITEM.get(coilId)),false).isEmpty(),"Normal slot accepts exact coil");
                check(m.getCoilCount()==COILS[i/6],"Actual installed coil count");
                m.getEnergyStorageInternal().setEnergy(0);m.setHeat(0);m.setChanged();
                var sides=new ArrayList<Map<String,Object>>();
                for(var side:Direction.values()){
                    var heat=m.getHeatStorageCapability(side);sides.add(Map.of("side",side.toString(),"present",heat!=null,
                       "extracts",heat!=null&&heat.canExtractHeat(),"receives",heat!=null&&heat.canReceiveHeat()));
                }
                observations.add(Map.of("tick",tick,"index",i,"sides",sides,"code_source",m.getClass().getProtectionDomain().getCodeSource().getLocation().toString()));
            }
        }
        if(tick==40)for(int i=0;i<30;i++){
            var m=machine(world,i);m.getEnergyStorageInternal().setEnergy(10000);m.setChanged();
            observations.add(Map.of("tick",tick,"index",i,"funded_eu",10000,"phase","normal-coil-idle"));
        }
        if(tick>=39&&tick<=150)for(int i=0;i<30;i++)observations.add(sample(world,i,tick));
        if(tick>=90&&tick<110)for(int i=0;i<30;i++){
            var m=machine(world,i);long before=m.getHeatStored();long extracted=m.getHeatStorage().extractHeat(7,false);
            check(extracted>=0&&extracted<=7,"Bounded public extraction");
            observations.add(Map.of("tick",tick,"index",i,"phase","public-load","requested_hu",7,"extracted_hu",extracted,"before_hu",before,"after_hu",m.getHeatStored()));
        }
        if(tick==130)for(int i=0;i<30;i++){
            var m=machine(world,i);var saved=m.saveWithFullMetadata(world.registryAccess());
            var restored=BlockEntity.loadStatic(m.getBlockPos(),m.getBlockState(),saved,world.registryAccess());
            check(restored instanceof mio_icif_heat_generator_elc&&restored!=m&&restored.getLevel()==null,"Ordinary factory reconstruction");
            observations.add(Map.of("tick",tick,"index",i,"saved_factory_original",saved.toString(),"saved_factory_copy",restored.saveWithFullMetadata(world.registryAccess()).toString()));
        }
        if(tick==151){
            var result=Map.<String,Object>of("observation_completed",true,"cases",30,"checks",checks,"observations",observations,
                "reference_parity","NOT_ASSESSED","scope","Registered six-face public SI heater surface and real ticks with0/1/2/5/10 ordinary coils; no implementation read, independent replacement or full parity claim.");
            Files.writeString(Path.of("heater-calibration-result.json"),new Gson().toJson(result));return result;
        }
        return null;
    }
}
