// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;
import com.singularity_iteration.mio_icif.Items.Reactor.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Only declared public SI item calls and ordinary saved stacks; no implementation inspection. */
public final class ReactorItemCallsProbe {
 private static String saved(ItemStack stack,ServerLevel w){return stack.isEmpty()?"{}":stack.save(w.registryAccess()).toString();}
 public static List<Map<String,Object>> inspect(ServerLevel w){
  var out=new ArrayList<Map<String,Object>>();
  for(var id:BuiltInRegistries.ITEM.keySet())if(id.getNamespace().equals("mio_icif")&&id.getPath().startsWith("reactor/")){
   var item=BuiltInRegistries.ITEM.get(id);
   if(item instanceof mio_icif_nuclear_reactor rod){
    var metadata=new LinkedHashMap<String,Object>();metadata.put("id",id.toString());metadata.put("operation","fuel-metadata");
    metadata.put("energy",rod.getEnergyOutput());metadata.put("actual_energy",rod.getActualEnergyOutput());metadata.put("heat",rod.getHeatOutput());metadata.put("actual_heat",rod.getActualHeatOutput());
    metadata.put("pulses",rod.getNeutronPulseCount());metadata.put("self_pulses",rod.getBaseSelfPulses());metadata.put("multiplier",rod.getBaseEnergyMultiplier());metadata.put("cells",rod.getNumberOfCells());metadata.put("is_mox",rod.isMoxFuel());metadata.put("receive_pulse",rod.canReceiveNeutronPulse());
    metadata.put("type_multiplier",rod.getRodType().getEfficiencyMultiplier());metadata.put("range",rod.getRodType().getRange());metadata.put("type_pulses",rod.getRodType().getNeutronPulseCount());metadata.put("type_energy",rod.getRodType().getBaseEnergyMultiplier());out.add(metadata);
    for(int a:new int[]{0,1,2,4})for(int b:new int[]{0,1,4})for(String op:List.of("heat","operate")){
     ItemStack stack=new ItemStack(item);var row=new LinkedHashMap<String,Object>();row.put("id",id.toString());row.put("operation",op);row.put("args",List.of(a,b));row.put("before",saved(stack,w));
     var result=op.equals("heat")?rod.getHeatOutput(stack,a,b):rod.operate(stack,a,b);
     row.put("eu",result.energyProduced);row.put("heat",result.heatProduced);row.put("depleted",result.depleted);row.put("after",saved(stack,w));out.add(row);
    }
    for(boolean heatPhase:new boolean[]{false,true}){ItemStack stack=new ItemStack(item);var accumulator=new AtomicInteger(7);var ok=rod.acceptNeutronPulse(stack,accumulator,heatPhase);out.add(Map.of("id",id.toString(),"operation","accept-pulse","heat_phase",heatPhase,"result",ok,"accumulator",accumulator.get(),"after",saved(stack,w)));}
    if(rod instanceof mio_icif_mox_reactor mox)for(float ratio:new float[]{0,.5f,.5001f,1})for(int a:new int[]{0,1,4}){
     ItemStack stack=new ItemStack(item);var fluid=mox.getMoxHeatOutputForFluid(stack,a,0,ratio);var active=mox.operateMox(stack,a,0,ratio);
     out.add(Map.of("id",id.toString(),"operation","mox","args",List.of(a,ratio),"eu",mox.getMoxEnergyOutput(new ItemStack(item),a,ratio),"heat_dry",mox.getMoxHeatOutput(new ItemStack(item),a,ratio,false),"heat_wet",mox.getMoxHeatOutput(new ItemStack(item),a,ratio,true),"fluid",List.of(fluid.energyProduced,fluid.heatProduced,fluid.depleted),"active",List.of(active.energyProduced,active.heatProduced,active.depleted),"after",saved(stack,w)));
    }
   }
   if(item instanceof mio_icif_heat_vent vent)for(int heat:new int[]{0,1,999,1000,1001})for(int hull:new int[]{0,1000}){
    ItemStack stack=new ItemStack(item);vent.setStoredHeat(stack,heat);var emitted=new AtomicInteger(7);var before=saved(stack,w);int cooled=vent.cool(stack,hull,emitted);
    out.add(Map.of("id",id.toString(),"operation","vent-cool","args",List.of(heat,hull),"before",before,"result",cooled,"emitted",emitted.get(),"after",saved(stack,w),"melted",vent.isMelted(stack)));
   }
   if(item instanceof mio_icif_condensator con)for(int heat:new int[]{0,1,10000,20001,100001})for(String op:List.of("add","remove","redstone","lapis")){
    ItemStack stack=new ItemStack(item);con.setStoredHeat(stack,heat);var before=saved(stack,w);Object result=switch(op){case "add"->con.addHeat(stack,10001);case "remove"->con.removeHeat(stack,10001);case "redstone"->con.repairWithRedstone(stack);default->con.repairWithLapis(stack);};
    out.add(Map.of("id",id.toString(),"operation","condensator-"+op,"heat_seed",heat,"before",before,"result",result,"after",saved(stack,w),"full",con.isFull(stack)));
   }
  }return out;
 }
}
