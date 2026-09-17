// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_scanner_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_pattern_storage;
import dev.scex.si.processing.UuQuoteBook;
import dev.scex.si.processing.PatternMenuData;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Actual candidate machine; explicit test-only platform and quote ports, not world acceptance. */
public final class ScannerMachineContract {
    private static int assertions;
    private static HolderLookup.Provider registries;
    private static void require(boolean pass,String why){assertions++;if(!pass)throw new AssertionError(why);}
    private static final class Library extends mio_icif_pattern_storage {
        Library(){super(BlockPos.ZERO,Blocks.FURNACE.defaultBlockState(),BlockEntityType.FURNACE);getEnergyStorageInternal().setEnergy(1000);}
        @Override protected boolean operational(){return true;}
        @Override protected HolderLookup.Provider patternRegistries(){return registries;}
    }
    private static final class Machine extends mio_icif_scanner_elc {
        long time;boolean quotes=true,authority=true,storage=true;double cost=.00015;final Library library=new Library();
        Machine(){super(BlockPos.ZERO,Blocks.FURNACE.defaultBlockState(),BlockEntityType.FURNACE);put(new ItemStack(Items.STONE));}
        @Override protected boolean serverThread(){return authority;}
        @Override protected long gameTime(){return time;}
        @Override protected UuQuoteBook.Quote trustedQuote(ItemStack item){return !quotes||item.isEmpty()?null:new UuQuoteBook.Quote(cost,1);}
        @Override protected List<mio_icif_pattern_storage> nearbyStorage(){return storage?List.of(library):List.of();}
        void put(ItemStack stack){itemHandler.setStackInSlot(SCANNER_SLOT,stack.copy());}
        void step(){time++;tickProduction();}
        void repeat(){tickProduction();}
        long powered(){getEnergyStorageInternal().setEnergy(256);step();return 256-getEnergyStorageInternal().getAmount();}
        CompoundTag saved(){var tag=new CompoundTag();saveAdditional(tag,registries);return tag;}
    }
    private static void lifecycle(){
        var m=new Machine();long consumed=0;
        for(int i=0;i<1300;i++){consumed+=m.powered();require(!m.isScanComplete()&&m.getItem(0).is(Items.STONE),"Input remains until full observed scan work");}
        require(m.getProgress()==1300&&consumed==332800,"Observed 256 EU per work tick");
        var snapshot=m.saved();var resumed=new Machine();resumed.time=m.time;resumed.loadAdditional(snapshot,registries);
        require(!resumed.hasHeldScanData()&&resumed.getProgress()==1300,"In-progress component and input survive registry-aware save");
        resumed.getEnergyStorageInternal().setEnergy(256);resumed.repeat();
        require(resumed.getEnergyStorageInternal().getAmount()==256&&resumed.getProgress()==1300,"Same saved world tick cannot be debited twice");
        for(int i=1300;i<3300;i++)consumed+=resumed.powered();
        require(consumed==844800&&resumed.isScanComplete()&&resumed.getItem(0).isEmpty(),"Observed completion boundary consumes exactly one input and 844800 EU");
        var complete=resumed.saved();var loaded=new Machine();loaded.time=resumed.time;loaded.loadAdditional(complete,registries);
        require(loaded.isScanComplete()&&loaded.getScanResult().item.is(Items.STONE),"Completed result survives before explicit save action");
        loaded.getScanResult().item.setCount(32);require(loaded.getScanResult().item.getCount()==1,"Public result is defensive");
        loaded.cost=.0002;require(loaded.storeResult(),"Current authoritative price stores in adjacent library");
        require(loaded.library.getStoredCount()==1&&loaded.library.getCurrentUuCost()==.0002&&loaded.library.getCurrentEuCost()==844800,"Stale scan quote repriced when saved");
        require(!loaded.storeResult()&&loaded.library.getEnergyStorageInternal().getAmount()==900,"Repeated button cannot replay storage payment");
        loaded.put(new ItemStack(Items.STONE));long eu=loaded.powered();require(eu==0&&loaded.getScanState()==mio_icif_scanner_elc.State.ALREADY_RECORDED,"Existing component identity avoids repeat scan payment");
    }
    private static void boundaries(){
        var m=new Machine();m.storage=false;require(m.powered()==0&&m.getProgress()==0&&m.getItem(0).is(Items.STONE),"Missing storage never debits or consumes input");
        m.storage=true;m.getEnergyStorageInternal().setEnergy(255);m.step();require(m.getProgress()==0&&m.getEnergyStorageInternal().getAmount()==255,"Insufficient EU leaves account untouched");
        m.powered();m.repeat();require(m.getProgress()==1,"One scan advancement per real game tick");
        require(m.getItemHandler().extractItem(0,1,false).isEmpty()&&m.removeItemNoUpdate(0).isEmpty(),"Paid input cannot escape through menu or container");
        m.setItem(0,new ItemStack(Items.DIRT));require(m.getItem(0).is(Items.STONE),"Paid input cannot be replaced");
        m.getItem(0).setCount(0);require(m.getItem(0).getCount()==1,"Container getter cannot mutate input");
        m.quotes=false;require(m.powered()==0&&m.getProgress()==1,"Quote loss pauses without erasing paid progress");
        m.quotes=true;require(m.powered()==256&&m.getProgress()==2,"Quote restoration resumes paid progress");
        var snap=m.saved();m.authority=false;m.discardResult();require(m.getProgress()==2&&!m.storeResult(),"Client cannot discard or save");
        m.authority=true;m.discardResult();require(m.getProgress()==0&&m.getItemHandler().extractItem(0,1,false).getCount()==1,"Explicit cancellation releases unconsumed input");
        var unknown=snap.copy();unknown.getCompound("scex_scanner_v1").putString("future","keep");var future=new Machine();future.loadAdditional(unknown,registries);
        require(future.hasHeldScanData()&&future.saved().getCompound("scex_scanner_v1").getCompound("held").equals(unknown),"Unknown fields retained as opaque held data");
        future.discardResult();require(future.hasHeldScanData()&&future.getItemHandler().extractItem(0,1,false).isEmpty(),"Unknown data cannot be discarded by old menu");
        var legacy=new CompoundTag();legacy.putDouble("old_wrong_cost",.000001);var old=new Machine();old.loadAdditional(legacy,registries);
        require(old.hasHeldScanData()&&old.saved().getCompound("scex_scanner_v1").getCompound("held").equals(legacy),"Unmapped legacy paid state retained");
        var mismatch=new Machine();mismatch.loadAdditional(snap,registries);mismatch.put(new ItemStack(Items.DIRT));require(mismatch.powered()==0&&mismatch.hasHeldScanData(),"External input mutation freezes paid work without another debit");
        var tagged=new ItemStack(Items.STONE);var tag=new CompoundTag();tag.putString("variant","independent");tagged.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
        var component=new Machine();component.put(tagged);component.powered();var restored=new Machine();restored.loadAdditional(component.saved(),registries);
        require(ItemStack.isSameItemSameComponents(restored.getScannedItem(),tagged),"Component-bearing scan snapshot survives");
        var wire=m.getContainerData();m.getEnergyStorageInternal().setEnergy(512000);var received=new SimpleContainerData(wire.getCount());
        for(int i=0;i<wire.getCount();i++)received.set(i,(short)wire.get(i));
        require(PatternMenuData.read(received,3,2)==512000&&PatternMenuData.read(received,5,2)==512000,"Energy limbs survive actual signed-short transport");
        var scan=new mio_icif_scanner_elc.ScanResult(tagged,.0007463641798863822,844800);
        var roundtrip=mio_icif_scanner_elc.ScanResult.deserializeNBT(scan.serializeNBT());
        require(roundtrip!=null&&ItemStack.isSameItemSameComponents(tagged,roundtrip.item)&&roundtrip.uuMatterCostBuckets==scan.uuMatterCostBuckets,"Public ABI NBT keeps component identity and fractional price");
    }
    public static void main(String[] args)throws Exception{
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        require(Path.of(mio_icif_scanner_elc.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[0]).toRealPath()),"Actual candidate scanner loaded");
        lifecycle();boundaries();System.out.println("SCEX_SCANNER_MACHINE assertions="+assertions+" PASS scope=actual_entity_test_platform_no_world");
    }
}
