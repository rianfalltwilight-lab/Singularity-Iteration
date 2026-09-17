// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;
import com.google.gson.Gson;
import com.singularity_iteration.mio_icif.Blocks.entity.generator.mio_icif_nuclear_reactor_generator;
import com.singularity_iteration.mio_icif.Blocks.entity.reactor.mio_icif_reactor_chamber;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Container;
import com.singularity_iteration.mio_icif.api.capability.IMioIcifCapabilities;
import com.singularity_iteration.mio_icif.energy.EnergyUnit.*;
import dev.scex.energy.EnergyAmount;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Real six-face chamber contacts share one core quote; cached ports retain owner identity. */
public final class ChamberWorldProbe {
 private int assertions;private final List<List<BlockPos>> receivers=new ArrayList<>();private final List<BlockPos> switches=new ArrayList<>();
 private final BlockPos cacheAt=new BlockPos(16300,80,100);private mio_icif_nuclear_reactor_generator cachedCore;private mio_icif_reactor_chamber cachedChamber;
 private IEUEnergyStorage eu;private IMioIcifCapabilities.IHeatStorage hu;private IItemHandler items;
 private void check(boolean value,String why){assertions++;if(!value)throw new AssertionError("R124 "+why);}
 private BlockPos at(int i){return new BlockPos(16000+i*16,80,100);}
 private mio_icif_nuclear_reactor_generator core(ServerLevel w,BlockPos at){return (mio_icif_nuclear_reactor_generator)w.getBlockEntity(at);}
 private void place(ServerLevel w,BlockPos at,String id,Direction face){var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:"+id));var state=block.defaultBlockState();var prop=block.getStateDefinition().getProperty("facing");if(prop instanceof DirectionProperty p){check(p.getPossibleValues().contains(face),"valid factory facing");state=state.setValue(p,face);}check(w.setBlockAndUpdate(at,state),"placed "+id);}
 private ItemStack fuel(){return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:reactor/item_reactor_uranium_simple")));}
 private void inert(String why){long heat=cachedCore.getCurrentHeat();var balance=cachedCore.ownedEnergy().scexExactAmount();var rod=fuel();check(hu.extractHeat(100,false)==0&&cachedCore.getCurrentHeat()==heat,why+" HU");check(eu.extract(100,false)==0&&cachedCore.ownedEnergy().scexExactAmount().equals(balance),why+" EU");check(ItemStack.matches(items.insertItem(0,rod,false),rod)&&items.extractItem(0,1,false).isEmpty(),why+" items");}
 private List<Map<String,Object>> snapshot(ServerLevel w){var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<13;i++){var m=core(w,at(i));var total=m.ownedEnergy().scexExactAmount();var amounts=new ArrayList<EnergyAmount>();for(var p:receivers.get(i)){var n=((mio_icif_Energy_Container)w.getBlockEntity(p)).getEnergyStorageInternal().scexExactAmount();total=total.add(n);amounts.add(n);}rows.add(Map.of("case",i,"core",m.saveWithoutMetadata(w.registryAccess()).toString(),"sum",total,"sinks",amounts));}return rows;}
 public Map<String,Object> inspect(ServerLevel w,int tick)throws Exception{
  if(tick==20){
   for(int i=0;i<13;i++){
    var center=at(i);place(w,center,"generator/block_nuclear_reactor_generator",Direction.NORTH);core(w,center).setItem(0,fuel());var sinks=new ArrayList<BlockPos>();
    var sides=i==12?Direction.values():new Direction[]{Direction.values()[i%6]};
    for(var side:sides){place(w,center.relative(side),"reactor/block_reactor_chamber",Direction.NORTH);if(i>=6&&i<12)place(w,center.relative(side,2),"wiring/cable/block_glass_cable",Direction.NORTH);var sink=center.relative(side,i>=6&&i<12?3:2);place(w,sink,"wiring/block_mfsu",side);sinks.add(sink);}
    var power=i==12?center.above().east():center.relative(sides[0].getOpposite());check(w.setBlockAndUpdate(power,Blocks.REDSTONE_BLOCK.defaultBlockState()),"chamber/core redstone");switches.add(power);receivers.add(sinks);
    check(core(w,center).getAvailableColumns()==(i==12?9:4),"only live unique chambers expand inventory");check(core(w,center).electricalContactPositions().size()==(i==12?7:2),"one core plus chamber contact positions");
   }
   place(w,cacheAt,"generator/block_nuclear_reactor_generator",Direction.NORTH);place(w,cacheAt.east(),"reactor/block_reactor_chamber",Direction.NORTH);cachedCore=core(w,cacheAt);cachedCore.getHeatStorage().setHeat(1000);cachedCore.ownedEnergy().setEnergy(137);cachedChamber=(mio_icif_reactor_chamber)w.getBlockEntity(cacheAt.east());
   eu=w.getCapability(EUApi.SIDED,cacheAt.east(),Direction.UP);hu=w.getCapability(IMioIcifCapabilities.HEAT_STORAGE_BLOCK,cacheAt.east(),Direction.UP);items=w.getCapability(Capabilities.ItemHandler.BLOCK,cacheAt.east(),Direction.UP);check(eu!=null&&hu!=null&&items!=null,"native registry exposes all guarded chamber ports");check(hu.extractHeat(1,false)==0,"no first-tick heat admission");
  }
  if(tick==60){
   check(hu.extractHeat(17,true)==17&&cachedCore.getCurrentHeat()==1000,"chamber heat simulation");check(hu.extractHeat(17,false)==17&&cachedCore.getCurrentHeat()==983,"chamber heat debits only core");
   check(CompletableFuture.supplyAsync(()->hu.extractHeat(1,false)).join()==0,"off-thread chamber heat inert");
   var rod=fuel();check(items.insertItem(0,rod,true).isEmpty()&&cachedCore.getItem(0).isEmpty(),"chamber item simulation");check(items.insertItem(0,rod,false).isEmpty()&&cachedCore.getItem(0).getCount()==1,"chamber owns no separate inventory");
   var saved=cachedChamber.saveWithoutMetadata(w.registryAccess());saved.putInt("ReactorX",999999);cachedChamber.loadAdditional(saved,w.registryAccess());check(cachedChamber.getConnectedReactor()==cachedCore,"saved coordinates cannot redirect ownership");
   // Suppress neighbor notifications for this same-call capability ambiguity check; the legacy block event otherwise destroys the chamber immediately.
   check(w.setBlock(cacheAt.east(2),cachedCore.getBlockState(),2),"second actual core without neighbor event");check(cachedChamber.getConnectedReactor()==null&&cachedCore.getAvailableColumns()==3&&core(w,cacheAt.east(2)).getAvailableColumns()==3,"ambiguous two-core chamber rejected on both sides");inert("ambiguous cached ports");
   w.removeBlock(cacheAt.east(2),false);check(cachedChamber.getConnectedReactor()==cachedCore&&cachedCore.getAvailableColumns()==4,"unique ownership restores without cached position");
   cachedChamber.setRemoved();inert("removed flag");cachedChamber.clearRemoved();check(cachedChamber.getConnectedReactor()==cachedCore,"live chamber readmission");
   w.removeBlock(cacheAt.east(),false);check(cachedChamber.isRemoved(),"normal removal completes tile removal");inert("removed actual chamber");place(w,cacheAt.east(),"reactor/block_reactor_chamber",Direction.NORTH);inert("replacement chamber cannot revive old ports");
   var current=(mio_icif_reactor_chamber)w.getBlockEntity(cacheAt.east());var oldPort=current.getItemHandlerCapability(Direction.UP);w.removeBlock(cacheAt,false);place(w,cacheAt,"generator/block_nuclear_reactor_generator",Direction.NORTH);check(ItemStack.matches(oldPort.insertItem(0,fuel(),false),fuel()),"same-position replacement core cannot inherit cached item port");
  }
  if(tick==221)for(var at:switches)w.removeBlock(at,false);
  if(tick==260){
   var rows=snapshot(w);for(int i=0;i<13;i++){check(rows.get(i).get("sum").equals(EnergyAmount.of(1000)),"exact single1000 EU budget across contacts case"+i+" actual="+rows.get(i).get("sum"));check(core(w,at(i)).getCurrentHeat()==40,"ten natural hull cycles case"+i);check(!core(w,at(i)).isRunning(),"redstone pause case"+i);}
   for(var p:receivers.get(12))check(((mio_icif_Energy_Container)w.getBlockEntity(p)).getEnergyStorageInternal().getAmount()>0,"all six chamber receivers served");
  }
  if(tick==450){
   // R123 command-removal parity and no stale tile/neighbor blast for eight hull states.
   int[] heat={0,1999,2000,3999,4000,6999,8000,9999};
   for(int i=0;i<heat.length;i++){var at=new BlockPos(16400+i*16,80,100);place(w,at,"generator/block_nuclear_reactor_generator",Direction.NORTH);var m=core(w,at);m.getHeatStorage().setHeat(heat[i]);for(var side:Direction.values())w.setBlockAndUpdate(at.relative(side),Blocks.STONE.defaultBlockState());w.removeBlock(at,false);check(m.isRemoved()&&w.getBlockEntity(at)==null&&w.getBlockState(at).isAir(),"heated removal clears identity "+heat[i]);for(var side:Direction.values())check(w.getBlockState(at.relative(side)).is(Blocks.STONE),"heated removal preserves neighboring stone "+heat[i]);}
   var result=Map.<String,Object>of("passed",true,"assertions",assertions,"cases",13,"groups",List.of("six-face-direct-and-wire-chamber-native-energy","six-chambers-share-one-core-budget","cached-eu-hu-item-owner-identity-and-ambiguity","eight-heated-removal-controls"),"rows",snapshot(w));Files.writeString(Path.of("chamber-world-result.json"),new Gson().toJson(result));return result;
  }return null;
 }
}
