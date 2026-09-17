// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.singularity_iteration.mio_icif.Blocks.entity.generator.mio_icif_stirling_generator;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;

/** SI public-interface observation only. Does not assert reference IC2 conversion values. */
public final class StirlingCalibrationProbe {
    private static final long[] INPUTS={0,1,2,3,9,10,99,100};
    private final List<Map<String,Object>> observations=new ArrayList<>();
    private int assertions;
    public StirlingCalibrationProbe() { }
    private void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private static BlockPos at(Direction face){return new BlockPos(256+4*face.get3DDataValue(),84,96);}
    private mio_icif_stirling_generator machine(ServerLevel world,Direction face) {
        var pos=at(face);var chunk=world.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
        check(chunk!=null,"Preloaded calibration chunk");
        var tile=chunk.getBlockEntity(pos,LevelChunk.EntityCreationType.CHECK);
        check(tile instanceof mio_icif_stirling_generator && !tile.isRemoved(),"Actual SI Stirling placement");
        return (mio_icif_stirling_generator)tile;
    }
    private Map<String,Object> sample(ServerLevel world,Direction face,int tick) {
        var tile=machine(world,face);var heat=tile.getHeatStorage();var energy=tile.getEnergyStorage();
        var row=new LinkedHashMap<String,Object>();
        row.put("tick",tick);row.put("facing",face.toString());row.put("position",at(face).toShortString());
        row.put("energy",energy.getAmount());row.put("energy_capacity",energy.getCapacity());
        row.put("heat",heat.getHeatStored());row.put("heat_buffer",tile.getHeatBuffer());
        row.put("heat_capacity",heat.getMaxHeatStored());row.put("heat_receive_limit",heat.getMaxReceive());
        row.put("heat_extract_limit",heat.getMaxExtract());row.put("heat_loss",heat.getHeatLossPerTick());
        row.put("heat_temperature",heat.getTemperature());row.put("output",tile.getEnergyOutput());
        row.put("working",tile.isWorking());row.put("power_output",tile.getPowerOutput());
        row.put("nbt",tile.saveWithFullMetadata(world.registryAccess()).toString());
        return row;
    }
    public Map<String,Object> inspect(ServerLevel world,int tick) throws Exception {
        if(tick==20) {
            var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:generator/block_stirling_generator"));
            check(block!=Blocks.AIR,"Exact registered Stirling block");
            for(var face:Direction.values()) {
                var p=at(face);var chunk=world.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);
                check(chunk!=null && chunk.getBlockState(p).isAir(),"Dedicated empty calibration position");
                var state=block.defaultBlockState();check(state.hasProperty(BlockStateProperties.FACING),"Six-way orientation");
                check(world.setBlock(p,state.setValue(BlockStateProperties.FACING,face),3),"Normal registered placement");
            }
        }
        if(tick==39)for(var face:Direction.values()) {
            var tile=machine(world,face);var sides=new ArrayList<Map<String,Object>>();
            for(var side:Direction.values()) {
                var heat=tile.getHeatStorageCapability(side);var energy=tile.getEnergyStorageCapability(side);
                sides.add(Map.of("side",side.toString(),"heat_present",heat!=null,
                        "heat_receives",heat!=null && heat.canReceiveHeat(),"heat_extracts",heat!=null && heat.canExtractHeat(),
                        "eu_present",energy!=null));
            }
            observations.add(Map.of("tick",tick,"facing",face.toString(),"sides",sides,
                    "code_source",tile.getClass().getProtectionDomain().getCodeSource().getLocation().toString()));
        }
        if(tick>=40 && tick<=110 && (tick-40)%10==0)for(var face:Direction.values()) {
            var tile=machine(world,face);long requested=INPUTS[(tick-40)/10];
            tile.getEnergyStorage().setEnergy(0);tile.getHeatStorage().setHeat(requested);tile.setChanged();
            observations.add(Map.of("tick",tick,"facing",face.toString(),"seed_hu",requested,"phase","public-buffer-input"));
        }
        if(tick==120 || tick==140)for(var face:Direction.values()) {
            var tile=machine(world,face);long capacity=tile.getEnergyStorage().getCapacity();
            check(capacity>0,"Observed positive energy capacity");
            tile.getEnergyStorage().setEnergy(tick==120?capacity:capacity-1);
            tile.getHeatStorage().setHeat(tile.getHeatStorage().getMaxHeatStored());tile.setChanged();
            observations.add(Map.of("tick",tick,"facing",face.toString(),"phase",tick==120?"full-output":"one-eu-room"));
        }
        if(tick>=39 && tick<=160)for(var face:Direction.values())observations.add(sample(world,face,tick));
        if(tick==160)for(var face:Direction.values()) {
            var tile=machine(world,face);var saved=tile.saveWithFullMetadata(world.registryAccess());
            var loaded=BlockEntity.loadStatic(tile.getBlockPos(),tile.getBlockState(),saved,world.registryAccess());
            check(loaded instanceof mio_icif_stirling_generator && loaded!=tile && loaded.getLevel()==null,"Saved factory creates unbound copy");
            observations.add(Map.of("tick",tick,"facing",face.toString(),"saved_factory_original",saved.toString(),
                    "saved_factory_copy",loaded.saveWithFullMetadata(world.registryAccess()).toString()));
        }
        if(tick==161)for(var face:Direction.values()) {
            var tile=machine(world,face);
            for(var side:Direction.values()) {
                tile.getEnergyStorage().setEnergy(0);tile.getHeatStorage().setHeat(0);
                var before=tile.saveWithFullMetadata(world.registryAccess());
                var capability=tile.getHeatStorageCapability(side);
                long accepted=capability==null ? 0 : capability.receiveHeat(1,false);
                check(accepted>=0 && accepted<=1,"Public heat capability bounded receipt");
                observations.add(Map.of("tick",tick,"facing",face.toString(),"input_side",side.toString(),
                        "capability_present",capability!=null,"requested_hu",1,"accepted_hu",accepted,
                        "before_nbt",before.toString(),"after_nbt",tile.saveWithFullMetadata(world.registryAccess()).toString()));
            }
            tile.getEnergyStorage().setEnergy(0);tile.getHeatStorage().setHeat(0);tile.setChanged();
        }
        if(tick!=162)return null;
        var result=Map.<String,Object>of("observation_completed",true,"assertions",assertions,"observations",observations,
                "reference_parity","NOT_ASSESSED","scope","Fixed SI binary public surface and buffer trajectory only; no inferred ratio, external HU supply, EU network transfer or cold restart acceptance.");
        Files.writeString(Path.of("stirling-calibration-result.json"),new Gson().toJson(result));return result;
    }
}
