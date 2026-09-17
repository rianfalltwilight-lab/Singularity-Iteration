// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;
import com.google.gson.*;
import com.singularity_iteration.mio_icif.Blocks.entity.generator.mio_icif_nuclear_reactor_generator;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Container;
import com.singularity_iteration.mio_icif.Items.DataComponent.FuelRodDurability;
import com.singularity_iteration.mio_icif.Items.DataComponent.ReactorComponentData;
import com.singularity_iteration.mio_icif.Items.Normal.mio_icif_data_components;
import dev.scex.energy.EnergyAmount;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.neoforged.neoforge.items.IItemHandler;

/** Real registered inventories, generation, redstone, save states and native receiver chains. */
public final class ReactorWorldProbe {
 private final JsonArray cases;private final boolean restart;private int assertions;
 private final List<String> groups=new ArrayList<>();private final List<Map<String,Object>> snapshots=new ArrayList<>();
 private IItemHandler removedPort;private long startTime;private final Map<Integer,EnergyAmount> detached=new HashMap<>();
 private final long[] coldHeat=new long[3];private final int[] coldFuel=new int[3];private final EnergyAmount[] coldSink=new EnergyAmount[3];
 private EnergyAmount legacyTotal;
 public ReactorWorldProbe()throws Exception{var cfg=JsonParser.parseString(Files.readString(Path.of("reactor-world.json"))).getAsJsonObject();cases=cfg.getAsJsonArray("cases");restart=cfg.get("phase").getAsString().equals("restart");}
 private void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError("R118 reactor: "+why);}
 private BlockPos at(int i){return new BlockPos(8000+i*8,80,100);}
 private mio_icif_nuclear_reactor_generator m(ServerLevel w,int i){return (mio_icif_nuclear_reactor_generator)w.getBlockEntity(at(i));}
 private mio_icif_Energy_Container sink(ServerLevel w,int i){return (mio_icif_Energy_Container)w.getBlockEntity(at(i).below());}
 private void place(ServerLevel w,BlockPos p,String id,Direction face){var b=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:"+id));var prop=(DirectionProperty)b.getStateDefinition().getProperty("facing");check(prop!=null&&prop.getPossibleValues().contains(face),"valid facing");check(w.setBlockAndUpdate(p,b.defaultBlockState().setValue(prop,face)),"actual factory "+id);}
 private ItemStack item(String suffix){return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:reactor/item_reactor_"+suffix)));}
 private void seed(mio_icif_nuclear_reactor_generator m,JsonObject c){
  m.getHeatStorage().setHeat(c.get("initial_heat").getAsLong());
  for(var value:c.getAsJsonArray("entries")){var e=value.getAsJsonObject();var stack=item(e.get("si").getAsString());int wear=e.get("wear").getAsInt();
   var f=stack.get(mio_icif_data_components.FUEL_ROD_DURABILITY.get());var h=stack.get(mio_icif_data_components.REACTOR_COMPONENT_DATA.get());
   if(f!=null)stack.set(mio_icif_data_components.FUEL_ROD_DURABILITY.get(),new FuelRodDurability(f.maxUses()-wear,f.maxUses(),0));
   if(h!=null)stack.set(mio_icif_data_components.REACTOR_COMPONENT_DATA.get(),new ReactorComponentData(wear,h.maxValue()));
   m.setItem(e.get("slot").getAsInt(),stack);
  }
 }
 private List<Map<String,Object>> rows(ServerLevel w){var out=new ArrayList<Map<String,Object>>();
  for(int i=0;i<cases.size();i++){var m=m(w,i);var tag=m.saveWithoutMetadata(w.registryAccess());var r=new LinkedHashMap<String,Object>();r.put("index",i);r.put("hull",m.getCurrentHeat());r.put("max_heat",m.getMaxHeat());r.put("inventory",tag.get("Items").toString());r.put("rate",m.getExactEnergyGeneration());r.put("owned",m.ownedEnergy().scexExactAmount());r.put("sink",sink(w,i).getEnergyStorageInternal().scexExactAmount());r.put("hold",m.hasLegacyHold());r.put("failure",m.getReactorFailure());out.add(r);}return out;}
 private void compareReference(ServerLevel w,int i){var c=cases.get(i).getAsJsonObject();var m=m(w,i);
  check(m.getReactorFailure().isEmpty(),c.get("label")+" failure="+m.getReactorFailure());
  check(m.getCurrentHeat()==c.get("expected_heat").getAsLong(),c.get("label")+" actual hull "+m.getCurrentHeat()+" expected "+c.get("expected_heat"));
  for(var value:c.getAsJsonArray("expected_items")){var e=value.getAsJsonObject();var stack=m.getItem(e.get("slot").getAsInt());
   check(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals("mio_icif:reactor/item_reactor_"+e.get("si").getAsString()),"real output identity "+c.get("label"));
   if(e.has("wear")){var fuel=stack.get(mio_icif_data_components.FUEL_ROD_DURABILITY.get());var thermal=stack.get(mio_icif_data_components.REACTOR_COMPONENT_DATA.get());int wear=fuel!=null?fuel.maxUses()-fuel.remainingUses():thermal!=null?thermal.storedValue():0;check(wear==e.get("wear").getAsInt(),"actual component wear "+c.get("label")+" actual="+wear+" expected="+e.get("wear"));}
  }
 }
 public Map<String,Object> inspect(ServerLevel w,int tick)throws Exception{
  if(restart){
   if(tick==20){var checkpoint=JsonParser.parseString(Files.readString(Path.of("world/scex-reactor-checkpoint.json"))).getAsJsonObject();
    var actual=JsonParser.parseString(new Gson().toJson(rows(w)));Files.writeString(Path.of("reactor-cold-comparison.json"),new Gson().toJson(Map.of("expected",checkpoint.get("rows"),"actual",actual)));
    check(actual.equals(checkpoint.get("rows")),"cold exact idle fuel/heat/fraction/old inventories");
    var loader=mio_icif_nuclear_reactor_generator.class.getClassLoader();
    for(String suffix:new String[]{"2","3","HeatAcceptor","HeatAcceptorInfo","NuclearReactorItemHandler","VentType"}){
     String name=mio_icif_nuclear_reactor_generator.class.getName()+"$"+suffix;
     check(loader.getResource(name.replace('.','/')+".class")==null,"old reactor class resource absent");
     try{Class.forName(name,false,loader);throw new AssertionError("old reactor internal class loadable");}catch(ClassNotFoundException expected){assertions++;}
    }
    groups.add("retired-internal-classes-absent-from-actual-loader");
    for(int i=0;i<3;i++){coldHeat[i]=m(w,i).getCurrentHeat();coldFuel[i]=m(w,i).getItem(0).get(mio_icif_data_components.FUEL_ROD_DURABILITY.get()).remainingUses();coldSink[i]=sink(w,i).getEnergyStorageInternal().scexExactAmount();}
    for(int i=0;i<cases.size();i++)w.setBlockAndUpdate(at(i).west(),Blocks.REDSTONE_BLOCK.defaultBlockState());groups.add("new-jvm-exact-idle-ledgers-and-inventories");}
   if(tick==80){for(int i=0;i<cases.size();i++)check(!m(w,i).hasLegacyHold(),"known cold state resumed without migration hold");
    for(int i=0;i<3;i++){check(m(w,i).getCurrentHeat()==coldHeat[i]+3*new int[]{4,24,96}[i],"three natural cold heat cycles");check(m(w,i).getItem(0).get(mio_icif_data_components.FUEL_ROD_DURABILITY.get()).remainingUses()==coldFuel[i]-3,"three natural cold fuel debits");check(sink(w,i).getEnergyStorageInternal().scexExactAmount().compareTo(coldSink[i])>0,"real cold sink receives generation");}
    groups.add("cold-native-reactor-resume");}
   if(tick==90){
    w.removeBlock(at(0).west(),false);var old=m(w,0).saveWithoutMetadata(w.registryAccess());old.remove("scex_reactor");old.remove("ReactorItems");old.putLong("energy",137);old.putLong("scex_energy_fraction",EnergyAmount.UNITS/4);old.putInt("ReactorCycleTicks",19);
    var stack=item("uranium_simple");stack.set(mio_icif_data_components.FUEL_ROD_DURABILITY.get(),new FuelRodDurability(9000,10000,0));
    var inv=new CompoundTag();inv.putInt("Size",54);var list=new ListTag();var entry=new CompoundTag();entry.putInt("Slot",0);entry.put("Item",stack.save(w.registryAccess()));list.add(entry);inv.put("Items",list);old.put("Items",inv);
    legacyTotal=sink(w,0).getEnergyStorageInternal().scexExactAmount().add(EnergyAmount.fromDouble(137.25));m(w,0).loadAdditional(old,w.registryAccess());
    var future=m(w,1).saveWithoutMetadata(w.registryAccess());future.getCompound("scex_reactor").putInt("version",777);future.getCompound("scex_reactor").putString("opaque","retain future reactor");m(w,1).loadAdditional(future,w.registryAccess());
    var duplicate=m(w,2).saveWithoutMetadata(w.registryAccess());var entries=duplicate.getCompound("Items").getList("Items",Tag.TAG_COMPOUND);entries.add(entries.getCompound(0).copy());m(w,2).loadAdditional(duplicate,w.registryAccess());
   }
   if(tick==120){
    var f=m(w,0).getItem(0).get(mio_icif_data_components.FUEL_ROD_DURABILITY.get());check(f.remainingUses()==9000&&f.maxUses()==10000,"legacy remaining fuel preserved without refill");
    check(m(w,0).ownedEnergy().scexExactAmount().add(sink(w,0).getEnergyStorageInternal().scexExactAmount()).equals(legacyTotal),"legacy fractional EU conserved through native output");
    check(m(w,1).hasLegacyHold()&&m(w,1).saveWithoutMetadata(w.registryAccess()).getCompound("scex_reactor").getCompound("hold").getCompound("future_reactor").getString("opaque").equals("retain future reactor"),"future payload retained while stopped");
    check(m(w,2).hasLegacyHold()&&m(w,2).saveWithoutMetadata(w.registryAccess()).getCompound("scex_reactor").getCompound("hold").getCompound("invalid_inventory").getList("Items",Tag.TAG_COMPOUND).size()==2,"duplicate inventory raw entries retained");
    groups.add("legacy-cycle-fraction-and-unknown-inventory-preservation");return finish(w);
   }return null;
  }
  if(tick==20){startTime=w.getGameTime();
   Direction[] faces={Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST};
   for(int i=0;i<cases.size();i++){
    place(w,at(i),"generator/block_nuclear_reactor_generator",faces[i%4]);place(w,at(i).below(),"wiring/block_mfsu",Direction.DOWN);
    check(m(w,i).ownedEnergy().scexNetworkControlled(),"independent nuclear owner");seed(m(w,i),cases.get(i).getAsJsonObject());w.setBlockAndUpdate(at(i).west(),Blocks.REDSTONE_BLOCK.defaultBlockState());
   }
   groups.add("four-facing-actual-registry-inventories-and-independent-owners");
  }
  if(tick==221){
   for(int i=0;i<cases.size();i++){compareReference(w,i);w.removeBlock(at(i).west(),false);}groups.add("ten-natural-reactor-cycles-match-fixed-reference");
  }
  if(tick==260){
   for(int i=0;i<cases.size();i++){var c=cases.get(i).getAsJsonObject();var expected=EnergyAmount.fromDouble(c.get("expected_eu").getAsDouble());var actual=m(w,i).ownedEnergy().scexExactAmount().add(sink(w,i).getEnergyStorageInternal().scexExactAmount());
    check(actual.equals(expected),c.get("label")+" actual EU "+actual+" expected "+expected);check(!m(w,i).isRunning(),"redstone cycle stopped");}
   groups.add("complete-cycle-native-mfsu-exact-fractional-generation");
   // Empty demand still burns fuel but owns no speculative generated EU.
   for(int i=0;i<3;i++){detached.put(i,sink(w,i).getEnergyStorageInternal().scexExactAmount());sink(w,i).getEnergyStorageInternal().setEnergy(sink(w,i).getEnergyStorageInternal().getCapacity());w.setBlockAndUpdate(at(i).west(),Blocks.REDSTONE_BLOCK.defaultBlockState());}
  }
  if(tick==380){
   for(int i=0;i<3;i++){check(m(w,i).ownedEnergy().scexExactAmount().isZero(),"full sink creates no banked EU");w.removeBlock(at(i).west(),false);}
   groups.add("full-demand-fuel-runs-without-idle-eu-bank");
  }
  if(tick==420){
   for(int i=0;i<3;i++){sink(w,i).getEnergyStorageInternal().setEnergy(detached.get(i).whole());check(m(w,i).getCurrentHeat()>cases.get(i).getAsJsonObject().get("expected_heat").getAsLong(),"fuel burned while full");}
   // A real cached item capability must reject writes after block removal.
   int temp=cases.size()+2;place(w,at(temp),"generator/block_nuclear_reactor_generator",Direction.NORTH);removedPort=m(w,temp).getItemHandlerCapability(Direction.UP);w.removeBlock(at(temp),false);var fuel=item("uranium_simple");check(ItemStack.matches(removedPort.insertItem(0,fuel,false),fuel),"removed cached inventory inert");groups.add("cached-capability-lifecycle");
  }
  if(tick==440){Files.writeString(Path.of("world/scex-reactor-checkpoint.json"),new Gson().toJson(Map.of("rows",rows(w),"saved_tick",tick,"game_time",w.getGameTime())));groups.add("normal-saved-world-checkpoint");return finish(w);}return null;
 }
 private Map<String,Object> finish(ServerLevel w)throws Exception{var result=Map.<String,Object>of("passed",true,"assertions",assertions,"groups",groups,"rows",rows(w),"cases",cases.size(),"phase",restart?"restart":"initial","scope","Electrical reactor only. Fluid, accidents, chamber energy contacts, client and full pack remain open.");Files.writeString(Path.of("reactor-world-result.json"),new Gson().toJson(result));return result;}
}
