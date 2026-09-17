// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;
import com.google.gson.Gson;
import com.singularity_iteration.mio_icif.Blocks.entity.generator.mio_icif_nuclear_reactor_generator;
import com.singularity_iteration.mio_icif.api.capability.IMioIcifCapabilities;
import com.singularity_iteration.mio_icif.Items.Reactor.*;
import com.singularity_iteration.mio_icif.Items.DataComponent.FuelRodDurability;
import com.singularity_iteration.mio_icif.Items.Normal.mio_icif_data_components;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
/** Actual registries, SI public item helpers and a cached native HU capability. */
public final class NuclearItemWorldProbe {
 private final BlockPos at=new BlockPos(14000,80,100);private int assertions;private IMioIcifCapabilities.IHeatStorage cached;private mio_icif_nuclear_reactor_generator reactor;
 private void check(boolean value,String why){assertions++;if(!value)throw new AssertionError("R122 "+why);}
 private ItemStack item(String key){return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:reactor/item_reactor_"+key)));}
 public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception{
  if(tick==40){world.setBlockAndUpdate(at,BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:generator/block_nuclear_reactor_generator")).defaultBlockState());reactor=(mio_icif_nuclear_reactor_generator)world.getBlockEntity(at);reactor.getHeatStorage().setHeat(5000);cached=world.getCapability(IMioIcifCapabilities.HEAT_STORAGE_BLOCK,at,Direction.UP);check(cached!=null,"real native heat capability");check(cached.extractHeat(1000,false)==0&&reactor.getCurrentHeat()==5000,"pre-first-tick admission");}
  if(tick==60){
   check(cached.extractHeat(1000,true)==1000&&reactor.getCurrentHeat()==5000,"simulate does not debit");check(cached.extractHeat(1000,false)==1000&&reactor.getCurrentHeat()==4000,"single heat balance debit");
   check(CompletableFuture.supplyAsync(()->cached.extractHeat(1,false)).join()==0&&reactor.getCurrentHeat()==4000,"off-thread cached port cannot mutate world");
   for(String key:List.of("uranium_simple","uranium_dual","uranium_quad","mox_simple","mox_dual","mox_quad")){
    ItemStack stack=item(key);var rod=(mio_icif_nuclear_reactor)stack.getItem();stack.set(DataComponents.CUSTOM_NAME,Component.literal("retained "+key));
    stack.set(mio_icif_data_components.FUEL_ROD_DURABILITY.get(),new FuelRodDurability(2,10000,19));rod.operate(stack,1,0);
    var data=stack.get(mio_icif_data_components.FUEL_ROD_DURABILITY.get());check(data.remainingUses()==1&&data.maxUses()==10000&&data.tickCounter()==0,"legacy partial tick and original maximum retained "+key);
    for(int i=0;i<20;i++)rod.operate(stack,1,0);check(rod.isDepleted(stack),"twenty legacy calls exhaust final cycle "+key);var before=stack.copy();var ended=rod.operate(stack,1,0);check(ended.depleted&&ended.energyProduced==0&&ended.heatProduced==0&&ItemStack.matches(before,stack),"terminal calls are inert "+key);
    check(stack.getHoverName().getString().equals("retained "+key),"fuel name retained "+key);check(rod.isMoxFuel()==key.startsWith("mox"),"public MOX metadata "+key);check(rod.getNeutronPulseOutput()==rod.getNumberOfCells(),"public cell pulses "+key);
   }
   for(String key:List.of("vent","golden_vent","diamond_vent","vent_core","iridium_vent","iridium_oc_vent")){
    ItemStack stack=item(key);var vent=(mio_icif_heat_vent)stack.getItem();vent.setStoredHeat(stack,vent.getMaxHeatStorage());check(!stack.isEmpty()&&!vent.isMelted(stack),"exact heat capacity survives "+key);
    vent.addHeat(stack,1);check(stack.isEmpty(),"capacity plus one melts "+key);
   }
   ItemStack over=item("golden_vent");var vent=(mio_icif_heat_vent)over.getItem();vent.setStoredHeat(over,999);var emitted=new AtomicInteger(7);check(vent.cool(over,1000,emitted)==36&&over.isEmpty()&&emitted.get()==7,"overflow debits hull before melt and emits no heat");
   ItemStack component=item("vent_spread");var spread=(mio_icif_heat_vent)component.getItem();check(spread.addHeat(component,99)==99&&!component.isEmpty()&&spread.getStoredHeat(component)==0,"zero-capacity component vent rejects heat without melting");
   for(String key:List.of("condensator","condensator_lap")){
    ItemStack stack=item(key);var condenser=(mio_icif_condensator)stack.getItem();stack.set(DataComponents.CUSTOM_NAME,Component.literal("repair identity"));condenser.setStoredHeat(stack,condenser.getMaxHeatStorage());check(condenser.addHeat(stack,17)==17&&!stack.isEmpty(),"saturated condensator returns remainder");check(condenser.repairWithRedstone(stack)&&condenser.getStoredHeat(stack)==condenser.getMaxHeatStorage()-10000,"public redstone repair");check(stack.getHoverName().getString().equals("repair identity"),"condensator name retained");
   }
   reactor.setRemoved();check(cached.extractHeat(1000,false)==0&&reactor.getCurrentHeat()==4000,"removed cached heat guard");reactor.clearRemoved();check(cached.extractHeat(1000,false)==0,"reactivated core requires a real server tick");
  }
  if(tick==80)check(cached.extractHeat(1,false)==1,"re-admitted after natural server tick");
  // Run the legacy removal explosion only after both independent energy timelines finish.
  // Explosion events intentionally pause the shared grid for one frame.
  if(tick==450){long heat=reactor.getCurrentHeat();world.removeBlock(at,false);check(cached.extractHeat(1000,false)==0&&reactor.getCurrentHeat()==heat,"block removed cached port stays inert");
   var result=Map.<String,Object>of("passed",true,"assertions",assertions,"groups",List.of("actual-fuel-items-old-counter-and-metadata","vent-exact-capacity-and-overflow","condensator-repair-state","native-hu-cache-server-thread-and-lifecycle"));Files.writeString(Path.of("nuclear-item-world-result.json"),new Gson().toJson(result));return result;
  }return null;
 }
}
