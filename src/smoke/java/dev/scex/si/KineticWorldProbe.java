// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.entity.KUEntity.KUGenerator.mio_icif_Kinetic_Generator_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.generator.mio_icif_kinetic_generator;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Container;
import dev.scex.energy.EnergyAmount;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/** Real four-facing motor/converter/native-storage chains and scoped accounting/lifecycle controls. */
public final class KineticWorldProbe {
 private final boolean restart;
 private final List<String> groups=new ArrayList<>();private int assertions;
 private final Map<Integer,BigInteger> totals=new HashMap<>(),dropped=new HashMap<>();
 private final Map<Integer,Long> rateEu=new HashMap<>(),rateSink=new HashMap<>();
 private static final int[] MOTORS={0,1,2,5,10};
 private static final Direction[] SIDES={Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST};
 private static final BlockPos FRACTION=new BlockPos(5200,80,100);
 public KineticWorldProbe()throws Exception{restart=JsonParser.parseString(Files.readString(Path.of("kinetic-world.json"))).getAsJsonObject().get("phase").getAsString().equals("restart");}
 private void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError("R111 kinetic: "+why);}
 private BlockPos pos(int i){return new BlockPos(5000+i*8,80,100);}
 private Direction side(int i){return SIDES[i/5];}
 private mio_icif_kinetic_generator converter(ServerLevel w,int i){return (mio_icif_kinetic_generator)w.getBlockEntity(pos(i));}
 private mio_icif_Kinetic_Generator_elc motor(ServerLevel w,int i){return (mio_icif_Kinetic_Generator_elc)w.getBlockEntity(pos(i).relative(side(i)));}
 private mio_icif_Energy_Container sink(ServerLevel w,int i){return (mio_icif_Energy_Container)w.getBlockEntity(pos(i).relative(side(i).getOpposite()));}
 private void place(ServerLevel w,BlockPos at,String id,Direction face){
  var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:"+id));var property=(DirectionProperty)block.getStateDefinition().getProperty("facing");
  check(property!=null&&property.getPossibleValues().contains(face),"Registered allowed facing "+id);
  check(w.setBlockAndUpdate(at,block.defaultBlockState().setValue(property,face)),"Registered placement "+id);
 }
 private void fill(mio_icif_Kinetic_Generator_elc m,int count){
  var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:resource/item_motor"));
  for(int n=1;n<=count;n++)check(m.getItemHandler().insertItem(n,new ItemStack(item),false).isEmpty(),"Actual motor slot");
  check(m.getMotorCount()==count,"Actual motor count");
 }
 private BigInteger total(ServerLevel w,int i){
  var m=motor(w,i);var c=converter(w,i);var e=m.getEnergyStorageInternal().scexExactAmount().add(c.ownedEnergy().scexExactAmount()).add(sink(w,i).getEnergyStorageInternal().scexExactAmount());
  return e.units().add(dev.scex.si.energy.ElectricMotorKinetics.cost(m.scexStoredKinetic()+c.getKineticStorage().getKineticStored()).units());
 }
 private List<Map<String,Object>> snapshot(ServerLevel w){
  var rows=new ArrayList<Map<String,Object>>();
  for(int i=0;i<20;i++){var m=motor(w,i);var c=converter(w,i);var s=sink(w,i).getEnergyStorageInternal();
   rows.add(Map.of("index",i,"motor_eu",m.getEnergyStorageInternal().getAmount(),"motor_fraction",m.getEnergyStorageInternal().scexSavedFraction(),"motor_ku",m.scexStoredKinetic(),"converter_eu",c.ownedEnergy().getAmount(),"converter_fraction",c.ownedEnergy().scexSavedFraction(),"converter_ku",c.getKineticStorage().getKineticStored(),"sink_eu",s.getAmount(),"sink_fraction",s.scexSavedFraction()));
  }return rows;
 }
 public Map<String,Object> inspect(ServerLevel w,int tick)throws Exception{
  if(restart){
   if(tick==20||tick==50){
    var expected=JsonParser.parseString(Files.readString(Path.of("world/scex-kinetic-checkpoint.json")));
    check(JsonParser.parseString(new Gson().toJson(snapshot(w))).equals(expected),"Saved cold world exact motor/KU/EU balances");
    var s=(mio_icif_Energy_Container)w.getBlockEntity(FRACTION.south());check(s.getEnergyStorageInternal().scexExactAmount().equals(new EnergyAmount(0,EnergyAmount.UNITS*3/4)),"Cold quarter EU preserved");
   }
   if(tick==50){groups.add("new-jvm-exact-quarter-eu-and-all-twenty-chain-balances");return finish(w);}return null;
  }
  if(tick==20){
   for(int i=0;i<20;i++){
    place(w,pos(i),"generator/block_kinetic_generator",side(i));place(w,pos(i).relative(side(i)),"kugenerator/block_kinetic_generator_elc",side(i).getOpposite());
    var m=motor(w,i);check(m.getEnergyStorageInternal().scexNetworkControlled()&&converter(w,i).ownedEnergy().scexNetworkControlled(),"Both independent owners");fill(m,MOTORS[i%5]);m.getEnergyStorageInternal().setEnergy(10000);
   }
   place(w,FRACTION,"generator/block_kinetic_generator",Direction.NORTH);
   var c=(mio_icif_kinetic_generator)w.getBlockEntity(FRACTION);var port=c.getKineticStorageCapability(Direction.NORTH);
   check(port.receiveKinetic(2,false)==2,"Two paid external KU accepted");
   place(w,FRACTION.north(),"kugenerator/block_kinetic_generator_elc",Direction.SOUTH);
   var fractionalMotor=(mio_icif_Kinetic_Generator_elc)w.getBlockEntity(FRACTION.north());fill(fractionalMotor,1);
   fractionalMotor.getEnergyStorageInternal().scexLoadFraction(EnergyAmount.UNITS/4);
   var changed=c.getBlockState().setValue((DirectionProperty)c.getBlockState().getBlock().getStateDefinition().getProperty("facing"),Direction.SOUTH);w.setBlockAndUpdate(FRACTION,changed);
   check(port.receiveKinetic(1,false)==0,"Cached wrong-face input invalidated on rotation");
   w.setBlockAndUpdate(FRACTION,changed.setValue((DirectionProperty)changed.getBlock().getStateDefinition().getProperty("facing"),Direction.NORTH));
  }
  if(tick==60){
   for(int i=0;i<20;i++){var m=motor(w,i);var c=converter(w,i);
    check(m.getEnergyStorageInternal().getAmount()==9750&&m.scexStoredKinetic()==1000,"No sink pays one 250 EU motor buffer, including zero motors");
    check(c.ownedEnergy().scexExactAmount().isZero()&&c.getKineticStorage().getKineticStored()==0,"No demand does not pull source KU");
    place(w,pos(i).relative(side(i).getOpposite()),"wiring/block_mfsu",side(i).getOpposite());var s=sink(w,i).getEnergyStorageInternal();s.setEnergy(s.getCapacity());
   }
   var fractionalMotor=(mio_icif_Kinetic_Generator_elc)w.getBlockEntity(FRACTION.north());
   check(fractionalMotor.getEnergyStorageInternal().scexExactAmount().isZero()&&fractionalMotor.scexStoredKinetic()==1,"Actual quarter EU pays exactly one buffered KU");
   groups.add("four-facings-five-motor-counts-idle-buffer-only");
  }
  if(tick==90){for(int i=0;i<20;i++){check(motor(w,i).getEnergyStorageInternal().getAmount()==9750&&motor(w,i).scexStoredKinetic()==1000,"Full sink no source drain");var s=sink(w,i).getEnergyStorageInternal();s.setEnergy(s.getCapacity()-1);}groups.add("full-sink-no-extra-source-charge");}
  if(tick==100){for(int i=0;i<20;i++){boolean work=MOTORS[i%5]>0;check(motor(w,i).getEnergyStorageInternal().getAmount()==(work?9749:9750),"One EU room exact source cost");var s=sink(w,i).getEnergyStorageInternal();check(s.getAmount()==s.getCapacity()-(work?0:1),"One EU delivered only with installed motor");}groups.add("one-eu-demand-and-zero-motor-control");}
  if(tick==110){for(int i=0;i<20;i++){sink(w,i).getEnergyStorageInternal().setEnergy(0);totals.put(i,total(w,i));}}
  if(tick==120){for(int i=0;i<20;i++){rateEu.put(i,motor(w,i).getEnergyStorageInternal().getAmount());rateSink.put(i,sink(w,i).getEnergyStorageInternal().getAmount());}}
  if(tick==130){for(int i=0;i<20;i++){long expected=MOTORS[i%5]*250L;check(rateEu.get(i)-motor(w,i).getEnergyStorageInternal().getAmount()==expected,"Measured motor EU cost per ten ticks");check(sink(w,i).getEnergyStorageInternal().getAmount()-rateSink.get(i)==expected,"Measured 0/25/50/125/250 EU native steady rates");check(total(w,i).equals(totals.get(i)),"Full exact energy conservation");}groups.add("twenty-real-chains-five-measured-rates-and-exact-ledger");}
  if(tick==140){for(int i=0;i<20;i++){var e=motor(w,i).getEnergyStorageInternal();dropped.put(i,e.scexExactAmount().units());e.setEnergy(0);}}
  if(tick==160){
   for(int i=0;i<20;i++){check(total(w,i).equals(totals.get(i).subtract(dropped.get(i))),"Power removal preserves paid KU and delivered EU");check(motor(w,i).scexStoredKinetic()==(MOTORS[i%5]==0?1000:0),"Paid buffer drains exactly once when motors installed");}
   place(w,FRACTION.south(),"wiring/block_mfsu",Direction.SOUTH);groups.add("power-cut-conserves-all-owned-balances");
   // Actual receiver grade checks, isolated from the twenty accounting chains.
   for(int n=0;n<2;n++){var at=new BlockPos(5240+n*40,80,100);place(w,at,"generator/block_kinetic_generator",Direction.NORTH);place(w,at.north(),"kugenerator/block_kinetic_generator_elc",Direction.SOUTH);
    var m=(mio_icif_Kinetic_Generator_elc)w.getBlockEntity(at.north());fill(m,n==0?2:6);m.getEnergyStorageInternal().setEnergy(10000);place(w,at.south(),n==0?"wiring/block_bat_box":"wiring/block_cesu",Direction.SOUTH);
   }
  }
  if(tick==190){
   var s=(mio_icif_Energy_Container)w.getBlockEntity(FRACTION.south());check(s.getEnergyStorageInternal().scexExactAmount().equals(new EnergyAmount(0,EnergyAmount.UNITS*3/4)),"Three KU produces exact three-quarter EU");
   check(((mio_icif_Kinetic_Generator_elc)w.getBlockEntity(FRACTION.north())).scexStoredKinetic()==0,"Paid fractional source KU consumed once");
   for(int n=0;n<2;n++)check(w.getBlockEntity(new BlockPos(5240+n*40,80,101))==null,"Measured 50/150 EU receiver removal");
   var c=converter(w,0);var old=c.saveWithFullMetadata(w.registryAccess());old.remove("scex_kinetic_converter");old.putLong("energy",137);old.putLong("KineticStored",64);
   var copy=(mio_icif_kinetic_generator)BlockEntity.loadStatic(c.getBlockPos(),c.getBlockState(),old,w.registryAccess());check(copy!=null&&copy.ownedEnergy().getAmount()==137&&copy.getKineticStorage().getKineticStored()==64,"Observed legacy KU field and EU preserved");
   var future=copy.saveWithFullMetadata(w.registryAccess());future.getCompound("scex_kinetic_converter").putInt("version",99);
   var held=(mio_icif_kinetic_generator)BlockEntity.loadStatic(c.getBlockPos(),c.getBlockState(),future,w.registryAccess());check(held.hasLegacyHold(),"Unknown version retained and held");
   groups.add("quarter-eu-overvoltage-and-conservative-old-save-roundtrip");
  }
  if(tick==210){Files.writeString(Path.of("world/scex-kinetic-checkpoint.json"),new Gson().toJson(snapshot(w)));groups.add("cold-world-checkpoint");}
  if(tick==220)return finish(w);return null;
 }
 private Map<String,Object> finish(ServerLevel w)throws Exception{
  var engine=dev.scex.si.energy.IndependentSiEnergy.current(w.getServer());check(engine!=null&&engine.metrics().failure().isEmpty(),"Independent grid healthy");
  var result=Map.<String,Object>of("passed",true,"phase",restart?"restart":"initial","assertions",assertions,"groups",groups,"chains",snapshot(w),"scope","Scoped SI motor/converter/native grid; 20 horizontal chains and two receiver grades. Not full orientation parity, foreign API fault recovery, client, multiplayer or performance.");
  Files.writeString(Path.of("kinetic-world-result.json"),new Gson().toJson(result));return result;
 }
}
