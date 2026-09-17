// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_replicator_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_replicator_elc;
import com.singularity_iteration.mio_icif.Blocks.Environment.fluid.mio_icif_fluids;
import com.singularity_iteration.mio_icif.Items.Resource.mio_icif_memory;
import dev.scex.si.processing.IndependentUuValueIndex;
import dev.scex.si.processing.UuQuoteBook;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Actual registered entities/tickers; two explicit reference-price fixtures, not a production price loader. */
public final class ReplicatorWorldProbe {
    private static final double STONE=.00015,IRON=.0007463641798863822;
    private final List<String> groups=new ArrayList<>();
    private final List<BlockPos> positions=new ArrayList<>();
    private int assertions;
    private Block block;private mio_icif_memory memory;
    private long[] previousEu=new long[2];private int[] paidTicks=new int[2];
    private CompoundTag stoneFull,ironFull;
    private void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError("R95 replicator: "+why);}
    private void near(double actual,double expected,String why){check(Math.abs(actual-expected)<1e-12,why+" actual="+actual+" expected="+expected);}
    private mio_icif_replicator_elc machine(ServerLevel world,int index){return (mio_icif_replicator_elc)world.getBlockEntity(positions.get(index));}
    private void quotes(ServerLevel world){
        var prices=new IndependentUuValueIndex(2);
        check(prices.register(new ItemStack(Items.STONE),STONE)&&prices.register(new ItemStack(Items.IRON_INGOT),IRON),"explicit measured fixture quotes");
        UuQuoteBook.install(world.getServer(),prices);
    }
    private mio_icif_replicator_elc create(ServerLevel world,Item item,long eu,int fluid){
        var at=new BlockPos(2000+positions.size()*4,80,4);positions.add(at);
        check(world.setBlockAndUpdate(at,block.defaultBlockState()),"place registered machine");
        var tile=machine(world,positions.size()-1);
        var crystal=new ItemStack(memory);check(memory.tryStoreData(crystal,new ItemStack(item),99,999999),"crystal stores deliberately stale costs");
        tile.setItem(mio_icif_replicator_elc.MEMORY_SLOT,crystal);
        tile.getEnergyStorageInternal().setEnergy(eu);
        if(fluid>0)check(tile.getFluidHandlerCapability(Direction.UP).fill(new FluidStack(mio_icif_fluids.UUMATTER.get(),fluid),IFluidHandler.FluidAction.EXECUTE)==fluid,"normal UU capability fill");
        return tile;
    }
    private void start(ServerLevel world){
        var blocks=BuiltInRegistries.BLOCK.stream().filter(b->b instanceof mio_icif_block_replicator_elc).toList();
        var memories=BuiltInRegistries.ITEM.stream().filter(i->i instanceof mio_icif_memory).toList();
        check(blocks.size()==1&&memories.size()==1,"registered identities unambiguous");block=blocks.getFirst();memory=(mio_icif_memory)memories.getFirst();
        quotes(world);
        create(world,Items.STONE,2000000,1000).loopGeneration();
        create(world,Items.IRON_INGOT,2000000,1000).loopGeneration();
        create(world,Items.STONE,2000000,1000).generateOnce();
        create(world,Items.STONE,0,1000).loopGeneration();
        create(world,Items.IRON_INGOT,2000000,0).loopGeneration();
        for(int i=0;i<2;i++)previousEu[i]=machine(world,i).getEnergyStorageInternal().getAmount();
    }
    private void observe(ServerLevel world){
        for(int i=0;i<2;i++){
            var m=machine(world,i);long eu=m.getEnergyStorageInternal().getAmount();long spent=previousEu[i]-eu;
            check(spent==0||spent==512,"real tick has at most one base debit");if(spent>0)paidTicks[i]++;previousEu[i]=eu;
            int count=m.getItemHandler().getStackInSlot(mio_icif_replicator_elc.OUTPUT_SLOT).getCount();double price=i==0?STONE:IRON;
            check(count==Math.min(64,paidTicks[i]/(i==0?2:8)),"actual output timing follows measured base work ticks");
            near((1000-m.getUuMatterAmount())/1000.0-m.getUuCreditBuckets(),count*price+m.getProcessedUuBuckets(),"tank plus fractional credit conservation");
            near(m.getCurrentUuCostBuckets(),price,"stale crystal price ignored");
            check(!m.hasHeldReplicationData(),"valid current work stays usable");
        }
    }
    private void reloadMidWork(ServerLevel world){
        var old=machine(world,1);check(old.getProcessedUuBuckets()>0&&old.getProcessedUuBuckets()<IRON,"save during partially paid item");
        var at=positions.get(1);var state=world.getBlockState(at);var saved=old.saveWithFullMetadata(world.registryAccess());
        var restored=BlockEntity.loadStatic(at,state,saved,world.registryAccess());
        check(restored instanceof mio_icif_replicator_elc,"standard block entity deserializer");
        world.removeBlockEntity(at);world.setBlockEntity(restored);
        near(machine(world,1).getProcessedUuBuckets(),old.getProcessedUuBuckets(),"mid-work progress reloaded");
        near(machine(world,1).getUuCreditBuckets(),old.getUuCreditBuckets(),"mid-work fractional credit reloaded");
        groups.add("actual-block-entity-mid-work-serialization");
    }
    private void heldBreakPlace(ServerLevel world) throws Exception {
        var tile=create(world,Items.STONE,10000,100);var pos=positions.getLast();
        var bad=tile.saveWithoutMetadata(world.registryAccess());bad.getCompound("scex_replication_v1").putInt("version",999);
        tile.loadWithComponents(bad,world.registryAccess());tile.setChanged();check(tile.hasHeldReplicationData(),"future schema held");
        check(tile.getItemHandler().extractItem(2,1,false).isEmpty(),"held inventory cannot escape through capability");
        var player=FakePlayerFactory.get(world,new GameProfile(UUID.fromString("aa3a8a56-4c34-4809-9747-9a5bed48f54f"),"R95UuCustody"));
        var state=world.getBlockState(pos);var before=tile.saveWithId(world.registryAccess());
        var params=new LootParams.Builder(world).withParameter(LootContextParams.ORIGIN,Vec3.atCenterOf(pos)).withParameter(LootContextParams.TOOL,ItemStack.EMPTY)
            .withParameter(LootContextParams.BLOCK_ENTITY,tile).withOptionalParameter(LootContextParams.THIS_ENTITY,player);
        check(state.getDrops(params).isEmpty(),"held loot preview does not duplicate packed machine");
        check(world.destroyBlock(pos,true,player),"actual held block destruction");
        var drops=world.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(1));
        check(drops.size()==1&&drops.getFirst().getItem().is(block.asItem()),"exactly one packed machine, no loose duplicates: "+drops.stream().map(e->e.getItem().toString()).toList());
        var packed=drops.getFirst().getItem().copy();drops.getFirst().discard();
        check(packed.get(DataComponents.BLOCK_ENTITY_DATA).copyTag().equals(before),"packed item retains exact pre-break data");
        var target=pos.south(4);world.setBlockAndUpdate(target.below(),Blocks.STONE.defaultBlockState());
        player.setPos(target.getX()+.5,target.getY(),target.getZ()+2.5);player.setItemInHand(InteractionHand.MAIN_HAND,packed);
        var context=new BlockPlaceContext(player,InteractionHand.MAIN_HAND,packed,new BlockHitResult(Vec3.atCenterOf(target.below()).add(0,.5,0),Direction.UP,target.below(),false));
        check(((BlockItem)packed.getItem()).place(context).consumesAction()&&packed.isEmpty(),"survival placement consumes packed machine");
        var placed=(mio_icif_replicator_elc)world.getBlockEntity(target);
        check(placed.hasHeldReplicationData(),"placement retains held status");
        check(placed.saveWithId(world.registryAccess()).getCompound("scex_replication_v1").equals(before.getCompound("scex_replication_v1")),"placement retains exact unresolved owned data");
        groups.add("actual-held-block-break-and-survival-placement");
        // An ordinary machine delegates inventory drops exactly once to its base.
        var ordinary=create(world,Items.STONE,10000,100);var ordinaryPos=positions.getLast();
        var originalCrystal=ordinary.getItemHandler().getStackInSlot(2).copy();
        check(world.destroyBlock(ordinaryPos,true,player),"ordinary machine destruction");
        var ordinaryDrops=world.getEntitiesOfClass(ItemEntity.class,new AABB(ordinaryPos).inflate(1));int crystals=0;
        for(var entity:ordinaryDrops){if(ItemStack.isSameItemSameComponents(entity.getItem(),originalCrystal))crystals+=entity.getItem().getCount();}
        check(crystals==1,"ordinary stored crystal drops exactly once");
        groups.add("ordinary-break-drops-inventory-once");
    }
    public Map<String,Object> inspect(ServerLevel world,int tick) throws Exception {
        if(tick==40)start(world);
        if(tick>40&&tick<=620)observe(world);
        if(tick==43)reloadMidWork(world);
        if(tick==60){
            var once=machine(world,2);check(once.getTotalProcessed()==1&&once.getWorkMode()==mio_icif_replicator_elc.WorkMode.STOPPED,"single copy stops once");
            near((1000-once.getUuMatterAmount())/1000.0-once.getUuCreditBuckets(),STONE,"single copy exact UU");
            check(once.getEnergyStorageInternal().getAmount()==2000000-1024,"single copy exact EU");
            check(machine(world,3).getUuMatterAmount()==1000&&machine(world,3).getTotalProcessed()==0,"no EU consumes no UU");
            check(machine(world,4).getEnergyStorageInternal().getAmount()==2000000&&machine(world,4).getTotalProcessed()==0,"no UU consumes no EU");
            groups.add("single-mode-and-resource-starvation");
        }
        if(tick==570){stoneFull=machine(world,0).saveWithoutMetadata(world.registryAccess());ironFull=machine(world,1).saveWithoutMetadata(world.registryAccess());}
        if(tick==620){
            check(paidTicks[0]==128&&paidTicks[1]==512,"64 stone and iron have measured paid work totals");
            check(stoneFull.equals(machine(world,0).saveWithoutMetadata(world.registryAccess()))&&ironFull.equals(machine(world,1).saveWithoutMetadata(world.registryAccess())),"full output does not change saved resources or work");
            groups.add("real-ticker-reference-costs-and-full-output-idle");
            machine(world,3).getEnergyStorageInternal().setEnergy(2000000);UuQuoteBook.remove(world.getServer());
        }
        if(tick==640){
            check(machine(world,3).getTotalProcessed()==0&&machine(world,3).getEnergyStorageInternal().getAmount()==2000000,"missing current quotes cannot use stale price");
            groups.add("missing-price-authority-pauses-without-debit");quotes(world);heldBreakPlace(world);
        }
        if(tick==680){
            check(machine(world,3).getTotalProcessed()>0,"restored quote generation resumes normal ticking");
            var result=Map.of("passed",true,"assertions",assertions,"groups",groups,"scope","Actual NeoForge world with two explicit fixture prices. No production price resolver, full JVM restart, connected client, upgrades or all-item pricing acceptance.");
            Files.writeString(Path.of("replicator-world-result.json"),new Gson().toJson(result));return result;
        }
        return null;
    }
}
