// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;
import com.google.gson.Gson;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_chunk_loader;
import dev.scex.si.energy.OwnedChunkTickets;
import dev.scex.energy.EnergyAmount;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/** Real paid ticking, shared targets, vanilla-ticket isolation and exact storage recovery. */
public final class ChunkTicketWorldProbe {
 private int assertions;
 private final BlockPos a=new BlockPos(18000,80,100),b=a.east(2),c=a.east(32),d=a.east(64);
 private final ChunkPos target=new ChunkPos((18000>>4)+3,100>>4);
 private mio_icif_chunk_loader first,second,saved,upgrade;
 private long begin,upgradeCapacity;
 private final BlockPos reentrantAt=new BlockPos(18320,80,100);
 private final ChunkPos reentrantTarget=new ChunkPos((18320>>4)+4,100>>4);
 private mio_icif_chunk_loader reentrant;
 private boolean armed,callbackObserved;
 @net.neoforged.bus.api.SubscribeEvent
 public void loading(net.neoforged.neoforge.event.level.ChunkEvent.Load event){
  if(!armed || event.getLevel()!=reentrant.getLevel() || !event.getChunk().getPos().equals(reentrantTarget))return;
  armed=false;callbackObserved=true;
  var world=(ServerLevel)event.getLevel();
  check(world.getServer().isSameThread(),"synchronous FULL-load callback on server thread");
  check(reentrant.getEnergyStorageInternal().getAmount()==198,"load callback observes prepaid two-chunk bill");
  check(owners(world,reentrantTarget)==1,"tracker entry exists during synchronous load callback");
  check(world.removeBlock(reentrantAt,false)&&reentrant.isRemoved(),"load callback removes real owner");
 }
 private void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError("R131 tickets "+why);}
 private mio_icif_chunk_loader place(ServerLevel w,BlockPos p){
  check(w.setBlockAndUpdate(p,BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:producer/block_chunk_loader")).defaultBlockState()),"place registered loader");
  var tile=(mio_icif_chunk_loader)w.getBlockEntity(p);check(tile!=null&&tile.getEnergyStorageInternal().scexNetworkControlled(),"independent existing balance");
  return tile;
 }
 private long owners(ServerLevel w,ChunkPos chunk){
  var data=w.getDataStorage().get(ForcedChunksSavedData.factory(),"chunks");
  return data==null?0:data.getBlockForcedChunks().getTickingChunks().values().stream().filter(s->s.contains(chunk.toLong())).count();
 }
 private ItemStack item(String id){var value=BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));check(value!=Items.AIR,"registered "+id);return new ItemStack(value);}
 private void put(mio_icif_chunk_loader m,int slot,ItemStack value){((IItemHandlerModifiable)m.getItemHandler()).setStackInSlot(slot,value);}
 public Map<String,Object> inspect(ServerLevel w,int tick)throws Exception{
  if(tick==20){
   check(!w.getForcedChunks().contains(target.toLong()),"target has no preexisting vanilla ticket");
   w.setChunkForced(target.x,target.z,true);
   first=place(w,a);second=place(w,b);saved=place(w,c);upgrade=place(w,d);
   for(var m:List.of(first,second)){
    check(m.addChunkToLoaded(m.getSelfChunkPos())&&m.addChunkToLoaded(target),"select self and common target");
    check(!m.isActive(),"selection alone inactive");
    m.getEnergyStorageInternal().setEnergy(1000);
   }
   check(owners(w,target)==0,"selection installs no unpaid ticket");
   check(!first.isChunkInRange(Integer.MIN_VALUE,0)&&!first.isChunkInRange(new ChunkPos(Integer.MIN_VALUE,0)),"overflow cannot bypass selection range");
   check(!first.addChunkToLoaded(new ChunkPos(target.x+100,target.z)),"range rejects");
   try{first.getLoadedChunks().clear();throw new AssertionError("mutable selection leaked");}catch(UnsupportedOperationException expected){assertions++;}
   var bank=saved.getEnergyStorageInternal();bank.setEnergy(1000);bank.scexLoadFraction(EnergyAmount.UNITS/4);
   var payment=bank.scexReserveInternal(500);check(payment!=null&&bank.getAmount()==500,"upfront shared debit");
   check(bank.generateEnergyInternal(100,false)==100&&bank.consumeEnergyInternal(25,false)==25,"callback balance changes");
   check(payment.cancel()&&!payment.cancel()&&!payment.commit(),"cancel exactly once");
   check(bank.scexExactAmount().equals(new EnergyAmount(1075,EnergyAmount.UNITS/4)),"refund preserves callback changes and fraction");
   bank.setEnergy(2500);var full=bank.scexReserveInternal(500);check(full!=null&&bank.receive(128,false)==0,"refund space reserved");
   check(full.cancel()&&bank.getAmount()==2500,"full refund without clipping");
   bank.setEnergy(2000);check(saved.addChunkToLoaded(saved.getSelfChunkPos()),"persistent paid owner selection");
   put(upgrade,1,item("mio_icif:upgrade/transformer_upgrade"));
   begin=w.getGameTime();
  }
  if(tick==24){
   long elapsed=w.getGameTime()-begin;
   check(first.getEnergyStorageInternal().getAmount()==1000-2*elapsed&&second.getEnergyStorageInternal().getAmount()==1000-2*elapsed,"two EU per natural tick on each owner");
   check(first.isActive()&&second.isActive()&&owners(w,target)==2,"two paid owners hold independent tickets");
   check(w.destroyBlock(a,true),"remove first actual owner");check(owners(w,target)==1,"one owner's removal preserves second");
   check(w.getForcedChunks().contains(target.toLong()),"owner removal preserves vanilla ticket");
   check(upgrade.getEnergyStorageInternal().getMaxReceive()>128,"transformer upgrade raises input");
   put(upgrade,1,ItemStack.EMPTY);
  }
  if(tick==26){
   check(upgrade.getEnergyStorageInternal().getMaxReceive()==128,"removed transformer restores input128");
   second.getEnergyStorageInternal().setEnergy(0);
   put(upgrade,1,item("mio_icif:upgrade/energy_storage_upgrade"));
  }
  if(tick==28){
   check(!second.isActive()&&owners(w,target)==0,"insufficient payment releases every owned ticket");
   check(w.getForcedChunks().contains(target.toLong()),"depletion does not clear vanilla ticket");
   upgradeCapacity=upgrade.getEnergyStorageInternal().getCapacity();check(upgradeCapacity>2500,"upgrade capacity is real");
   upgrade.getEnergyStorageInternal().setEnergy(Math.min(upgradeCapacity,10000));
   var tag=upgrade.saveWithFullMetadata(w.registryAccess());
   var copy=(mio_icif_chunk_loader)BlockEntity.loadStatic(d,upgrade.getBlockState(),tag,w.registryAccess());
   check(copy!=null&&copy.getEnergyStorageInternal().getAmount()==upgrade.getEnergyStorageInternal().getAmount(),"saved upgraded amount preserved");
   check(copy.getEnergyStorageInternal().getCapacity()==upgradeCapacity&&!copy.isActive(),"saved capacity restored before energy and no activation");
   check(ItemStack.matches(upgrade.getItemHandler().getStackInSlot(1),copy.getItemHandler().getStackInSlot(1)),"upgrade inventory persists");
   var bad=tag.copy();var selection=new net.minecraft.nbt.CompoundTag();selection.putInt("count",Integer.MAX_VALUE);
   selection.putLong("c0",new ChunkPos(Integer.MIN_VALUE,Integer.MAX_VALUE).toLong());bad.put("loadedChunks",selection);
   copy=(mio_icif_chunk_loader)BlockEntity.loadStatic(d,upgrade.getBlockState(),bad,w.registryAccess());
   check(copy!=null&&copy.getLoadedChunkCount()==0,"bounded invalid saved selections rejected");
   var cached=second.scexFeCapability(Direction.UP);w.destroyBlock(b,true);check(cached.receiveEnergy(4,false)==0,"removed cached input inert");
   w.setChunkForced(target.x,target.z,false);
  }
  if(tick==60){
   check(w.getChunkSource().getChunkNow(reentrantTarget.x,reentrantTarget.z)==null,"reentrant target was not already FULL");
   reentrant=place(w,reentrantAt);reentrant.getEnergyStorageInternal().setEnergy(200);
   check(reentrant.addChunkToLoaded(reentrant.getSelfChunkPos())&&reentrant.addChunkToLoaded(reentrantTarget),"reentrant target selection");
   armed=true;net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(this);
  }
  if(tick==90){
   net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(this);
   check(callbackObserved&&!armed,"actual synchronous loading removal exercised");
   check(w.getBlockEntity(reentrantAt)==null&&reentrant.getEnergyStorageInternal().getAmount()==200,"failed ticket transaction cancels once without revival");
   check(owners(w,reentrantTarget)==0&&!w.shouldTickBlocksAt(reentrantTarget.toLong()),"both saved tracker and late runtime ticking ticket are removed");
  }
  if(tick==100){
   check(saved.isActive()&&owners(w,saved.getSelfChunkPos())==1,"paid owner remains for cold persistence");
   check(saved.getEnergyStorageInternal().getAmount()==2000-(w.getGameTime()-begin),"one EU per natural saved-owner tick");
   var result=Map.<String,Object>of("passed",true,"assertions",assertions,"cases",13,"upgrade_capacity",upgradeCapacity,
    "groups",List.of("payment-before-tickets","overlapping-owner-and-vanilla-isolation","depletion-removal-cache","escrow-reentrant-ledger","upgrade-rate-and-saved-capacity","bounded-immutable-selection"),
    "limits",List.of("Cold JVM still requires separate checks","Historical vanilla force tickets lack provenance and are not automatically removed"));
   Files.writeString(Path.of("chunk-ticket-world-result.json"),new Gson().toJson(result));return result;
  }return null;
 }
}

