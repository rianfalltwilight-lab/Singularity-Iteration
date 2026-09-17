// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_scanner_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_pattern_storage;
import dev.scex.si.processing.IndependentScanSession;
import dev.scex.si.processing.IndependentUuValueIndex;
import dev.scex.si.processing.UuQuoteBook;
import dev.scex.si.processing.UuScanEligibility;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** R102 draft: actual owned machine with test-only platform; normal loader needs the separate world plan. */
public final class ScannerDeniedContract {
    private static int assertions;
    private static HolderLookup.Provider registries;
    private static void require(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    private interface Checked { void run()throws Exception; }
    private static void rejects(Checked operation,String why)throws Exception{
        try{operation.run();}catch(IllegalArgumentException|IllegalStateException|java.io.IOException expected){require(true,why);return;}
        throw new AssertionError(why);
    }
    private static final class Library extends mio_icif_pattern_storage {
        Library(){super(BlockPos.ZERO,Blocks.FURNACE.defaultBlockState(),BlockEntityType.FURNACE);getEnergyStorageInternal().setEnergy(1000);}
        @Override protected boolean operational(){return true;}
        @Override protected HolderLookup.Provider patternRegistries(){return registries;}
    }
    private static final class Machine extends mio_icif_scanner_elc {
        long time,generation=1;boolean eligible=true,storage=true;
        UuQuoteBook.Disposition disposition=UuQuoteBook.Disposition.KNOWN_DENIED;
        final Library library=new Library();
        Machine(){super(BlockPos.ZERO,Blocks.FURNACE.defaultBlockState(),BlockEntityType.FURNACE);put(new ItemStack(Items.MILK_BUCKET));}
        @Override protected boolean serverThread(){return true;}
        @Override protected long gameTime(){return time;}
        @Override protected UuQuoteBook.Quote trustedQuote(ItemStack item){return disposition==UuQuoteBook.Disposition.FINITE?new UuQuoteBook.Quote(.00015,generation):null;}
        @Override protected UuQuoteBook.Assessment trustedAssessment(ItemStack item){
            if(disposition==UuQuoteBook.Disposition.UNAVAILABLE)return new UuQuoteBook.Assessment(disposition,null,-1,false);
            if(!ItemStack.isSameItemSameComponents(item,new ItemStack(Items.MILK_BUCKET)))
                return new UuQuoteBook.Assessment(UuQuoteBook.Disposition.UNSUPPORTED,null,generation,false);
            return new UuQuoteBook.Assessment(disposition,trustedQuote(item),generation,disposition==UuQuoteBook.Disposition.KNOWN_DENIED&&eligible);
        }
        @Override protected List<mio_icif_pattern_storage> nearbyStorage(){return storage?List.of(library):List.of();}
        void put(ItemStack item){itemHandler.setStackInSlot(0,item.copy());}
        void step(){time++;tickProduction();}
        void repeat(){tickProduction();}
        long powered(){getEnergyStorageInternal().setEnergy(256);step();return 256-getEnergyStorageInternal().getAmount();}
        CompoundTag saved(){var tag=new CompoundTag();saveAdditional(tag,registries);return tag;}
    }
    private static void machine(){
        var first=new Machine();long paid=0;
        for(int i=0;i<1300;i++)paid+=first.powered();
        require(paid==332800&&first.getProgress()==1300&&!first.isScanComplete(),"Eligible known denial does real work");
        require(first.getItem(0).is(Items.MILK_BUCKET)&&first.getItemHandler().extractItem(0,1,false).isEmpty(),"Paid denied input remains owned");
        var tag=first.saved();var payload=tag.getCompound("scex_scanner_v1").getCompound("session");
        require(payload.getInt("scex_scan_version")==3&&payload.getString("completion_kind").equals("KNOWN_DENIED")&&!payload.contains("uu_buckets"),"Denial is a saved disposition, never a fake finite or Infinity price");
        var resumed=new Machine();resumed.time=first.time;resumed.loadAdditional(tag,registries);
        require(!resumed.hasHeldScanData()&&resumed.getProgress()==1300,"Paid denied work survives registry-aware save");
        resumed.getEnergyStorageInternal().setEnergy(256);resumed.repeat();
        require(resumed.getEnergyStorageInternal().getAmount()==256&&resumed.getProgress()==1300,"Saved same tick cannot debit twice");
        resumed.disposition=UuQuoteBook.Disposition.UNAVAILABLE;
        for(int i=0;i<50;i++)require(resumed.powered()==0&&resumed.getProgress()==1300,"Unavailable whole generation pauses without debit");
        resumed.disposition=UuQuoteBook.Disposition.KNOWN_DENIED;resumed.eligible=false;
        require(resumed.powered()==0&&resumed.getProgress()==1300,"Eligibility revoked pauses paid work");
        resumed.disposition=UuQuoteBook.Disposition.FINITE;
        require(resumed.powered()==0&&resumed.getProgress()==1300,"New finite quote cannot reinterpret paid negative work");
        resumed.disposition=UuQuoteBook.Disposition.KNOWN_DENIED;resumed.eligible=true;resumed.generation++;
        for(int i=1300;i<3300;i++)paid+=resumed.powered();
        require(paid==844800&&resumed.getProgress()==3300&&resumed.isDeniedScanComplete(),"Full eligible denial pays exactly 3300 times 256 EU");
        require(resumed.getScanState()==mio_icif_scanner_elc.State.FAILED&&!resumed.hasHeldScanData(),"Expected negative completion is separate from held corruption");
        require(resumed.getItem(0).is(Items.MILK_BUCKET)&&!resumed.isScanComplete()&&resumed.getScanResult()==null&&!resumed.storeResult(),"Denied completion keeps original input and has no storable result");
        require(resumed.library.getStoredCount()==0&&resumed.library.getEnergyStorageInternal().getAmount()==1000,"No library pattern or storage payment");
        for(int i=0;i<20;i++)require(resumed.powered()==0&&resumed.getProgress()==3300,"Terminal denial cannot charge another automatic scan");
        var terminal=new Machine();terminal.time=resumed.time;terminal.loadAdditional(resumed.saved(),registries);
        require(terminal.isDeniedScanComplete()&&terminal.getScanState()==mio_icif_scanner_elc.State.FAILED&&terminal.powered()==0,"Terminal denial round-trips without another debit");
        terminal.discardResult();
        require(terminal.getProgress()==0&&terminal.removeItem(0,1).is(Items.MILK_BUCKET),"Explicit normal reset releases the original input");
        var unknown=new Machine();unknown.disposition=UuQuoteBook.Disposition.UNSUPPORTED;
        require(unknown.powered()==0&&unknown.getProgress()==0,"Unknown identity never starts a scan");
        var ineligible=new Machine();ineligible.eligible=false;
        require(ineligible.powered()==0&&ineligible.getProgress()==0,"Known Infinity alone never grants eligibility");
        var noStorage=new Machine();noStorage.storage=false;
        require(noStorage.powered()==0&&noStorage.getProgress()==0,"Observed admission retains the storage precondition");
        var stateful=new Machine();var variant=new ItemStack(Items.MILK_BUCKET);var data=new CompoundTag();data.putString("state","unobserved");
        variant.set(DataComponents.CUSTOM_DATA,CustomData.of(data));stateful.put(variant);
        require(stateful.powered()==0&&stateful.getProgress()==0&&ItemStack.isSameItemSameComponents(stateful.getItem(0),variant),"No stateful normalization inferred from default eligibility");
        var malformed=tag.copy();malformed.getCompound("scex_scanner_v1").getCompound("session").putDouble("uu_buckets",0);
        var held=new Machine();held.loadAdditional(malformed,registries);held.discardResult();
        require(held.hasHeldScanData()&&held.powered()==0&&held.getItemHandler().extractItem(0,1,false).isEmpty(),"Illegal denial-price hybrid retained without debit or overwrite");
        var finite=new Machine();finite.disposition=UuQuoteBook.Disposition.FINITE;finite.powered();
        var v2=finite.saved();var oldSession=v2.getCompound("scex_scanner_v1").getCompound("session");oldSession.putInt("scex_scan_version",2);oldSession.remove("completion_kind");
        var old=new Machine();old.disposition=UuQuoteBook.Disposition.FINITE;old.time=finite.time;old.loadAdditional(v2,registries);
        require(!old.hasHeldScanData()&&old.getProgress()==1&&old.powered()==256&&old.getProgress()==2,"Existing v2 finite scan migrates without losing paid work");
    }
    private static IndependentScanSession session(){return new IndependentScanSession(()->true,3300,256);}
    private static void paymentIntent(){
        var scan=session();require(scan.beginDenied(new ItemStack(Items.MILK_BUCKET)),"Explicit item-backed denial begins");
        var partial=new IndependentScanSession.EnergyPort(){public long available(){return 256;}public long consume(long amount){return 13;}};
        require(scan.tick(1,partial)==13&&scan.progress()==0&&scan.paidTick()==13,"Partial payment cannot count a full tick");
        var loaded=session();loaded.load(scan.save(registries),registries);
        require(loaded.paidTick()==13&&loaded.completionKind()==IndependentScanSession.CompletionKind.KNOWN_DENIED,"Partial payment and completion kind survive");
        var rest=new IndependentScanSession.EnergyPort(){public long available(){return 243;}public long consume(long amount){require(amount==243,"Only unpaid remainder requested");return amount;}};
        require(loaded.tick(2,rest)==243&&loaded.progress()==1&&loaded.paidTick()==0,"Exactly one work tick after combined 256 EU");
        var intent=loaded.save(registries);intent.putLong("pending_payment",256);intent.putInt("state",IndependentScanSession.State.SCANNING.ordinal());
        var uncertain=session();uncertain.load(intent,registries);
        require(uncertain.state()==IndependentScanSession.State.FAILED&&uncertain.pendingPayment()==256&&uncertain.tick(3,rest)==0,"Saved uncertain debit is held, never replayed");
        require(uncertain.takeStoredPattern()==null&&uncertain.takeResult()==null,"Uncertain or denied work cannot leak a finite pattern");
        var original=intent.copy();original.putString("future","preserve");var future=session();future.load(original,registries);
        require(future.state()==IndependentScanSession.State.FAILED&&future.save(registries).equals(original),"Unknown schema fields remain opaque and exact");
        var full=new IndependentScanSession.EnergyPort(){public long available(){return 256;}public long consume(long amount){return amount;}};
        for(int i=3;i<=3301;i++)loaded.tick(i,full);
        require(loaded.state()==IndependentScanSession.State.COMPLETED&&loaded.progress()==3300
                &&loaded.takeStoredPattern()==null&&loaded.takeResult()==null
                &&loaded.state()==IndependentScanSession.State.COMPLETED,"Fully paid denial cannot export either public finite result type");
    }
    private static void eligibility()throws Exception{
        var milk=IndependentUuValueIndex.keyOf(new ItemStack(Items.MILK_BUCKET));var denied=Set.of(milk);
        String good="{\"schema\":1,\"denied_scan_items\":[\"minecraft:milk_bucket\"]}";
        var resolved=UuScanEligibility.read(new StringReader(good),registries,denied);
        require(resolved.equals(denied),"Explicit registered default identity receives eligibility");
        var changed=new ItemStack(Items.MILK_BUCKET);var data=new CompoundTag();data.putBoolean("state",true);changed.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
        require(!resolved.contains(IndependentUuValueIndex.keyOf(changed)),"Eligibility retains complete default components");
        for(String bad:List.of(good.replace("schema\":1","schema\":3"),good.replace("schema\":1","schema\":1,\"schema\":1"),
                good.replace("milk_bucket","missing_item"),good.replace("minecraft:milk_bucket","milk_bucket"),
                good.replace("milk_bucket\"]","milk_bucket\",\"minecraft:milk_bucket\"]"),
                good.replace("milk_bucket","stone"),good.replace("schema","future"),good+"{}",good.replace("1,","\"1\",")))
            rejects(()->UuScanEligibility.read(new StringReader(bad),registries,denied),"Invalid eligibility rejected: "+bad);
        rejects(()->UuScanEligibility.read(new StringReader(good),registries,Set.of()),"Eligibility cannot manufacture a known denial");
        rejects(()->UuScanEligibility.read(new StringReader(good.substring(0,good.length()-1)+",\"basis\":\""+"测".repeat(22000)+"\"}"),registries,denied),"UTF8 byte bound enforced");
        require(UuQuoteBook.classify(null,new ItemStack(Items.MILK_BUCKET)).disposition()==UuQuoteBook.Disposition.UNAVAILABLE,"No server authority is unavailable rather than known denied");
        rejects(()->new UuQuoteBook.Assessment(UuQuoteBook.Disposition.UNSUPPORTED,null,1,true),"Unknown identity cannot be scan eligible");
    }
    public static void main(String[] args)throws Exception{
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        require(Path.of(mio_icif_scanner_elc.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[0]).toRealPath()),"Actual candidate scanner loaded");
        machine();paymentIntent();eligibility();
        System.out.println("SCEX_SCANNER_DENIED assertions="+assertions+" PASS scope=actual_machine_test_platform_no_world");
    }
}
