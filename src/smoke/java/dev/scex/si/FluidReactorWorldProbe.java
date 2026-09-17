// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;
import com.google.gson.*;
import com.singularity_iteration.mio_icif.Blocks.entity.generator.mio_icif_nuclear_reactor_generator;
import com.singularity_iteration.mio_icif.Blocks.entity.reactor.mio_icif_reactor_fluid_port;
import com.singularity_iteration.mio_icif.Blocks.mio_icif_blocks;
import com.singularity_iteration.mio_icif.Blocks.Environment.fluid.mio_icif_fluids;
import com.singularity_iteration.mio_icif.Items.DataComponent.FuelRodDurability;
import com.singularity_iteration.mio_icif.Items.DataComponent.ReactorComponentData;
import com.singularity_iteration.mio_icif.Items.Normal.mio_icif_data_components;
import com.singularity_iteration.mio_icif.Items.Cell.mio_icif_cells;
import dev.scex.si.reactor.ReactorCycle;
import dev.scex.si.reactor.ReactorInventory;
import dev.scex.si.reactor.FluidReactorCycle;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Actual registered shells and native fluid ports, compared to frozen normal reference saves. */
public final class FluidReactorWorldProbe {
 private final JsonArray cases;private final boolean restart;private int assertions;private final List<String> groups=new ArrayList<>();
 private long initialTime;private final List<net.minecraft.nbt.CompoundTag> coldStart=new ArrayList<>();
 private IFluidHandler cachedPort;private mio_icif_reactor_fluid_port removedPort;
 public FluidReactorWorldProbe(net.minecraft.server.MinecraftServer server)throws Exception{
  var cfg=JsonParser.parseString(Files.readString(Path.of("fluid-reactor-world.json"))).getAsJsonObject();cases=cfg.getAsJsonArray("cases");restart=cfg.get("phase").getAsString().equals("restart");
  if(restart){var w=server.overworld();initialTime=w.getGameTime();
   for(int i=0;i<cases.size();i++){var m=m(w,i);check(m!=null,"real core restored before first server tick");coldStart.add(m.saveWithoutMetadata(w.registryAccess()));}
   var expected=JsonParser.parseString(Files.readString(Path.of("world/scex-fluid-reactor-checkpoint.json"))).getAsJsonObject().get("rows");var actual=JsonParser.parseString(new Gson().toJson(rows(w)));
   Files.writeString(Path.of("fluid-reactor-cold-comparison.json"),new Gson().toJson(Map.of("expected",expected,"actual",actual,"game_time",initialTime,"phase","ServerStarted before first server tick")));
   check(actual.equals(expected),"exact saved fluids, component wear, metadata and invalid holds before natural cooling resumes");groups.add("new-jvm-exact-state-before-first-server-tick");
  }
 }
 private void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError("R120 fluid reactor: "+why);}
 private BlockPos at(int i){return new BlockPos(12000+i*16,80,100);}
 private mio_icif_nuclear_reactor_generator m(ServerLevel w,int i){return (mio_icif_nuclear_reactor_generator)w.getBlockEntity(at(i));}
 private ItemStack item(String suffix){return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:reactor/item_reactor_"+suffix)));}
 private void shell(ServerLevel w,int i){var p=at(i);
  for(int x=-2;x<=2;x++)for(int y=-2;y<=2;y++)for(int z=-2;z<=2;z++)
   w.setBlockAndUpdate(p.offset(x,y,z),Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.abs(z))==2?mio_icif_blocks.REACTOR_VESSEL.get().defaultBlockState():Blocks.AIR.defaultBlockState());
  w.setBlockAndUpdate(p,mio_icif_blocks.NUCLEAR_REACTOR_GENERATOR.get().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST}[i%4]));
  for(var side:Direction.values())w.setBlockAndUpdate(p.relative(side),mio_icif_blocks.REACTOR_CHAMBER.get().defaultBlockState());
  w.setBlockAndUpdate(p.east(2),mio_icif_blocks.REACTOR_REDSTONE_PORT.get().defaultBlockState());w.setBlockAndUpdate(p.south(2),mio_icif_blocks.REACTOR_FLUID_PORT.get().defaultBlockState());w.setBlockAndUpdate(p.west(2),mio_icif_blocks.REACTOR_ACCESS_HATCH.get().defaultBlockState());
 }
 private void seed(ServerLevel w,int i){var c=cases.get(i).getAsJsonObject();var m=m(w,i);m.getHeatStorage().setHeat(c.get("heat").getAsLong());
  for(var e0:c.getAsJsonArray("items")){var e=e0.getAsJsonObject();var stack=item(e.get("si").getAsString());int wear=e.get("wear").getAsInt();var f=stack.get(mio_icif_data_components.FUEL_ROD_DURABILITY.get());var h=stack.get(mio_icif_data_components.REACTOR_COMPONENT_DATA.get());
   if(f!=null)stack.set(mio_icif_data_components.FUEL_ROD_DURABILITY.get(),new FuelRodDurability(f.maxUses()-wear,f.maxUses(),0));
   if(h!=null)stack.set(mio_icif_data_components.REACTOR_COMPONENT_DATA.get(),new ReactorComponentData(wear,h.maxValue()));m.setItem(e.get("slot").getAsInt(),stack);
  }
  m.getFluidHandler().commitCycle(c.get("cold").getAsInt(),c.get("hot").getAsInt(),0);
  var tag=m.saveWithoutMetadata(w.registryAccess());tag.putInt("ReactorCycleTicks",c.get("counter").getAsInt());m.loadAdditional(tag,w.registryAccess());
 }
 private List<Map<String,Object>> rows(ServerLevel w){var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<cases.size();i++){var m=m(w,i);var tag=m.saveWithoutMetadata(w.registryAccess());rows.add(Map.of("index",i,"heat",m.getCurrentHeat(),"cold",m.getInputFluidAmount(),"hot",m.getOutputFluidAmount(),"items",tag.get("Items").toString(),"fluid",tag.get("FluidHandler").toString(),"mode",m.getReactorMode().getName(),"failure",m.getReactorFailure()));}return rows;}
 private void compare(ServerLevel w,int i){var c=cases.get(i).getAsJsonObject();var m=m(w,i);String name=c.get("label").getAsString();
  check(m.getReactorFailure().isEmpty(),name+" failure "+m.getReactorFailure());check(m.isValidFluidReactorStructure(),name+" actual valid shell");
  check(m.getCurrentHeat()==c.get("expected_heat").getAsLong(),name+" heat "+m.getCurrentHeat()+" expected "+c.get("expected_heat"));
  check(m.getInputFluidAmount()==c.get("expected_cold").getAsInt(),name+" cold "+m.getInputFluidAmount());check(m.getOutputFluidAmount()==c.get("expected_hot").getAsInt(),name+" hot "+m.getOutputFluidAmount());
  check(m.getExactEnergyGeneration()==0&&m.ownedEnergy().scexExactAmount().isZero()&&m.outputFaces()==0,name+" no fluid EU");
  int count=0;
  for(var e0:c.getAsJsonArray("expected_items")){var e=e0.getAsJsonObject();var stack=m.getItem(e.get("slot").getAsInt());check(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().endsWith("item_reactor_"+e.get("si").getAsString()),name+" identity");var f=stack.get(mio_icif_data_components.FUEL_ROD_DURABILITY.get());var h=stack.get(mio_icif_data_components.REACTOR_COMPONENT_DATA.get());int wear=f!=null?f.maxUses()-f.remainingUses():h!=null?h.storedValue():0;check(wear==e.get("wear").getAsInt(),name+" wear "+wear);count++;}
  int actual=0;for(int slot=0;slot<54;slot++)if(!m.getItem(slot).isEmpty())actual++;check(actual==count,name+" removed components");
  var data=m.getFluidContainerData();check(data.getCount()==5&&data.get(0)==m.getCurrentHeat()&&data.get(1)==m.getMaxHeat()&&data.get(3)==m.getInputFluidAmount()&&data.get(4)==m.getOutputFluidAmount(),name+" fluid menu data");
 }
 private void checkColdCycles(ServerLevel w,int i,net.minecraft.nbt.CompoundTag before,int cycles,boolean enabled)throws Exception{
  var m=m(w,i);var savedItems=before.getCompound("Items").getList("Items",net.minecraft.nbt.Tag.TAG_COMPOUND);ItemStack[] expected=new ItemStack[54];Arrays.fill(expected,ItemStack.EMPTY);
  for(int n=0;n<savedItems.size();n++){var row=savedItems.getCompound(n);expected[row.getInt("Slot")]=ItemStack.parse(w.registryAccess(),row.getCompound("Item")).orElseThrow();}
  long heat=before.getLong("HeatStored");var state=before.getCompound("FluidHandler");int cold=state.getCompound("InputTank").getCompound("Fluid").getInt("amount"),hot=state.getCompound("OutputTank").getCompound("Fluid").getInt("amount");
  if(!state.getCompound("scex_fluid_hold").isEmpty())cycles=0;
  for(int n=0;n<cycles;n++){var parts=new ReactorCycle.Part[54];for(int slot=0;slot<54;slot++)parts[slot]=ReactorInventory.read(expected[slot]);var result=FluidReactorCycle.step(parts,9,heat,enabled,cold,hot,10000);var next=result.cycle().parts();var depleted=result.cycle().depletedFuel();for(int slot=0;slot<54;slot++)expected[slot]=ReactorInventory.write(expected[slot],parts[slot],next[slot],depleted[slot]);heat=result.cycle().hullHeat();cold=result.coolant();hot=result.hotCoolant();}
  check(m.getCurrentHeat()==heat&&m.getInputFluidAmount()==cold&&m.getOutputFluidAmount()==hot,"cold natural cycle heat/tanks "+i+" cycles "+cycles);
  for(int slot=0;slot<54;slot++)check(ItemStack.matches(expected[slot],m.getItem(slot)),"cold exact inventory "+i+":"+slot);
 }
 private Map<String,Object> finish(ServerLevel w)throws Exception{var result=new LinkedHashMap<String,Object>();result.put("passed",true);result.put("phase",restart?"restart":"initial");result.put("cases",cases.size());result.put("groups",groups);result.put("assertions",assertions);result.put("rows",rows(w));Files.writeString(Path.of("fluid-reactor-world-result.json"),new Gson().toJson(result));return result;}
 public Map<String,Object> inspect(ServerLevel w,int tick)throws Exception{
  if(restart){
   if(tick==20){int elapsed=Math.toIntExact(w.getGameTime()-initialTime);check(elapsed>=20&&elapsed<=22,"bounded natural server ticks");
    for(int i=0;i<cases.size();i++){var old=coldStart.get(i);int counter=old.getInt("ReactorCycleTicks");checkColdCycles(w,i,old,(elapsed+19-counter)/20,false);}
    groups.add("loaded-passive-cooling-matches-elapsed-ticks");coldStart.clear();
    for(int i=0;i<3;i++){coldStart.add(m(w,i).saveWithoutMetadata(w.registryAccess()));w.setBlockAndUpdate(at(i).east(3),Blocks.REDSTONE_BLOCK.defaultBlockState());}
   }
   if(tick==80){for(int i=0;i<3;i++)checkColdCycles(w,i,coldStart.get(i),3,true);check(m(w,1).getFluidHandler().hasHold(),"wrong-fluid pause survives cold restart");groups.add("cold-natural-fluid-restart-and-retained-invalid-state");}
   if(tick==90){var loader=mio_icif_nuclear_reactor_generator.class.getClassLoader();
    for(String suffix:new String[]{"2","3"}){String name="com.singularity_iteration.mio_icif.Blocks.entity.generator.mio_icif_fluid_reactor_handler$"+suffix;check(loader.getResource(name.replace('.','/')+".class")==null,"old fluid class absent");try{Class.forName(name,false,loader);throw new AssertionError("Old fluid class loadable");}catch(ClassNotFoundException expected){assertions++;}}
    groups.add("retired-fluid-inner-classes-absent");return finish(w);
   }return null;
  }
  if(tick==20){for(int i=0;i<cases.size();i++){shell(w,i);seed(w,i);}groups.add("four-facing-actual-shells-and-six-chambers");}
  if(tick==40)for(int i=0;i<cases.size();i++)w.setBlockAndUpdate(at(i).east(3),Blocks.REDSTONE_BLOCK.defaultBlockState());
  if(tick==240)for(int i=0;i<cases.size();i++)w.removeBlock(at(i).east(3),false);
  if(tick==359){for(int i=0;i<cases.size();i++)compare(w,i);groups.add("twenty-one-normal-reference-fluid-and-component-paths");}
  if(tick==360){var m=m(w,0);cachedPort=w.getCapability(Capabilities.FluidHandler.BLOCK,at(0).south(2),Direction.SOUTH);check(cachedPort!=null,"registered fluid port");
   check(cachedPort.fill(new FluidStack(mio_icif_fluids.COOLANT.get(),1),IFluidHandler.FluidAction.SIMULATE)==0,"full tank simulation");
   check(cachedPort.drain(1000,IFluidHandler.FluidAction.EXECUTE).isEmpty(),"cold fluid cannot drain");
   m.getFluidHandler().getInputTank().setFluid(new FluidStack(mio_icif_fluids.COOLANT.get(),9980));check(cachedPort.fill(new FluidStack(mio_icif_fluids.COOLANT.get(),5),IFluidHandler.FluidAction.SIMULATE)==5&&m.getInputFluidAmount()==9980,"simulation retains exact input");
   check(cachedPort.fill(new FluidStack(mio_icif_fluids.COOLANT.get(),5),IFluidHandler.FluidAction.EXECUTE)==5&&m.getInputFluidAmount()==9985,"native coolant admission");
   m.getFluidHandler().getOutputTank().setFluid(new FluidStack(mio_icif_fluids.HOTCOOLANT.get(),20));check(cachedPort.drain(7,IFluidHandler.FluidAction.EXECUTE).getAmount()==7&&m.getOutputFluidAmount()==13,"native hot output");
   w.removeBlock(at(0).offset(-2,-2,-2),false);check(cachedPort.fill(new FluidStack(mio_icif_fluids.COOLANT.get(),1),IFluidHandler.FluidAction.EXECUTE)==0&&cachedPort.drain(1,IFluidHandler.FluidAction.EXECUTE).isEmpty(),"broken shell cached port denies immediately");
   w.setBlockAndUpdate(at(0).offset(-2,-2,-2),mio_icif_blocks.REACTOR_VESSEL.get().defaultBlockState());groups.add("native-fluid-port-simulation-and-broken-shell");
  }
  if(tick==380){var m=m(w,0);check(m.isValidFluidReactorStructure(),"shell restored naturally");removedPort=(mio_icif_reactor_fluid_port)w.getBlockEntity(at(0).south(2));w.removeBlock(at(0).south(2),false);
   check(removedPort.fill(new FluidStack(mio_icif_fluids.COOLANT.get(),1),IFluidHandler.FluidAction.EXECUTE)==0,"removed port cannot mutate live core");
   w.setBlockAndUpdate(at(0).south(2),mio_icif_blocks.REACTOR_FLUID_PORT.get().defaultBlockState());groups.add("removed-fluid-port-lifecycle");
  }
  if(tick==400){var m=m(w,0);check(m.isValidFluidReactorStructure(),"replacement port reformed");m.getFluidHandler().commitCycle(0,2000,0);
   var bucket=new ItemStack(mio_icif_fluids.COOLANT_BUCKET.get());bucket.set(DataComponents.CUSTOM_NAME,Component.literal("cold receipt"));m.getFluidHandler().setStackInSlot(0,bucket);
   var empty=new ItemStack(Items.BUCKET);empty.set(DataComponents.CUSTOM_NAME,Component.literal("hot receipt"));m.getFluidHandler().setStackInSlot(2,empty);
  }
  if(tick==405){var f=m(w,0).getFluidHandler();check(f.getInputFluidAmount()==1000&&f.getOutputFluidAmount()==1000,"whole bucket fluid accounting");check(f.getStackInSlot(0).isEmpty()&&f.getStackInSlot(2).isEmpty(),"containers debited once");check(f.getStackInSlot(1).is(Items.BUCKET)&&f.getStackInSlot(1).getHoverName().getString().equals("cold receipt"),"cold bucket metadata");check(f.getStackInSlot(3).is(mio_icif_fluids.HOTCOOLANT_BUCKET.get())&&f.getStackInSlot(3).getHoverName().getString().equals("hot receipt"),"hot bucket metadata");groups.add("component-preserving-whole-container-transactions");
   f.setStackInSlot(1,ItemStack.EMPTY);f.setStackInSlot(3,ItemStack.EMPTY);
   var dynamic=mio_icif_cells.createDynamicFilledCell(mio_icif_fluids.COOLANT.get(),125);dynamic.set(DataComponents.CUSTOM_NAME,Component.literal("partial coolant"));f.setStackInSlot(0,dynamic);
   var cell=new ItemStack(mio_icif_cells.CELL_EMPTY.get());cell.set(DataComponents.CUSTOM_NAME,Component.literal("hot cell"));f.setStackInSlot(2,cell);
  }
  if(tick==407){var f=m(w,0).getFluidHandler();check(f.getInputFluidAmount()==1125&&f.getOutputFluidAmount()==0,"partial dynamic and full output cell conservation");check(mio_icif_cells.isEmptyCell(f.getStackInSlot(1))&&f.getStackInSlot(1).getHoverName().getString().equals("partial coolant"),"only dynamic fluid metadata cleared");check(mio_icif_cells.getCellFluid(f.getStackInSlot(3)).getAmount()==1000&&f.getStackInSlot(3).getHoverName().getString().equals("hot cell"),"filled cell metadata retained");
   var bad=m(w,1).saveWithoutMetadata(w.registryAccess());var wrongTank=new net.neoforged.neoforge.fluids.capability.templates.FluidTank(10000);wrongTank.setFluid(new FluidStack(net.minecraft.world.level.material.Fluids.WATER,13));bad.getCompound("FluidHandler").put("InputTank",wrongTank.writeToNBT(w.registryAccess(),new net.minecraft.nbt.CompoundTag()));
   m(w,1).loadAdditional(bad,w.registryAccess());var once=m(w,1).saveWithoutMetadata(w.registryAccess());m(w,1).loadAdditional(once,w.registryAccess());var twice=m(w,1).saveWithoutMetadata(w.registryAccess());
   check(m(w,1).getFluidHandler().hasHold(),"wrong saved fluid held");check(once.getCompound("FluidHandler").getCompound("scex_fluid_hold").equals(twice.getCompound("FluidHandler").getCompound("scex_fluid_hold")),"repeated invalid state load has stable retained payload");groups.add("partial-cells-and-stable-invalid-fluid-preservation");
  }
  if(tick==409){var rows=rows(w);Files.writeString(Path.of("world/scex-fluid-reactor-checkpoint.json"),new Gson().toJson(Map.of("rows",rows,"game_time",w.getGameTime())));groups.add("normal-fluid-world-checkpoint");return finish(w);}
  return null;
 }
}
