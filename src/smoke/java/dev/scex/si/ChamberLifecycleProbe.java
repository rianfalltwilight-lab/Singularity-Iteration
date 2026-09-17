// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;
import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.singularity_iteration.mio_icif.Blocks.entity.generator.mio_icif_nuclear_reactor_generator;
import com.singularity_iteration.mio_icif.Blocks.entity.reactor.mio_icif_reactor_chamber;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** R125 normal topology, actual survival placement and exactly-once chamber drops. */
public final class ChamberLifecycleProbe {
 private int assertions;private final List<BlockPos> dropCases=new ArrayList<>();
 private final String chamber="mio_icif:reactor/block_reactor_chamber",core="mio_icif:generator/block_nuclear_reactor_generator";
 private void check(boolean value,String why){assertions++;if(!value)throw new AssertionError("R126 "+why);}
 private void place(ServerLevel w,BlockPos p,String id){check(w.setBlockAndUpdate(p,BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState()),"real block "+id);}
 private void pad(ServerLevel w,BlockPos p){for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)w.setBlockAndUpdate(p.offset(x,-1,z),Blocks.STONE.defaultBlockState());}
 private int drops(ServerLevel w,BlockPos p){return w.getEntitiesOfClass(ItemEntity.class,new AABB(p).inflate(4)).stream().map(ItemEntity::getItem).filter(s->BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(chamber)).mapToInt(ItemStack::getCount).sum();}
 public Map<String,Object> inspect(ServerLevel w,int tick)throws Exception{
  if(tick==40){
   int[] heat={0,1999,2000,3999,4000,6999,8000,9999};
   for(int i=0;i<8;i++){
    var p=new BlockPos(17000+i*16,80,100);pad(w,p.east());place(w,p,core);place(w,p.east(),chamber);var m=(mio_icif_nuclear_reactor_generator)w.getBlockEntity(p);var c=(mio_icif_reactor_chamber)w.getBlockEntity(p.east());m.getHeatStorage().setHeat(heat[i]);
    for(var side:Direction.values())if(side!=Direction.WEST)w.setBlockAndUpdate(p.east().relative(side),Blocks.STONE.defaultBlockState());
    var cached=c.getHeatStorageCapability(Direction.UP);check(w.destroyBlock(p.east(),true),"normal heated chamber destruction");check(c.isRemoved()&&w.getBlockEntity(p.east())==null&&w.getBlockState(p).getBlock()==m.getBlockState().getBlock(),"core survives and removed chamber is gone");check(cached.extractHeat(1,false)==0&&m.getCurrentHeat()==heat[i],"removed hot cached port cannot debit");
    for(var side:Direction.values())if(side!=Direction.WEST)check(w.getBlockState(p.east().relative(side)).is(Blocks.STONE),"heated removal never explodes neighbor");
    m.getHeatStorage().setHeat(0);dropCases.add(p.east());
   }
   // A second real core uses normal neighbor notifications; exactly one existing chamber drops.
   var p=new BlockPos(17200,80,100);pad(w,p.east());place(w,p,core);place(w,p.east(),chamber);var old=(mio_icif_reactor_chamber)w.getBlockEntity(p.east());var cached=old.getItemHandlerCapability(Direction.UP);place(w,p.east(2),core);check(w.getBlockState(p.east()).isAir()&&old.isRemoved(),"normal second core retires chamber without explosion");check(w.getBlockEntity(p) instanceof mio_icif_nuclear_reactor_generator&&w.getBlockEntity(p.east(2)) instanceof mio_icif_nuclear_reactor_generator,"both cores survive ambiguity");check(cached.extractItem(0,1,false).isEmpty(),"old item cache invalid after automatic topology drop");dropCases.add(p.east());
   var orphan=new BlockPos(17232,80,100);pad(w,orphan.east());place(w,orphan,core);place(w,orphan.east(),chamber);w.removeBlock(orphan,false);check(w.getBlockState(orphan.east()).isAir()&&w.getBlockEntity(orphan.east())==null,"orphan automatically drops after actual core removal");dropCases.add(orphan.east());
   var player=FakePlayerFactory.get(w,new GameProfile(UUID.fromString("653bda2a-6d7c-40dc-a123-8e5c64c4d126"),"R126Chamber"));player.getAbilities().instabuild=false;
   for(int count=0;count<3;count++){
    var at=new BlockPos(17300+count*16,80,100);w.setBlockAndUpdate(at.below(),Blocks.STONE.defaultBlockState());if(count>=1)place(w,at.west(),core);if(count==2)place(w,at.east(),core);
    var stack=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(chamber)));player.setPos(at.getX()+.5,at.getY(),at.getZ()+2.5);player.setItemInHand(InteractionHand.MAIN_HAND,stack);
    var ctx=new BlockPlaceContext(player,InteractionHand.MAIN_HAND,stack,new BlockHitResult(Vec3.atCenterOf(at.below()).add(0,.5,0),Direction.UP,at.below(),false));var result=((BlockItem)stack.getItem()).place(ctx);
    if(count==1)check(result.consumesAction()&&stack.isEmpty()&&w.getBlockEntity(at) instanceof mio_icif_reactor_chamber,"single core survival placement consumes once");
    else check(!result.consumesAction()&&stack.getCount()==1&&w.getBlockState(at).isAir()&&drops(w,at)==0,"invalid survival placement retains held item without duplicate drop");
   }
  }
  if(tick==60)for(var p:dropCases){int count=drops(w,p);check(count==1,"one actual chamber drop per removal at"+p+" count="+count);}
  if(tick==100){for(var p:dropCases){int count=drops(w,p);check(count==1,"no repeated or merged duplicate drop at"+p+" count="+count);}
   var r=Map.<String,Object>of("passed",true,"assertions",assertions,"cases",13,"groups",List.of("eight-heated-chamber-removals-and-inert-cache","ordinary-second-core-and-orphan-single-drops","survival-block-item-placement-zero-one-two-cores","delayed-no-duplicate-drop"));Files.writeString(Path.of("chamber-lifecycle-result.json"),new Gson().toJson(r));return r;
  }return null;
 }
}
