// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;
import com.google.gson.Gson;
import com.singularity_iteration.mio_icif.Blocks.entity.generator.mio_icif_nuclear_reactor_generator;
import com.singularity_iteration.mio_icif.Items.Reactor.mio_icif_heat_vent;
import com.singularity_iteration.mio_icif.Items.Reactor.mio_icif_condensator;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/** Ordinary SI factories, public item metadata and saves only. Excluded bodies are not inspected. */
public final class ReactorStateProbe {
 private final List<Map<String,Object>> rows=new ArrayList<>();private int assertions;
 private static final BlockPos AT=new BlockPos(40,80,4);
 private mio_icif_nuclear_reactor_generator m(ServerLevel w){return (mio_icif_nuclear_reactor_generator)w.getBlockEntity(AT);}
 private void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError("R116 public reactor: "+why);}
 public Map<String,Object> inspect(ServerLevel w,int tick)throws Exception{
  var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:generator/block_nuclear_reactor_generator"));
  if(tick==20){
   var property=(DirectionProperty)block.getStateDefinition().getProperty("facing");
   for(var face:property.getPossibleValues()){
    var at=new BlockPos(2+face.ordinal()*3,80,4);w.setBlockAndUpdate(at,block.defaultBlockState().setValue(property,face));
    check(w.getBlockEntity(at) instanceof mio_icif_nuclear_reactor_generator,"actual registered reactor factory");var m=(mio_icif_nuclear_reactor_generator)w.getBlockEntity(at);
    var row=new LinkedHashMap<String,Object>();row.put("facing",face.getName());row.put("slots",m.getContainerSize());row.put("columns",m.getAvailableColumns());row.put("current_slots",m.getCurrentSlotCount());row.put("eu_capacity",m.getEnergyStorageInternal().getCapacity());row.put("eu_max_extract",m.getEnergyStorageInternal().getMaxExtract());row.put("heat_max",m.getMaxHeat());row.put("mode",m.getReactorMode().toString());row.put("default_saved",m.saveWithoutMetadata(w.registryAccess()).toString());
    m.getEnergyStorageInternal().setEnergy(137);m.getHeatStorage().setHeat(29);var rod=BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:reactor/item_reactor_uranium_simple"));m.setItem(0,new ItemStack(rod));
    check(m.getEnergyStorageInternal().getAmount()==137&&m.getCurrentHeat()==29&&!m.getItem(0).isEmpty(),"ordinary public seed retained");row.put("seeded_saved",m.saveWithoutMetadata(w.registryAccess()).toString());rows.add(row);m.clearContent();w.removeBlock(at,false);
   }
   w.setBlockAndUpdate(AT,block.defaultBlockState());
  }
  if(tick==30){rows.add(Map.of("phase","zero-chambers","columns",m(w).getAvailableColumns(),"slots",m(w).getCurrentSlotCount(),"saved",m(w).saveWithoutMetadata(w.registryAccess()).toString()));
   var chamber=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:reactor/block_reactor_chamber"));for(var side:Direction.values())check(w.setBlockAndUpdate(AT.relative(side),chamber.defaultBlockState()),"six actual chambers placed");
  }
  if(tick==60){
   if(Files.exists(Path.of("reactor-item-calls.json")))rows.add(Map.of("public_item_calls",ReactorItemCallsProbe.inspect(w)));
   rows.add(Map.of("phase","six-chambers","columns",m(w).getAvailableColumns(),"slots",m(w).getCurrentSlotCount(),"saved",m(w).saveWithoutMetadata(w.registryAccess()).toString()));
   var items=new ArrayList<Map<String,Object>>();
   for(var id:BuiltInRegistries.ITEM.keySet())if(id.getNamespace().equals("mio_icif")&&id.getPath().startsWith("reactor/")){
    var item=BuiltInRegistries.ITEM.get(id);var stack=new ItemStack(item);var r=new LinkedHashMap<String,Object>();r.put("id",id.toString());r.put("saved",stack.save(w.registryAccess()).toString());
    if(item instanceof mio_icif_heat_vent vent){r.put("max_heat",vent.getMaxHeatStorage());r.put("stored_heat",vent.getStoredHeat(stack));r.put("self_cooling",vent.getSelfCoolingRate());r.put("hull_absorption",vent.getReactorHeatAbsorptionRate());}
    if(item instanceof mio_icif_condensator con){r.put("max_heat",con.getMaxHeatStorage());r.put("stored_heat",con.getStoredHeat(stack));r.put("absorption",con.getHeatAbsorptionRate());r.put("redstone_repair",con.getRedstoneRepairAmount());r.put("lapis_repair",con.getLapisRepairAmount());}
    items.add(r);
   }
   check(!items.isEmpty(),"registered reactor item catalog present");
   var r=Map.<String,Object>of("passed",true,"assertions",assertions,"groups",List.of("actual-reactor-factories-and-ordinary-saves","actual-six-chambers-and-public-item-metadata"),"rows",rows,"items",items,"scope","SI public integration metadata only; no excluded body inspection, source clearance or original-game equivalence");Files.writeString(Path.of("reactor-states-result.json"),new Gson().toJson(r));return r;
  }return null;
 }
}
