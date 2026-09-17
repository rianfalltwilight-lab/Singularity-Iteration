// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;
import com.google.gson.Gson;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_blast_furnace;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_blast_furnace;
import com.singularity_iteration.mio_icif.Blocks.Environment.fluid.mio_icif_fluids;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.minecraft.server.level.ServerLevel;
/** Existing SI public compatibility observations, with the implementation body kept opaque. */
public final class BlastStateProbe {
 public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception {
  if(tick!=20)return null;
  var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:producer/block_blast_furnace"));
  var rows=new ArrayList<Map<String,Object>>();int assertions=0;
  for(var face:Direction.values()){
   var pos=new BlockPos(2+face.ordinal()*2,80,2);
   world.setBlock(pos,block.defaultBlockState().setValue(mio_icif_block_blast_furnace.FACING,face),3);
   if(!(world.getBlockEntity(pos) instanceof mio_icif_blast_furnace m))throw new AssertionError("Actual blast factory");assertions++;
   var sides=new LinkedHashMap<String,Boolean>();
   for(var side:Direction.values())sides.put(side.getName(),m.getHeatStorageCapability(side)!=null);
   var row=new LinkedHashMap<String,Object>();row.put("facing",face.getName());row.put("hu_capacity",m.getMaxHeatStored());row.put("heat_capacity",m.getHeatCapacity());row.put("slots",m.getItemHandler().getSlots());row.put("progress_max",m.getMaxProgress());row.put("air_capacity",m.getAirCapacity());row.put("hu_sides",sides);
   row.put("default_saved",m.saveWithoutMetadata(world.registryAccess()).toString());
   m.setHeat(137);m.getAirTank().fill(new FluidStack(mio_icif_fluids.AIR.get(),7),IFluidHandler.FluidAction.EXECUTE);
   m.getItemHandler().setStackInSlot(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT,2));
   row.put("seeded_saved",m.saveWithoutMetadata(world.registryAccess()).toString());rows.add(row);
   world.removeBlock(pos,false);
  }
  var result=Map.<String,Object>of("passed",true,"assertions",assertions,"groups",List.of("six-public-default-blast-states"),"rows",rows,"scope","SI public ABI/default and ordinary serialization only; no implementation body inspection or behavior parity claim");
  Files.writeString(Path.of("blast-states-result.json"),new Gson().toJson(result));return result;
 }
}
