// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_pattern_storage;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_replicator_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_pattern_storage;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_replicator_elc;
import com.singularity_iteration.mio_icif.Blocks.Environment.fluid.mio_icif_fluids;
import com.singularity_iteration.mio_icif.Items.Resource.mio_icif_memory;
import dev.scex.si.processing.UuPricingLifecycle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Ordinary registered machines and actual old NBT shapes; no product quote injection. */
public final class PatternMigrationWorldProbe {
    private int assertions;private long generation;
    private final BlockPos library=new BlockPos(2020,80,4),unknown=new BlockPos(2024,80,4),importer=new BlockPos(2028,80,4);
    private CompoundTag unresolvedBefore;
    private mio_icif_memory memory;
    private void check(boolean value,String why){assertions++;if(!value)throw new AssertionError("R98 migration: "+why);}
    private void near(double a,double b,String why){check(Math.abs(a-b)<1e-12,why);}
    private Block block(Class<?> type){var rows=BuiltInRegistries.BLOCK.stream().filter(type::isInstance).toList();check(rows.size()==1,"Unique registered "+type.getSimpleName());return rows.getFirst();}
    private BlockPos replicator(int i){return new BlockPos(2000+i*4,80,4);}
    private mio_icif_replicator_elc rep(ServerLevel world,int i){return (mio_icif_replicator_elc)world.getBlockEntity(replicator(i));}
    private mio_icif_pattern_storage storage(ServerLevel world,BlockPos pos){return (mio_icif_pattern_storage)world.getBlockEntity(pos);}
    private ItemStack legacy(double price){
        var crystal=new ItemStack(memory);var tag=new CompoundTag();tag.putString("item_id","minecraft:stone");tag.putInt("item_count",1);
        tag.putDouble("uu_matter_cost_buckets",price);tag.putLong("energy_cost",900000);tag.putString("legacy_note","keep");crystal.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));return crystal;
    }
    private void oldLibrary(ServerLevel world,BlockPos pos,boolean future){
        check(world.setBlockAndUpdate(pos,block(mio_icif_block_pattern_storage.class).defaultBlockState()),"Library placed");
        var machine=storage(world,pos);machine.getEnergyStorageInternal().setEnergy(1000);var tag=machine.saveWithoutMetadata(world.registryAccess());
        var entries=new ListTag();
        for(var item:List.of(Items.STONE,Items.IRON_INGOT)){
            var pattern=new CompoundTag();pattern.put("item",new ItemStack(item).save(world.registryAccess()));pattern.putDouble("uu_matter_cost_buckets",item==Items.STONE?0:Double.NaN);
            pattern.putLong("energy_cost",900000);if(future)pattern.putInt("scex_pattern_version",99);entries.add(pattern);
        }
        tag.put("patterns",entries);machine.loadAdditional(tag,world.registryAccess());check(machine.hasUnresolvedPatterns(),"Invalid old costs are initially retained");
        if(future){unresolvedBefore=new CompoundTag();machine.savePatternData(unresolvedBefore,world.registryAccess());}
    }
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception{
        if(tick==20){
            var report=UuPricingLifecycle.report(world.getServer());check(report!=null&&report.reference()==359,"Independent normal-start quote authority");generation=report.generation();
            var rows=BuiltInRegistries.ITEM.stream().filter(i->i instanceof mio_icif_memory).toList();check(rows.size()==1,"Registered memory item");memory=(mio_icif_memory)rows.getFirst();
            var prices=new double[]{0,Double.NaN,99};
            for(int i=0;i<3;i++){
                check(world.setBlockAndUpdate(replicator(i),block(mio_icif_block_replicator_elc.class).defaultBlockState()),"Replicator placed");
                var machine=rep(world,i);machine.setItem(mio_icif_replicator_elc.MEMORY_SLOT,legacy(prices[i]));machine.getEnergyStorageInternal().setEnergy(2000000);
                check(machine.getFluidHandlerCapability(Direction.UP).fill(new FluidStack(mio_icif_fluids.UUMATTER.get(),1000),IFluidHandler.FluidAction.EXECUTE)==1000,"Normal UU input");machine.generateOnce();
            }
            oldLibrary(world,library,false);oldLibrary(world,unknown,true);
            check(world.setBlockAndUpdate(importer,block(mio_icif_block_pattern_storage.class).defaultBlockState()),"Import library placed");
            var target=storage(world,importer);target.getEnergyStorageInternal().setEnergy(1000);target.setItem(0,legacy(0));
        }
        if(tick==80){
            for(int i=0;i<3;i++){
                var machine=rep(world,i);check(machine.getItemHandler().getStackInSlot(mio_icif_replicator_elc.OUTPUT_SLOT).getCount()==1,"Old wrong-price pattern copies exactly once");
                check(machine.getEnergyStorageInternal().getAmount()==1998976,"Normal actual EU debit");
                near((1000-machine.getUuMatterAmount())/1000.0-machine.getUuCreditBuckets(),.00015,"No zero or NaN free copy");
                var crystal=machine.getItemHandler().getStackInSlot(mio_icif_replicator_elc.MEMORY_SLOT);
                near(memory.getUuMatterCost(crystal),.00015,"Actual inventory crystal updated to authoritative quote");
                check(crystal.get(DataComponents.CUSTOM_DATA).copyTag().getString("legacy_note").equals("keep"),"Unrelated old component preserved");
            }
            var repaired=storage(world,library);check(!repaired.hasUnresolvedPatterns()&&repaired.getStoredCount()==2,"Known legacy library recovered as a complete set");
            near(repaired.getPattern(0).uuMatterCostBuckets,.00015,"Old zero library entry repriced");near(repaired.getPattern(1).uuMatterCostBuckets,.0007463641798863822,"Old NaN library entry repriced");
            check(repaired.getEnergyStorageInternal().getAmount()==1000,"Metadata migration charges no energy");
            var future=storage(world,unknown);var after=new CompoundTag();future.savePatternData(after,world.registryAccess());
            check(future.hasUnresolvedPatterns()&&after.equals(unresolvedBefore)&&future.getEnergyStorageInternal().getAmount()==1000,"Future schema remains complete and unchanged");
            var target=storage(world,importer);check(target.importMemoryPattern(),"Ordinary import migrates zero old crystal");
            near(memory.getUuMatterCost(target.getItem(0)),.00015,"Import updates actual crystal");
            check(target.getEnergyStorageInternal().getAmount()==900&&target.importMemoryPattern()&&target.getEnergyStorageInternal().getAmount()==900,"Repeated import does not double charge");
            repaired.setItem(0,new ItemStack(memory));check(repaired.exportCurrentPattern(),"Recovered library exports an actual crystal");
            near(memory.getUuMatterCost(repaired.getItem(0)),.00015,"Export agrees with current price authority");
        }
        if(tick==200){
            check(UuPricingLifecycle.report(world.getServer()).generation()>generation,"Real reload still uses independent price authority after old initializer removal");
            for(int i=0;i<3;i++)check(rep(world,i).getTotalProcessed()==1&&rep(world,i).getEnergyStorageInternal().getAmount()==1998976,"Reload does not restart completed single-copy payment");
            var repaired=storage(world,library);var saved=repaired.saveWithoutMetadata(world.registryAccess());repaired.loadAdditional(saved,world.registryAccess());
            check(!repaired.hasUnresolvedPatterns()&&repaired.getStoredCount()==2,"Migrated pattern data survives actual entity serialization");
        }
        if(tick==240){
            var result=Map.of("passed",true,"assertions",assertions,"groups",List.of("legacy-crystal-repricing-and-paid-copy","atomic-known-library-recovery-and-future-preservation","ordinary-import-export-idempotence","reload-and-migrated-save"),"scope","Actual old metadata correction; unknown paid scanner/replicator progress remains held; no connected client/full parity claim");
            Files.writeString(Path.of("pattern-migration-result.json"),new Gson().toJson(result));return result;
        }
        return null;
    }
}
