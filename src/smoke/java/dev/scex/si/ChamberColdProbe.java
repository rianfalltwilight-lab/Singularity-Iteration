// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;
import com.google.gson.*;
import com.singularity_iteration.mio_icif.Blocks.entity.generator.mio_icif_nuclear_reactor_generator;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Container;
import com.singularity_iteration.mio_icif.Blocks.entity.reactor.mio_icif_reactor_chamber;
import com.singularity_iteration.mio_icif.api.capability.IMioIcifCapabilities;
import com.singularity_iteration.mio_icif.Items.Normal.mio_icif_data_components;
import dev.scex.energy.EnergyAmount;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Cold admission and exact settled output after three natural restarted fuel cycles. */
public final class ChamberColdProbe {
 private int assertions;private final int[] fuel=new int[13];private final List<BlockPos> power=new ArrayList<>();
 private void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError("R124 cold "+why);}
 private BlockPos at(int i){return new BlockPos(16000+i*16,80,100);}
 private mio_icif_nuclear_reactor_generator core(ServerLevel w,int i){return (mio_icif_nuclear_reactor_generator)w.getBlockEntity(at(i));}
 private Direction[] sides(int i){return i==12?Direction.values():new Direction[]{Direction.values()[i%6]};}
 private EnergyAmount sum(ServerLevel w,int i){var total=core(w,i).ownedEnergy().scexExactAmount();for(var side:sides(i))total=total.add(((mio_icif_Energy_Container)w.getBlockEntity(at(i).relative(side,i>=6&&i<12?3:2))).getEnergyStorageInternal().scexExactAmount());return total;}
 public ChamberColdProbe(MinecraftServer server)throws Exception{
  var w=server.overworld();var rows=JsonParser.parseString(Files.readString(Path.of("chamber-cold.json"))).getAsJsonObject().getAsJsonArray("rows");check(rows.size()==13,"thirteen frozen chains");
  for(int i=0;i<13;i++){
   var expected=rows.get(i).getAsJsonObject();var old=TagParser.parseTag(expected.get("core").getAsString());var m=core(w,i);check(m!=null,"restored registered core");var current=m.saveWithoutMetadata(w.registryAccess());
   for(String key:List.of("Items","HeatStored","MaxHeatStored","energy","scex_energy_fraction","ReactorMode"))check(Objects.equals(old.get(key),current.get(key)),"saved "+key+" case"+i);
   check(m.getAvailableColumns()==(i==12?9:4),"loaded adjacency reconstructs cold columns");check(sum(w,i).equals(EnergyAmount.of(1000)),"cold exact total retained");
   int s=0;for(var side:sides(i)){
    var chamber=at(i).relative(side);check(((mio_icif_reactor_chamber)w.getBlockEntity(chamber)).getConnectedReactor()==m,"cold unique core identity");var port=w.getCapability(IMioIcifCapabilities.HEAT_STORAGE_BLOCK,chamber,Direction.UP);check(port!=null&&port.extractHeat(1,false)==0&&m.getCurrentHeat()==40,"before first tick cache cannot take heat");
    var wanted=expected.getAsJsonArray("sinks").get(s++).getAsJsonObject();var actual=((mio_icif_Energy_Container)w.getBlockEntity(at(i).relative(side,i>=6&&i<12?3:2))).getEnergyStorageInternal().scexExactAmount();check(actual.equals(new EnergyAmount(wanted.get("whole").getAsLong(),wanted.get("fraction").getAsLong())),"individual receiver exact cold balance");
   }
   fuel[i]=m.getItem(0).get(mio_icif_data_components.FUEL_ROD_DURABILITY.get()).remainingUses();power.add(i==12?at(i).above().east():at(i).relative(sides(i)[0].getOpposite()));
  }
 }
 public Map<String,Object> inspect(ServerLevel w,int tick)throws Exception{
  if(tick==20)for(var p:power)w.setBlockAndUpdate(p,Blocks.REDSTONE_BLOCK.defaultBlockState());
  if(tick==80)for(var p:power)w.removeBlock(p,false);
  if(tick==120){for(int i=0;i<13;i++){var m=core(w,i);check(m.getItem(0).get(mio_icif_data_components.FUEL_ROD_DURABILITY.get()).remainingUses()==fuel[i]-3,"exact three fuel debits");check(m.getCurrentHeat()==52,"exact three heat increments");check(sum(w,i).equals(EnergyAmount.of(1300)),"only300 EU generated across all restored contacts case"+i+" actual="+sum(w,i));check(!m.isRunning()&&!m.hasLegacyHold(),"normal paused terminal");}
   var r=Map.<String,Object>of("passed",true,"cases",13,"assertions",assertions,"groups",List.of("before-first-tick-cold-owner-admission-and-exact-balances","three-natural-cold-cycles-shared-budget-and-stop"));Files.writeString(Path.of("chamber-cold-result.json"),new Gson().toJson(r));return r;
  }return null;
 }
}
