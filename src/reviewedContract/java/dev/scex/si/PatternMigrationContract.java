// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.singularity_iteration.mio_icif.Items.Resource.mio_icif_memory;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_replicator_elc;
import dev.scex.si.processing.UuPatternMigration;
import dev.scex.si.processing.UuQuoteBook;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

public final class PatternMigrationContract {
    private static int assertions;
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    private static ItemStack legacy(mio_icif_memory item,double oldPrice){
        var crystal=new ItemStack(item);var tag=new CompoundTag();tag.putString("item_id","minecraft:stone");tag.putInt("item_count",1);
        tag.putDouble("uu_matter_cost_buckets",oldPrice);tag.putLong("energy_cost",900000);tag.putString("unrelated_note","retain");
        crystal.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));return crystal;
    }
    private static final class Replicator extends mio_icif_replicator_elc {
        boolean available=true;int lookups;
        Replicator(ItemStack crystal){
            super(BlockPos.ZERO,Blocks.FURNACE.defaultBlockState(),BlockEntityType.FURNACE);
            itemHandler.setStackInSlot(MEMORY_SLOT,crystal.copy());getUuMatterTank().setFluid(new FluidStack(Fluids.WATER,1000));getEnergyStorageInternal().setEnergy(2000000);
        }
        @Override protected boolean serverThread(){return true;}
        @Override protected Fluid uuFluid(){return Fluids.WATER;}
        @Override protected long priceGeneration(){return available?1:-1;}
        @Override protected UuQuoteBook.Quote trustedQuote(ItemStack item){lookups++;return available?new UuQuoteBook.Quote(.00015,1):null;}
        void step(){onTick();tickProduction();}
    }
    private static void migration(mio_icif_memory memory){
        for(double old:new double[]{0,.0000001,99,Double.NaN,Double.POSITIVE_INFINITY,-1}){
            var input=legacy(memory,old);var snapshot=input.copy();var result=UuPatternMigration.prepare(input,item->new UuQuoteBook.Quote(.00015,1));
            check(result.status()==UuPatternMigration.Status.UPDATED,"Known identity repriced from invalid or wrong legacy number");
            check(ItemStack.matches(input,snapshot),"Migration only prepares; original inventory is unchanged");
            var fixed=result.replacement();check(memory.getStoredItemStack(fixed).is(Items.STONE)&&memory.getUuMatterCost(fixed)==.00015,"Identity and authoritative cost agree");
            check(memory.getEnergyCost(fixed)==900000&&fixed.get(DataComponents.CUSTOM_DATA).copyTag().getString("unrelated_note").equals("retain"),"Unrelated components and legacy display energy preserved");
            check(UuPatternMigration.prepare(fixed,item->new UuQuoteBook.Quote(.00015,2)).status()==UuPatternMigration.Status.UNCHANGED,"Same price migration is idempotent across generations");
            fixed.setCount(3);check(result.replacement().getCount()==1,"Prepared result is defensively owned");
            var rep=new Replicator(input);rep.generateOnce();rep.step();rep.step();
            check(rep.getItemHandler().getStackInSlot(OUTPUT_SLOT).getCount()==1&&rep.getEnergyStorageInternal().getAmount()==1998976,"Real replicator migrates then pays normal EU");
            check(Math.abs((1000-rep.getUuMatterAmount())/1000.0-rep.getUuCreditBuckets()-.00015)<1e-15,"Old free/cheap pattern cannot reduce paid UU");
            rep.step();int calls=rep.lookups;for(int i=0;i<10;i++)rep.step();check(rep.lookups==calls,"Stable crystal does not rerun full migration on every idle tick");
        }
        var original=legacy(memory,0);var waiting=UuPatternMigration.prepare(original,item->null);
        check(waiting.status()==UuPatternMigration.Status.WAITING_QUOTE&&ItemStack.matches(waiting.replacement(),original),"No quote preserves old payload without inventing cost");
        var rep=new Replicator(original);rep.available=false;rep.generateOnce();rep.step();
        check(rep.getItemHandler().getStackInSlot(OUTPUT_SLOT).isEmpty()&&rep.getEnergyStorageInternal().getAmount()==2000000,"Unknown authority cannot pay or output");
        rep.available=true;rep.step();rep.step();check(rep.getItemHandler().getStackInSlot(OUTPUT_SLOT).getCount()==1,"Later authoritative generation recovers valid identity");
        for(String type:new String[]{"future","missing-item","stacked","negative-energy","fraction-count"}){
            var bad=legacy(memory,0);var data=bad.get(DataComponents.CUSTOM_DATA).copyTag();
            switch(type){case "future"->data.putInt("scex_pattern_container",99);case "missing-item"->data.putString("item_id","unknown:unmapped");case "stacked"->bad.setCount(2);case "negative-energy"->data.putLong("energy_cost",-1);case "fraction-count"->data.putDouble("item_count",1.5);default->throw new AssertionError();}
            bad.set(DataComponents.CUSTOM_DATA,CustomData.of(data));var result=UuPatternMigration.prepare(bad,item->new UuQuoteBook.Quote(.00015,1));
            check(result.status()==UuPatternMigration.Status.HELD_IDENTITY&&ItemStack.matches(result.replacement(),bad),"Uncertain identity preserved: "+type);
        }
        var tagged=new ItemStack(Items.STONE);var extra=new CompoundTag();extra.putString("variant","Aa");tagged.set(DataComponents.CUSTOM_DATA,CustomData.of(extra));
        var crystal=new ItemStack(memory);check(memory.tryStoreData(crystal,tagged,99,0),"Component pattern prepared");
        var result=UuPatternMigration.prepare(crystal,item->{check(ItemStack.isSameItemSameComponents(item,tagged),"Quote uses full component identity");return new UuQuoteBook.Quote(.0002,1);});
        check(ItemStack.isSameItemSameComponents(memory.getStoredItemStack(result.replacement()),tagged),"Migration preserves nested components");
    }
    private static final int OUTPUT_SLOT=mio_icif_replicator_elc.OUTPUT_SLOT;
    private static void libraryRecovery(){
        var registries=net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var records=new net.minecraft.nbt.ListTag();
        for(var item:List.of(Items.STONE,Items.IRON_INGOT)){
            var tag=new CompoundTag();tag.put("item",new ItemStack(item).save(registries));tag.putDouble("uu_matter_cost_buckets",Double.NaN);tag.putLong("energy_cost",900000);records.add(tag);
        }
        var original=records.copy();var recovered=UuPatternMigration.recover(records,registries,item->new UuQuoteBook.Quote(item.is(Items.STONE)?.00015:.0007463641798863822,1));
        check(recovered!=null&&recovered.size()==2&&recovered.get(0).buckets()==.00015,"Known legacy library prices recover atomically from authoritative quote");
        check(records.equals(original),"Recovery never mutates retained original records");
        check(UuPatternMigration.recover(records,registries,item->item.is(Items.STONE)?new UuQuoteBook.Quote(.00015,1):null)==null,"One unmapped entry preserves whole library");
        var duplicate=records.copy();duplicate.add(records.getCompound(0).copy());check(UuPatternMigration.recover(duplicate,registries,item->new UuQuoteBook.Quote(.00015,1))==null,"Duplicate identities are not silently collapsed");
        var future=records.copy();future.getCompound(0).putInt("scex_pattern_version",2);check(UuPatternMigration.recover(future,registries,item->new UuQuoteBook.Quote(.00015,1))==null,"Future library schema stays held");
        var extra=records.copy();extra.getCompound(0).putString("unknown","retain");check(UuPatternMigration.recover(extra,registries,item->new UuQuoteBook.Quote(.00015,1))==null,"Unknown legacy fields stay held");
    }
    public static void main(String[] args)throws Exception{
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),java.util.Map.of());net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        net.neoforged.neoforge.registries.GameData.unfreezeData();
        var memory=Registry.register(BuiltInRegistries.ITEM,ResourceLocation.fromNamespaceAndPath("scex_contract","migration_memory"),new mio_icif_memory(new Item.Properties()));
        for(var registry:BuiltInRegistries.REGISTRY)registry.freeze();
        check(Path.of(UuPatternMigration.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[0]).toRealPath()),"Actual newly compiled migration class");
        migration(memory);libraryRecovery();System.out.println("SCEX_PATTERN_MIGRATION assertions="+assertions+" PASS scope=legacy_crystal_and_actual_replicator_no_world");
    }
}
