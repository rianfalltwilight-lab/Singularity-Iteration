// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;
import com.google.gson.Gson;
import com.singularity_iteration.mio_icif.Blocks.entity.generator.mio_icif_kinetic_generator;
import com.singularity_iteration.mio_icif.Blocks.mio_icif_entity_block;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
/** SI public state observation only, without reading the excluded predecessor body. */
public final class KineticStateProbe {
 public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception {
  if(tick!=20)return null;
  var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:generator/block_kinetic_generator"));
  var rows=new ArrayList<Map<String,Object>>();int assertions=0;
  for(var face:mio_icif_entity_block.FACING.getPossibleValues()){
   var pos=new BlockPos(2+face.ordinal()*2,80,2);
   world.setBlock(pos,block.defaultBlockState().setValue(mio_icif_entity_block.FACING,face),3);
   if(!(world.getBlockEntity(pos) instanceof mio_icif_kinetic_generator m))throw new AssertionError("Actual kinetic factory");assertions++;
   var eu=m.getEnergyStorage();var ku=m.getKineticStorage();var sides=new LinkedHashMap<String,Boolean>();
   for(var side:Direction.values())sides.put(side.getName(),m.getKineticStorageCapability(side)!=null);
   var row=new LinkedHashMap<String,Object>();row.put("facing",face.getName());row.put("eu_capacity",eu.getCapacity());row.put("eu_amount",eu.getAmount());row.put("source_tier",m.getSourceTier());
   row.put("eu_max_extract",eu.getMaxExtract());row.put("ku_capacity",ku.getMaxKineticStored());row.put("ku_amount",ku.getKineticStored());
   row.put("ku_max_receive",ku.getMaxReceive());row.put("ku_max_extract",ku.getMaxExtract());row.put("ku_sides",sides);
   row.put("saved",m.saveWithoutMetadata(world.registryAccess()).toString());rows.add(row);
   world.removeBlock(pos,false);
  }
  var result=Map.<String,Object>of("passed",true,"assertions",assertions,"groups",List.of("four-public-default-horizontal-kinetic-generator-states"),"rows",rows,"scope","SI public ABI/default integration observation only; no implementation-body inspection, conversion parity or source clearance");
  Files.writeString(Path.of("kinetic-states-result.json"),new Gson().toJson(result));return result;
 }
}
