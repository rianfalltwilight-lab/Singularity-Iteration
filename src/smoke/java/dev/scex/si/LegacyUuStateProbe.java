// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Items.Normal.mio_icif_data_components;
import com.singularity_iteration.mio_icif.Items.DataComponent.ReactorComponentData;
import com.singularity_iteration.mio_icif.Items.DataComponent.FuelRodDurability;
import dev.scex.si.processing.UuQuoteBook;
import dev.scex.si.reactor.ReactorInventory;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/** Registered SI default stacks and independent nuclear state; no reference implementation access. */
public final class LegacyUuStateProbe {
 private int assertions;
 private void check(boolean value,String why){assertions++;if(!value)throw new AssertionError("Legacy UU defaults: "+why);}
 private String saved(ServerLevel world,ItemStack stack){
  var tag=stack.save(world.registryAccess());var copy=ItemStack.parse(world.registryAccess(),tag).orElseThrow();
  check(ItemStack.matches(stack,copy),"ordinary exact item serialization");return tag.toString();
 }
 public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception{
  if(tick!=20)return null;
  var fixture=JsonParser.parseString(Files.readString(Path.of("legacy-uu-states.json"))).getAsJsonObject();var cases=fixture.getAsJsonArray("rows");check(cases.size()==32,"32 frozen candidates");
  var rows=new ArrayList<Map<String,Object>>();var ops=world.registryAccess().createSerializationContext(NbtOps.INSTANCE);
  for(var entry:cases){
   var input=entry.getAsJsonObject();String target=input.get("target_item").getAsString();var id=ResourceLocation.parse(target);check(BuiltInRegistries.ITEM.containsKey(id),"registered target "+target);
   var item=BuiltInRegistries.ITEM.get(id);var stack=new ItemStack(item);var factory=item.getDefaultInstance();check(stack.getCount()==1&&!stack.isEmpty(),"single default stack");
   var row=new LinkedHashMap<String,Object>();row.put("source_row",input.get("source_row").getAsInt());row.put("target_item",target);row.put("runtime_class",item.getClass().getName());row.put("default_stack",saved(world,stack));row.put("factory_stack",saved(world,factory));
   row.put("same_factory_components",ItemStack.matches(stack,factory));row.put("default_components",DataComponentMap.CODEC.encodeStart(ops,stack.getComponents()).getOrThrow().toString());row.put("classification_before_mapping",UuQuoteBook.classify(world.getServer(),stack));
   if(input.get("family").getAsString().equals("PRISTINE_NUCLEAR_COMPONENT")){
    var part=ReactorInventory.read(stack);check(part!=null&&part.stored()==0,"pristine registered nuclear state");row.put("nuclear_kind",part.profile().kind().name());row.put("heat_or_wear",part.stored());row.put("capacity",part.profile().capacity());row.put("remaining_cycles",part.remaining());
    var changed=stack.copy();var thermal=stack.get(mio_icif_data_components.REACTOR_COMPONENT_DATA.get());var fuel=stack.get(mio_icif_data_components.FUEL_ROD_DURABILITY.get());
    if(thermal!=null){check(thermal.storedValue()==0&&thermal.maxValue()>0,"thermal default capacity");changed.set(mio_icif_data_components.REACTOR_COMPONENT_DATA.get(),new ReactorComponentData(1,thermal.maxValue()));}
    else if(fuel!=null){check(fuel.remainingUses()>1&&fuel.remainingUses()==fuel.maxUses()&&fuel.tickCounter()==0,"full fuel default");changed.set(mio_icif_data_components.FUEL_ROD_DURABILITY.get(),new FuelRodDurability(fuel.remainingUses()-1,fuel.maxUses(),0));}
    else throw new AssertionError("Missing expected nuclear component state "+target);
    check(!ItemStack.isSameItemSameComponents(stack,changed),"one state change produces distinct exact identity");row.put("changed_stack",saved(world,changed));row.put("changed_classification_before_mapping",UuQuoteBook.classify(world.getServer(),changed));
   }else{check(item instanceof BlockItem,"actual cable block item");row.put("default_block_state",((BlockItem)item).getBlock().defaultBlockState().toString());}
   rows.add(row);
  }
  var result=Map.<String,Object>of("passed",true,"assertions",assertions,"cases",32,"rows",rows,"scope","Public default identities only; no new price or scan eligibility admitted");Files.writeString(Path.of("legacy-uu-states-result.json"),new Gson().toJson(result));return result;
 }
}
