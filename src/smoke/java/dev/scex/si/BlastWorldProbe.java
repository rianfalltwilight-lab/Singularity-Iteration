// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_blast_furnace;
import com.singularity_iteration.mio_icif.Blocks.entity.HUEntity.HuGenerator.mio_icif_heat_generator_elc;
import com.singularity_iteration.mio_icif.Blocks.Environment.fluid.mio_icif_fluids;
import com.singularity_iteration.mio_icif.Items.Cell.mio_icif_cells;
import com.singularity_iteration.mio_icif.api.capability.IMioIcifCapabilities;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.nbt.TagParser;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
/** Actual registered machines, finite reference values, real paid air and two JVMs. */
public final class BlastWorldProbe {
 private final boolean restart;
 private int checks;private int pausedAir;
 private final List<Integer> resumeAir=new ArrayList<>();
 private final List<String> groups=new ArrayList<>();
 private IMioIcifCapabilities.IHeatStorage oldPort;
 public BlastWorldProbe()throws Exception{restart=JsonParser.parseString(Files.readString(Path.of("blast-world.json"))).getAsJsonObject().get("phase").getAsString().equals("restart");}
 private void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError("R113 blast: "+why);}
 private BlockPos at(int i){return new BlockPos(5600+i*8,80,100);}
 private Direction face(int i){return Direction.from3DDataValue(i%6);}
 private mio_icif_blast_furnace m(ServerLevel w,int i){var b=w.getBlockEntity(at(i));check(b instanceof mio_icif_blast_furnace,"registered furnace "+i);return (mio_icif_blast_furnace)b;}
 private mio_icif_heat_generator_elc h(ServerLevel w,int i){return (mio_icif_heat_generator_elc)w.getBlockEntity(at(i).relative(face(i)));}
 private ItemStack item(String id,int count){return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:"+id)),count);}
 private void fill(mio_icif_blast_furnace m,int n){if(n>0)check(m.getAirTank().fill(new FluidStack(mio_icif_fluids.AIR.get(),n),IFluidHandler.FluidAction.EXECUTE)==n,"air fill exact");}
 private List<Map<String,Object>> snapshots(ServerLevel w){
  var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<7;i++){var m=m(w,i);var h=h(w,i);rows.add(Map.of("index",i,"progress",m.getProgress(),"air",m.getAirAmount(),"heat",m.getHeatStored(),"eu",h.getEnergyStorageInternal().getAmount(),"hu",h.getHeatStored(),"saved",m.saveWithoutMetadata(w.registryAccess()).toString()));}return rows;
 }
 private void compare(ServerLevel w)throws Exception{var expected=JsonParser.parseString(Files.readString(Path.of("world/scex-blast-checkpoint.json"))).getAsJsonObject().getAsJsonArray("rows");check(expected.equals(new Gson().toJsonTree(snapshots(w))),"cold/stopped paid-progress inventory and NBT exact");}
 private void refill(ServerLevel w,int count){for(int i=0;i<count;i++)if(i!=10)h(w,i).getEnergyStorageInternal().setEnergy(10000);}
 public Map<String,Object> inspect(ServerLevel w,int tick)throws Exception{
  if(restart){
   if(tick==20){compare(w);groups.add("cold-jvm-exact-partial-recipes");
    var furnace=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:producer/block_blast_furnace"));
    for(int i=14;i<16;i++){w.setBlockAndUpdate(at(i),furnace.defaultBlockState());var m=m(w,i);var tag=TagParser.parseTag("{inventory:{Items:[],Size:7},progress:0}");tag.putLong("heat",i==14?70000:-9);m.loadAdditional(tag,w.registryAccess());check(m.hasUnmappedState(),"invalid old heat held after final constructor");check(m.saveWithoutMetadata(w.registryAccess()).getCompound("scex_blast").getCompound("hold").getLong("heat")==tag.getLong("heat"),"raw old heat preserved after final constructor");if(i==14)check(m.getHeatStored()==70000,"oversized existing heat not clamped");}
    check(m(w,13).hasUnmappedState(),"future opaque hold survives changed storage constructor");groups.add("final-owned-storage-migration-boundaries");
    var cp=JsonParser.parseString(Files.readString(Path.of("world/scex-blast-checkpoint.json"))).getAsJsonObject();for(int i=0;i<7;i++)fill(m(w,i),cp.getAsJsonArray("resume_air").get(i).getAsInt());refill(w,7);}
   if(tick>20&&tick%80==20)refill(w,7);
   if(tick==3900){for(int i=0;i<7;i++){var m=m(w,i);check(m.getProgress()==0&&m.getAirAmount()==2000,"exact6000mB across saved cycle "+i);check(m.getItemHandler().getStackInSlot(0).isEmpty(),"one iron consumed "+i);check(ItemStack.matches(m.getItemHandler().getStackInSlot(1),item("resource/item_adviron_ingot",1)),"one steel "+i);check(ItemStack.matches(m.getItemHandler().getStackInSlot(2),item("normal/item_slag",1)),"one slag "+i);check(!m.hasUnmappedState(),"no unexpected hold "+i);}groups.add("seven-natural-completions-after-reheat-and-cold-resume");return finish(w,tick);}
   return null;
  }
  if(tick==20){
   var heater=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:hugenerator/block_heat_generator_elc"));var furnace=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:producer/block_blast_furnace"));
   for(int i=0;i<12;i++){
    w.setBlockAndUpdate(at(i).relative(face(i)),heater.defaultBlockState().setValue(BlockStateProperties.FACING,face(i).getOpposite()));
    w.setBlockAndUpdate(at(i),furnace.defaultBlockState().setValue(BlockStateProperties.FACING,face(i)));
    var m=m(w,i);var h=h(w,i);check(m.getItemHandler().getSlots()==7&&m.getMaxHeatStored()==50100&&m.getAirCapacity()==8000,"public capacities/slots");
    for(var side:Direction.values())check((w.getCapability(IMioIcifCapabilities.HEAT_STORAGE_BLOCK,at(i),side)!=null)==(side==face(i)),"actual HU input face");
    if(i!=8)m.getItemHandler().setStackInSlot(0,new ItemStack(Items.IRON_INGOT));
    if(i!=7&&i!=11)fill(m,8000);
    m.setHeat(i==6?0:50000);
    if(i!=10){for(int c=1;c<=10;c++)check(h.getItemHandler().insertItem(c,item("resource/item_coil",1),false).isEmpty(),"normal coil insertion");h.getEnergyStorageInternal().setEnergy(10000);h.setHeat(100);}
    if(i==9){m.getItemHandler().setStackInSlot(1,new ItemStack(Items.COBBLESTONE,64));m.getItemHandler().setStackInSlot(2,new ItemStack(Items.COBBLESTONE,64));}
    if(i==11)m.getItemHandler().setStackInSlot(3,new ItemStack(mio_icif_cells.CELL_AIR.get()));
   }
   oldPort=w.getCapability(IMioIcifCapabilities.HEAT_STORAGE_BLOCK,at(8),face(8));
   var old=TagParser.parseTag("{airTank:{Fluid:{amount:7,id:\"mio_icif:air\"}},heat:137L,inventory:{Items:[{Item:{count:2,id:\"minecraft:iron_ingot\"},Slot:0}],Size:7},isWorking:0b,progress:0}");
   for(int i=12;i<14;i++){w.setBlockAndUpdate(at(i),furnace.defaultBlockState());var m=m(w,i);var tag=old.copy();if(i==13){var future=new net.minecraft.nbt.CompoundTag();future.putInt("version",99);future.putString("opaque","future balance");tag.put("scex_blast",future);}m.loadAdditional(tag,w.registryAccess());check(m.getHeatStored()==137&&m.getAirAmount()==7&&m.getItemHandler().getStackInSlot(0).getCount()==2,"observed old save retained");check(m.hasUnmappedState()==(i==13),"future state held");}
   for(int i=14;i<16;i++){w.setBlockAndUpdate(at(i),furnace.defaultBlockState());var m=m(w,i);var tag=old.copy();tag.putLong("heat",i==14?70000:-9);m.loadAdditional(tag,w.registryAccess());check(m.hasUnmappedState(),"invalid old heat held");check(m.saveWithoutMetadata(w.registryAccess()).getCompound("scex_blast").getCompound("hold").getLong("heat")==tag.getLong("heat"),"raw old heat preserved");}
  }
  if(tick>20&&tick<3200&&tick%80==20)refill(w,12);
  if(tick==60){
   for(int i=0;i<6;i++){var m=m(w,i);check(m.getProgress()==40&&m.getAirAmount()==7960&&m.getHeatStored()==50061,"measured six-face hot trajectory "+i);}
   check(m(w,6).getHeatStored()==4000&&m(w,6).getProgress()==0,"measured100HU per cold tick");
   check(m(w,7).getProgress()==0&&m(w,7).getHeatStored()==50061,"no-air pause heats");
   check(m(w,8).getHeatStored()==49960&&h(w,8).getEnergyStorageInternal().getAmount()==10000,"no-input no heat request");
   check(m(w,9).getProgress()==0&&m(w,9).getAirAmount()==8000,"blocked outputs no air debit");
   check(m(w,10).getProgress()==0&&m(w,10).getHeatStored()==49960,"no heat no processing");
   check(m(w,11).getProgress()==40&&m(w,11).getAirAmount()==960&&mio_icif_cells.isEmptyCell(m(w,11).getItemHandler().getStackInSlot(4)),"one air cell exact contents and empty return");
   check(m(w,13).getHeatStored()==137&&m(w,13).hasUnmappedState(),"future state not ticked");
   groups.add("six-facing-trajectories-and-cold-heat");groups.add("air-input-output-and-cell-controls");groups.add("ordinary-legacy-and-future-state-retention");
  }
  if(tick==100){fill(m(w,7),8000);m(w,9).getItemHandler().setStackInSlot(1,ItemStack.EMPTY);m(w,9).getItemHandler().setStackInSlot(2,ItemStack.EMPTY);var s=w.getBlockState(at(8));w.setBlock(at(8),s.setValue(BlockStateProperties.FACING,face(8).getOpposite()),3);check(oldPort.receiveHeat(100,false)==0,"cached HU face rejected after rotation");}
  if(tick==140){check(m(w,7).getProgress()==40&&m(w,9).getProgress()==40,"air refill and precise output unblock resume");groups.add("unblock-and-cached-port-invalidation");}
  if(tick==200){pausedAir=m(w,0).getAirAmount();m(w,0).getAirTank().drain(Integer.MAX_VALUE,IFluidHandler.FluidAction.EXECUTE);}
  if(tick==240){check(m(w,0).getProgress()==180&&m(w,0).getAirAmount()==0,"paid progress held during40ticks no air");fill(m(w,0),pausedAir);groups.add("paid-progress-pause-resume");}
  if(tick==3200){for(int i=0;i<7;i++){var m=m(w,i);check(m.getProgress()>2000&&m.getProgress()<6000&&m.getProgress()+m.getAirAmount()==8000,"partial cycle exact air accounting");resumeAir.add(m.getAirAmount());m.getAirTank().drain(Integer.MAX_VALUE,IFluidHandler.FluidAction.EXECUTE);m.setHeat(0);h(w,i).getEnergyStorageInternal().setEnergy(0);h(w,i).setHeat(0);}}
  if(tick==3205)Files.writeString(Path.of("world/scex-blast-checkpoint.json"),new Gson().toJson(Map.of("rows",snapshots(w),"resume_air",resumeAir)));
  if(tick==3210){compare(w);groups.add("idle-partial-cycle-checkpoint");return finish(w,tick);}
  return null;
 }
 private Map<String,Object> finish(ServerLevel w,int tick)throws Exception{var r=Map.<String,Object>of("passed",true,"assertions",checks,"groups",groups,"phase",restart?"restart":"initial","cases",16,"ticks",tick,"rows",snapshots(w));Files.writeString(Path.of("blast-world-result.json"),new Gson().toJson(r));return r;}
}
