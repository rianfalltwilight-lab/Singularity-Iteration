// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_matter_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Container;
import com.singularity_iteration.mio_icif.Blocks.Environment.fluid.mio_icif_fluids;
import com.singularity_iteration.mio_icif.Items.Cell.mio_icif_cells;
import com.singularity_iteration.mio_icif.Items.Normal.mio_icif_normal;
import dev.scex.energy.EnergyAmount;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Actual MFE inputs, owned exact ledger, normal containers, old saves and separate cold JVM. */
public final class MatterWorldProbe {
 private static final int CASES=22;
 private static final long[] CREDIT={0,5000,80000,45000,720000};
 private final boolean restart;
 private int assertions;private final List<String> groups=new ArrayList<>();
 private final Map<Integer,Integer> firstUu=new LinkedHashMap<>();
 private List<Map<String,Object>> paused;
 private IFluidHandler removedPort;
 private static final Direction[] SIDES={Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST};
 public MatterWorldProbe()throws Exception{restart=JsonParser.parseString(Files.readString(Path.of("matter-world.json"))).getAsJsonObject().get("phase").getAsString().equals("restart");}
 private void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError("R115 matter: "+why);}
 private BlockPos at(int i){return new BlockPos(6000+i*8,80,100);}
 private mio_icif_matter_elc m(ServerLevel w,int i){return (mio_icif_matter_elc)w.getBlockEntity(at(i));}
 private mio_icif_Energy_Container source(ServerLevel w,int i){return (mio_icif_Energy_Container)w.getBlockEntity(at(i).above());}
 private void place(ServerLevel w,BlockPos p,String id,Direction face){
  var b=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:"+id));var prop=(DirectionProperty)b.getStateDefinition().getProperty("facing");
  check(prop!=null&&prop.getPossibleValues().contains(face),"legal actual placement "+id);check(w.setBlockAndUpdate(p,b.defaultBlockState().setValue(prop,face)),"placed "+id);
 }
 private void amplifier(ServerLevel w,int i,boolean box,int count){check(m(w,i).getItemHandler().insertItem(0,new ItemStack(box?mio_icif_normal.SCRAPBOX.get():mio_icif_normal.SCRAP.get(),count),false).isEmpty(),"amplifier insertion");}
 private void fill(ServerLevel w,int i,int amount){check(m(w,i).getUuMatterTank().fill(new FluidStack(mio_icif_fluids.UUMATTER.get(),amount),IFluidHandler.FluidAction.EXECUTE)==amount,"owned fixture tank seed");}
 private void old(ServerLevel w,int i,long eu,long fraction,int scrap,int count,int fluid)throws Exception{
  var tag=TagParser.parseTag("{LastEnergy:0.0d,Scrap:0,UuMatterTank:{},energy:0L,scex_energy_fraction:0L,inventory:{Items:[],Size:7}}");
  tag.putLong("energy",eu);tag.putLong("scex_energy_fraction",fraction);tag.putInt("Scrap",scrap);m(w,i).loadAdditional(tag,w.registryAccess());
  if(count>0)amplifier(w,i,false,count);if(fluid>0)fill(w,i,fluid);
 }
 private void ledger(ServerLevel w,int i,EnergyAmount work,EnergyAmount credit,EnergyAmount pending){
  var tag=m(w,i).saveWithoutMetadata(w.registryAccess());var s=tag.getCompound("scex_matter");s.putInt("version",1);
  for(var row:Map.of("work",work,"amplifier",credit).entrySet()){var t=new CompoundTag();t.putLong("whole",row.getValue().whole());t.putLong("fraction",row.getValue().fraction());s.put(row.getKey(),t);}
  tag.putLong("energy",pending.whole());tag.putLong("scex_energy_fraction",pending.fraction());m(w,i).loadAdditional(tag,w.registryAccess());
 }
 private Map<String,Object> row(ServerLevel w,int i){
  var m=m(w,i);var r=new LinkedHashMap<String,Object>();r.put("index",i);r.put("work",m.getFabricationProgress());r.put("pending",m.getEnergyStorageInternal().scexExactAmount());r.put("amplifier",m.getAmplifierCredit());r.put("uu",m.getUuMatterAmount());r.put("stock",m.getItemHandler().getStackInSlot(0).getCount());r.put("output",m.getItemHandler().getStackInSlot(1).saveOptional(w.registryAccess()).toString());r.put("container",m.getItemHandler().getStackInSlot(2).saveOptional(w.registryAccess()).toString());r.put("held",m.hasUnmappedState());
  if(i<5||i==10||i==11)r.put("source",source(w,i).getEnergyStorageInternal().scexExactAmount());return r;
 }
 private List<Map<String,Object>> rows(ServerLevel w){var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<CASES;i++)rows.add(row(w,i));return rows;}
 private void conservation(ServerLevel w,int i){
  var m=m(w,i);long per=i==0?0:i<3?5000:45000;long unused=m.getItemHandler().getStackInSlot(0).getCount()*per;
  var consumed=EnergyAmount.of(CREDIT[i]-unused).subtract(m.getAmplifierCredit());
  var expected=EnergyAmount.of(4000000).subtract(source(w,i).getEnergyStorageInternal().scexExactAmount());for(int n=0;n<5;n++)expected=expected.add(consumed);
  var actual=m.getFabricationProgress().add(m.getEnergyStorageInternal().scexExactAmount()).add(EnergyAmount.of(m.getUuMatterAmount()*1000000L));
  check(expected.equals(actual),"chain "+i+" exact received EU + five amplifier gains = UU + work + pending");
 }
 private void compareCheckpoint(ServerLevel w)throws Exception{
  var e=JsonParser.parseString(Files.readString(Path.of("world/scex-matter-checkpoint.json"))).getAsJsonObject();
  var actual=JsonParser.parseString(new Gson().toJson(rows(w)));
  Files.writeString(Path.of("matter-checkpoint-comparison.json"),new Gson().toJson(Map.of("expected",e.get("rows"),"actual",actual)));
  check(actual.equals(e.get("rows")),"cold/idle exact saved balances and inventory");
 }
 public Map<String,Object> inspect(ServerLevel w,int tick)throws Exception{
  if(restart){
   if(tick==20){compareCheckpoint(w);
    var pausedFe=w.getCapability(Capabilities.EnergyStorage.BLOCK,at(0),Direction.NORTH);
    check(pausedFe!=null&&pausedFe.receiveEnergy(1,false)==0,"redstone blocks real FE input");
    for(int i=14;i<=16;i++){var heldFe=w.getCapability(Capabilities.EnergyStorage.BLOCK,at(i),Direction.NORTH);check(heldFe!=null&&heldFe.receiveEnergy(1,false)==0,"held save blocks real FE input");}
    var raw=m(w,15).saveWithoutMetadata(w.registryAccess()).getCompound("scex_matter").getCompound("hold");check(raw.getLong("energy")==-9,"negative original amount retained across cold JVM");
    check(m(w,14).saveWithoutMetadata(w.registryAccess()).getCompound("scex_matter").getCompound("hold").getString("opaque").equals("future balance"),"unknown future payload retained across cold JVM");
    for(int i=0;i<CASES;i++)w.removeBlock(at(i).west(),false);groups.add("cold-jvm-all-exact-ledgers-and-inventories");}
   if(tick==25){var fe=w.getCapability(Capabilities.EnergyStorage.BLOCK,at(21),Direction.NORTH);check(fe!=null&&fe.receiveEnergy(3,true)==3&&m(w,21).getEnergyStorageInternal().scexExactAmount().isZero(),"three FE simulation no mutation");check(fe.receiveEnergy(3,false)==3,"actual three FE input");}
   if(tick==40){
    check(m(w,19).getFabricationProgress().equals(EnergyAmount.fromDouble(1.5))&&m(w,19).getAmplifierCredit().equals(EnergyAmount.fromDouble(4999.75))&&m(w,19).getEnergyStorageInternal().scexExactAmount().isZero(),"pending quarter EU amplified exactly once after restart");
    check(m(w,20).getFabricationProgress().equals(EnergyAmount.fromDouble(1.5))&&m(w,20).getAmplifierCredit().equals(EnergyAmount.fromDouble(4999.75)),"already processed quarter EU not amplified again");
    for(int i=14;i<=16;i++)check(m(w,i).hasUnmappedState(),"future/invalid hold survives JVM");groups.add("pending-versus-paid-fractions-and-held-migration");
    check(m(w,21).getFabricationProgress().equals(EnergyAmount.fromDouble(.75)),"actual FE input gives exact three-quarter manufacturing progress");
    ledger(w,21,EnergyAmount.ZERO,EnergyAmount.of(5000),EnergyAmount.ZERO);
   }
   if(tick==45){var fe=w.getCapability(Capabilities.EnergyStorage.BLOCK,at(21),Direction.NORTH);check(fe.receiveEnergy(1,false)==1,"actual single FE amplified input");}
   if(tick==60){check(m(w,21).getFabricationProgress().equals(EnergyAmount.fromDouble(1.5))&&m(w,21).getAmplifierCredit().equals(EnergyAmount.fromDouble(4999.75)),"actual one FE gives exact sixfold progress and quarter credit debit");groups.add("actual-fe-fractions-simulation-pause-and-hold");}
   if(tick==240){for(int i=0;i<5;i++){conservation(w,i);check(m(w,i).getUuMatterAmount()>0,"natural source resumes after cold restart");}groups.add("natural-mfe-resume-conservation");return finish(w,tick);}return null;
  }
  if(tick==20){
   for(int i=0;i<CASES;i++){place(w,at(i),"producer/block_matter_elc",SIDES[i%4]);check(m(w,i).getEnergyStorageInternal().scexNetworkControlled(),"independent owner");}
   for(int i:new int[]{0,1,2,3,4,10,11}){place(w,at(i).above(),"wiring/block_mfe",Direction.DOWN);source(w,i).getEnergyStorageInternal().setEnergy(4000000);}
   amplifier(w,1,false,1);amplifier(w,2,false,16);amplifier(w,3,true,1);amplifier(w,4,true,16);amplifier(w,10,false,16);amplifier(w,11,false,16);
   old(w,5,137,EnergyAmount.UNITS/4,0,2,7);old(w,6,1000000,0,0,16,0);old(w,7,0,0,0,1,0);old(w,8,1000000,0,0,0,8000);old(w,9,1000000,0,0,0,7999);
   for(int i=12;i<=13;i++){fill(w,i,1000);var input=new ItemStack(i==12?mio_icif_cells.CELL_EMPTY.get():Items.BUCKET);input.set(DataComponents.CUSTOM_NAME,Component.literal("R115 named container"));check(m(w,i).getItemHandler().insertItem(2,input,false).isEmpty(),"normal container insertion");}
   ((ItemStackHandler)m(w,13).getItemHandler()).setStackInSlot(1,new ItemStack(Items.COBBLESTONE,64));
   for(int i=14;i<=16;i++){var tag=m(w,i).saveWithoutMetadata(w.registryAccess());if(i==14){tag.getCompound("scex_matter").putInt("version",99);tag.getCompound("scex_matter").putString("opaque","future balance");}if(i==15)tag.putLong("energy",-9);if(i==16)tag.putLong("scex_energy_fraction",EnergyAmount.UNITS);m(w,i).loadAdditional(tag,w.registryAccess());check(m(w,i).hasUnmappedState(),"future and invalid ledgers held");}
   ledger(w,17,EnergyAmount.fromDouble(999999.25),EnergyAmount.ZERO,EnergyAmount.fromDouble(.75));
   ledger(w,18,EnergyAmount.ZERO,EnergyAmount.fromDouble(.25),EnergyAmount.fromDouble(.5));
   fill(w,21,17);removedPort=w.getCapability(Capabilities.FluidHandler.BLOCK,at(21),Direction.NORTH);
   check(removedPort!=null&&removedPort.fill(new FluidStack(mio_icif_fluids.UUMATTER.get(),1),IFluidHandler.FluidAction.EXECUTE)==0,"registered external fluid capability is output only");
  }
  if(tick>20&&tick<2290){for(int i=0;i<5;i++){if(m(w,i).getUuMatterAmount()>0)firstUu.putIfAbsent(i,tick);if(tick%40==0)conservation(w,i);}}
  if(tick==60){
   check(m(w,5).getFabricationProgress().equals(EnergyAmount.fromDouble(137.25))&&m(w,5).getScrap()==10000&&m(w,5).getUuMatterAmount()==7,"legacy fractional balance retained without reamplification");
   check(m(w,6).getUuMatterAmount()==1&&m(w,6).getFabricationProgress().isZero()&&m(w,6).getScrap()==5000&&m(w,6).getItemHandler().getStackInSlot(0).getCount()==15,"one-million pulse preloads one scrap before output");
   check(m(w,7).getScrap()==0&&m(w,7).getItemHandler().getStackInSlot(0).getCount()==1,"zero progress idle consumes no scrap");
   check(m(w,8).getFabricationProgress().equals(EnergyAmount.of(1000000))&&m(w,8).getUuMatterAmount()==8000&&m(w,8).getEffectiveCapacity()==0,"full tank retains paid work and refuses input");
   check(m(w,9).getUuMatterAmount()==8000&&m(w,9).getFabricationProgress().isZero(),"one free mB accepts one output");
   check(m(w,17).getUuMatterAmount()==1&&m(w,17).getFabricationProgress().isZero(),"three-quarter EU completes exact one mB");
   check(m(w,18).getFabricationProgress().equals(EnergyAmount.fromDouble(1.75))&&m(w,18).getAmplifierCredit().isZero(),"partial amplifier applies to exact eligible fraction only");
   var cell=m(w,12).getItemHandler().getStackInSlot(1);check(m(w,12).getUuMatterAmount()==0&&mio_icif_cells.getCellFluid(cell).getAmount()==1000&&Component.literal("R115 named container").equals(cell.get(DataComponents.CUSTOM_NAME)),"whole cell and custom name preserved");
   check(m(w,13).getUuMatterAmount()==1000&&m(w,13).getItemHandler().getStackInSlot(2).is(Items.BUCKET),"blocked output does not consume container or fluid");
   ((ItemStackHandler)m(w,13).getItemHandler()).setStackInSlot(1,ItemStack.EMPTY);
   groups.add("legacy-preload-idle-full-and-fraction-controls");groups.add("whole-container-and-components");
  }
  if(tick==80){
   w.setBlockAndUpdate(at(10).west(),Blocks.REDSTONE_BLOCK.defaultBlockState());source(w,11).getEnergyStorageInternal().setEnergy(0);
   var bucket=m(w,13).getItemHandler().getStackInSlot(1);check(m(w,13).getUuMatterAmount()==0&&bucket.is(mio_icif_fluids.UUMATTER.get().getBucket())&&Component.literal("R115 named container").equals(bucket.get(DataComponents.CUSTOM_NAME)),"unblocked named bucket exact fill");
   check(removedPort.drain(3,IFluidHandler.FluidAction.SIMULATE).getAmount()==3&&m(w,21).getUuMatterAmount()==17,"fluid simulation no mutation");
   check(removedPort.drain(3,IFluidHandler.FluidAction.EXECUTE).getAmount()==3&&m(w,21).getUuMatterAmount()==14,"fluid actual drain exact");
   w.removeBlock(at(21),false);check(removedPort.drain(1,IFluidHandler.FluidAction.EXECUTE).isEmpty(),"cached removed capability rejected");place(w,at(21),"producer/block_matter_elc",Direction.NORTH);groups.add("output-port-simulation-and-removal");
  }
  if(tick==85)paused=List.of(row(w,10),row(w,11));
  if(tick==145){check(paused.equals(List.of(row(w,10),row(w,11))),"redstone input/work pause and power-cut no repeated amplification");w.removeBlock(at(10).west(),false);groups.add("redstone-and-power-cut-stability");}
  if(tick==180)check(!paused.getFirst().equals(row(w,10)),"redstone resumes natural input");
  if(tick==2290){
   check(firstUu.size()==5,"all five observed natural amplifier paths output");
   check(firstUu.get(4)<firstUu.get(2)&&firstUu.get(2)<firstUu.get(3)&&firstUu.get(3)<firstUu.get(1)&&firstUu.get(1)<firstUu.get(0),"observed scrap/box completion order");
   for(int i=0;i<5;i++)conservation(w,i);groups.add("five-natural-mfe-paths-and-exact-input-amplifier-ledger");
   for(int i=0;i<CASES;i++)w.setBlockAndUpdate(at(i).west(),Blocks.REDSTONE_BLOCK.defaultBlockState());
  }
  if(tick==2295){
   ledger(w,19,EnergyAmount.ZERO,EnergyAmount.of(5000),EnergyAmount.fromDouble(.25));
   ledger(w,20,EnergyAmount.fromDouble(1.5),EnergyAmount.fromDouble(4999.75),EnergyAmount.ZERO);
   Files.writeString(Path.of("world/scex-matter-checkpoint.json"),new Gson().toJson(Map.of("rows",rows(w),"first_uu",firstUu)));
  }
  if(tick==2300){compareCheckpoint(w);groups.add("paused-exact-cold-restart-checkpoint");return finish(w,tick);}return null;
 }
 private Map<String,Object> finish(ServerLevel w,int tick)throws Exception{var r=Map.<String,Object>of("passed",true,"assertions",assertions,"groups",groups,"phase",restart?"restart":"initial","cases",CASES,"ticks",tick,"rows",rows(w),"first_uu",firstUu);Files.writeString(Path.of("matter-world-result.json"),new Gson().toJson(r));return r;}
}
