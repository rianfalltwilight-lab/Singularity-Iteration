// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;
import com.google.gson.Gson;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_matter_elc;
import com.singularity_iteration.mio_icif.Blocks.Environment.fluid.mio_icif_fluids;
import com.singularity_iteration.mio_icif.Items.Normal.mio_icif_normal;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
/** Public SI ABI and ordinary saves only; excluded predecessor implementation remains opaque. */
public final class MatterStateProbe {
 public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception{
  if(tick!=20)return null;
  var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:producer/block_matter_elc"));var facing=(DirectionProperty)block.getStateDefinition().getProperty("facing");
  var rows=new ArrayList<Map<String,Object>>();int assertions=0;
  for(var face:facing.getPossibleValues()){
   var pos=new BlockPos(2+face.ordinal()*2,80,2);world.setBlock(pos,block.defaultBlockState().setValue(facing,face),3);
   if(!(world.getBlockEntity(pos) instanceof mio_icif_matter_elc m))throw new AssertionError("actual matter factory");assertions++;
   var eu=m.getEnergyStorageInternal();var row=new LinkedHashMap<String,Object>();row.put("facing",face.getName());row.put("slots",m.getItemHandler().getSlots());row.put("eu_capacity",eu.getCapacity());row.put("eu_max_receive",eu.getMaxReceive());row.put("uu_capacity",m.getUuMatterCapacity());row.put("scrap",m.getScrap());row.put("state",m.getState());row.put("default_saved",m.saveWithoutMetadata(world.registryAccess()).toString());
   eu.setEnergy(137);int filled=m.getUuMatterTank().fill(new FluidStack(mio_icif_fluids.UUMATTER.get(),7),IFluidHandler.FluidAction.EXECUTE);
   var remainder=m.getItemHandler().insertItem(0,new ItemStack(mio_icif_normal.SCRAP.get(),2),false);
   if(filled!=7||!remainder.isEmpty())throw new AssertionError("public seed accepted");assertions++;
   row.put("seeded_saved",m.saveWithoutMetadata(world.registryAccess()).toString());rows.add(row);world.removeBlock(pos,false);
  }
  var r=Map.<String,Object>of("passed",true,"assertions",assertions,"groups",List.of("actual-matter-defaults-and-ordinary-save"),"rows",rows,"scope","Public SI ABI and normal NBT only; no predecessor body inspection, source clearance or conversion claim");
  Files.writeString(Path.of("matter-states-result.json"),new Gson().toJson(r));return r;
 }
}
