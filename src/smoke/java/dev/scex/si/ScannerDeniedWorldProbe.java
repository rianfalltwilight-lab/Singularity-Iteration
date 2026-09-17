// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_scanner_elc;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_pattern_storage;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_scanner_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_pattern_storage;
import com.singularity_iteration.mio_icif.Items.Resource.mio_icif_memory;
import dev.scex.si.processing.UuPricingLifecycle;
import dev.scex.si.processing.UuQuoteBook;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;

/** Real tickers, normal commands/reloads, immutable cold world and counted fixture funding; no quote injection. */
public final class ScannerDeniedWorldProbe {
    private static final int[] ACTIVE={0,1,5};
    private static final Path PACK=Path.of("world/datapacks/scex-r102-scanner");
    private static final Path CHECKPOINT=Path.of("world/scex-scanner-denied-checkpoint.json");
    private static final String CATALOG="observed_1122.json",ELIGIBILITY="scan_eligibility.json";
    private final Gson gson=new Gson();
    private final JsonObject fixture;
    private final boolean restart;
    private final BlockPos origin;
    private final List<String> groups=new ArrayList<>();
    private final List<Map<String,Object>> events=new ArrayList<>();
    private final long[] funded=new long[6];
    private final boolean[] parked=new boolean[6];
    private final ItemStack[] inputs=new ItemStack[6];
    private int assertions,stage,pendingTick,holdUntil,nextAction;
    private long originalGeneration,priorGeneration;
    private int[] pausedProgress;
    private long[] pausedEnergy;
    private String baselineCatalog,baselineEligibility,conflictingCatalog,probeHash;
    private mio_icif_memory memory;
    private boolean initialized;

    public ScannerDeniedWorldProbe()throws Exception{
        fixture=JsonParser.parseString(Files.readString(Path.of("scanner-denied-world.json"))).getAsJsonObject();
        if(fixture.get("schema").getAsInt()!=1)throw new IllegalArgumentException("R102 fixture schema");
        String phase=fixture.get("phase").getAsString();
        if(!phase.equals("initial")&&!phase.equals("restart"))throw new IllegalArgumentException("R102 fixture phase");
        restart=phase.equals("restart");var xyz=fixture.getAsJsonArray("origin");
        origin=new BlockPos(xyz.get(0).getAsInt(),xyz.get(1).getAsInt(),xyz.get(2).getAsInt());
    }
    private void check(boolean value,String why){assertions++;if(!value)throw new AssertionError("R102 denied world: "+why);}
    private static String hash(byte[] data)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    private String hash(String text)throws Exception{return hash(text.getBytes(StandardCharsets.UTF_8));}
    private BlockPos pos(int i){return origin.offset(i*4,0,0);}
    private Block block(Class<?> type){var all=BuiltInRegistries.BLOCK.stream().filter(type::isInstance).toList();check(all.size()==1,"Unique registered "+type.getSimpleName());return all.getFirst();}
    private mio_icif_scanner_elc machine(ServerLevel world,int i){var tile=world.getBlockEntity(pos(i));check(tile instanceof mio_icif_scanner_elc,"Actual scanner "+i);return (mio_icif_scanner_elc)tile;}
    private mio_icif_pattern_storage library(ServerLevel world){var tile=world.getBlockEntity(pos(5).east());check(tile instanceof mio_icif_pattern_storage,"Actual adjacent library");return (mio_icif_pattern_storage)tile;}
    private UuPricingLifecycle.Report report(ServerLevel world){var r=UuPricingLifecycle.report(world.getServer());check(r!=null,"Ordinary loader report");return r;}
    private UuQuoteBook.Assessment classify(ServerLevel world,ItemStack item){return UuQuoteBook.classify(world.getServer(),item);}
    private void power(ServerLevel world,int i,long amount){
        var tile=machine(world,i);check(amount>=0&&amount<=512000,"Bounded fixture energy");
        funded[i]=Math.addExact(funded[i],amount-tile.getEnergyStorageInternal().getAmount());tile.getEnergyStorageInternal().setEnergy(amount);
    }
    private CompoundTag session(ServerLevel world,int i){return machine(world,i).saveWithFullMetadata(world.registryAccess()).getCompound("scex_scanner_v1").getCompound("session");}
    private void unchangedInput(ServerLevel world,int i){var now=machine(world,i).getItem(0);check(now.getCount()==1&&ItemStack.isSameItemSameComponents(now,inputs[i]),"Exact retained input "+i);}
    private void resource(ServerLevel world,String name,String expected)throws Exception{
        var id=ResourceLocation.fromNamespaceAndPath("mio_icif","uu/"+name);
        try(var reader=world.getServer().getResourceManager().getResource(id).orElseThrow().openAsReader()){
            var text=new java.io.StringWriter();reader.transferTo(text);
            check(text.toString().equals(expected),"Actual resource manager selected exact override "+name);
        }
    }
    private void writeResource(String name,String data)throws Exception{var p=PACK.resolve("data/mio_icif/uu/"+name);Files.createDirectories(p.getParent());Files.writeString(p,data);}
    private void command(ServerLevel world,int tick,String command){
        long before=UuQuoteBook.generation(world.getServer());
        world.getServer().getCommands().performPrefixedCommand(world.getServer().createCommandSourceStack(),command);
        pendingTick=tick;events.add(Map.of("tick",tick,"command",command,"generation_before",before));
    }
    private void snapshot(ServerLevel world){pausedProgress=new int[6];pausedEnergy=new long[6];for(int i=0;i<6;i++){pausedProgress[i]=machine(world,i).getProgress();pausedEnergy[i]=machine(world,i).getEnergyStorageInternal().getAmount();}}
    private void noPayment(ServerLevel world,int... indices){for(int i:indices){var m=machine(world,i);check(m.getProgress()==pausedProgress[i]&&m.getEnergyStorageInternal().getAmount()==pausedEnergy[i],"Pause preserves progress and EU "+i);unchangedInput(world,i);}}
    private void ordinaryClassifications(ServerLevel world){
        var a=classify(world,inputs[0]);var b=classify(world,inputs[1]);var c=classify(world,inputs[2]);var d=classify(world,inputs[3]);
        check(a.disposition()==UuQuoteBook.Disposition.KNOWN_DENIED&&a.deniedScanEligible()&&a.finite()==null,"Milk has explicit denial plus independent eligibility");
        check(b.disposition()==UuQuoteBook.Disposition.FINITE&&b.finite()!=null,"Finite control has authoritative quote");
        check(c.disposition()==UuQuoteBook.Disposition.UNSUPPORTED&&!c.deniedScanEligible(),"Unobserved components remain unsupported");
        check(d.disposition()==UuQuoteBook.Disposition.KNOWN_DENIED&&!d.deniedScanEligible(),"Unobserved known denial has no eligibility");
        for(int i:new int[]{0,2,3})check(UuQuoteBook.quote(world.getServer(),inputs[i])==null,"Nonfinite classification never exports a quote "+i);
    }
    private void initialize(ServerLevel world)throws Exception{
        try(var stream=ScannerDeniedWorldProbe.class.getResourceAsStream("ScannerDeniedWorldProbe.class")){check(stream!=null,"Own compiled probe present");probeHash=hash(stream.readAllBytes());}
        check(probeHash.equals(fixture.get("probe_class_sha256").getAsString()),"Frozen probe class identity");
        baselineCatalog=Files.readString(Path.of("scanner-denied-fixture/observed-baseline.json"));
        baselineEligibility=Files.readString(Path.of("scanner-denied-fixture/eligibility-baseline.json"));
        conflictingCatalog=Files.readString(Path.of("scanner-denied-fixture/observed-conflict.json"));
        check(hash(baselineCatalog).equals(fixture.get("catalog_sha256").getAsString())&&hash(baselineEligibility).equals(fixture.get("eligibility_sha256").getAsString())
                &&hash(conflictingCatalog).equals(fixture.get("conflict_sha256").getAsString()),"Frozen reload fixture bytes");
        originalGeneration=UuQuoteBook.generation(world.getServer());check(originalGeneration>0&&report(world).generation()==originalGeneration,"Normal startup generation");
        var memories=BuiltInRegistries.ITEM.stream().filter(i->i instanceof mio_icif_memory).toList();check(memories.size()==1,"Unique actual memory item");memory=(mio_icif_memory)memories.getFirst();
        for(int i=0;i<6;i++)inputs[i]=new ItemStack(i==1?Items.STONE:i==3?Items.ACACIA_BOAT:Items.MILK_BUCKET);
        var custom=new CompoundTag();custom.putBoolean("r102_unobserved",true);inputs[2].set(DataComponents.CUSTOM_DATA,CustomData.of(custom));
        ordinaryClassifications(world);resource(world,CATALOG,baselineCatalog);resource(world,ELIGIBILITY,baselineEligibility);
        var eligible=fixture.getAsJsonArray("eligible_items");var seen=new java.util.HashSet<String>();
        check(eligible.size()==fixture.get("eligibility_count").getAsInt()&&eligible.size()==13,"Frozen thirteen observed canonical eligibility identities");
        for(var value:eligible){String name=value.getAsString();check(seen.add(name),"Unique eligible identity "+name);var id=ResourceLocation.parse(name);
            check(BuiltInRegistries.ITEM.containsKey(id),"Registered eligible identity "+name);var stack=new ItemStack(BuiltInRegistries.ITEM.get(id));var assessment=classify(world,stack);
            check(!stack.isEmpty()&&assessment.disposition()==UuQuoteBook.Disposition.KNOWN_DENIED&&assessment.deniedScanEligible()&&assessment.finite()==null,"Ordinary loader authorizes canonical denial "+name);
            var restored=ItemStack.parse(world.registryAccess(),stack.save(world.registryAccess())).orElse(ItemStack.EMPTY);
            check(ItemStack.isSameItemSameComponents(stack,restored),"Eligible default components round-trip "+name);
        }
        check(seen.contains("minecraft:milk_bucket"),"Observed milk bucket remains eligible");
        if(!restart){
            check(world.setBlockAndUpdate(pos(5).east(),block(mio_icif_block_pattern_storage.class).defaultBlockState()),"Place actual library");library(world).getEnergyStorageInternal().setEnergy(1000);
            var scanner=block(mio_icif_block_scanner_elc.class);
            for(int i=0;i<6;i++){
                check(world.setBlockAndUpdate(pos(i),scanner.defaultBlockState()),"Place scanner "+i);
                machine(world,i).setItem(0,inputs[i]);if(i<4)machine(world,i).setItem(2,new ItemStack(memory));power(world,i,i==0?255:512000);
            }
            groups.add("normal-loader-distinguishes-finite-denied-unknown-and-eligibility");
        }else{
            check(hash(Files.readAllBytes(CHECKPOINT)).equals(fixture.get("checkpoint_sha256").getAsString()),"Cold checkpoint bytes from stopped initial world");
            var saved=JsonParser.parseString(Files.readString(CHECKPOINT)).getAsJsonObject();
            check(world.getGameTime()>saved.get("game_time").getAsLong(),"Saved world timeline continues in another JVM");
            var accounts=saved.getAsJsonArray("machines");check(accounts.size()==6,"All six saved accounts");
            for(int i=0;i<6;i++){
                var row=accounts.get(i).getAsJsonObject();funded[i]=row.get("funded_eu").getAsLong();
                unchangedInput(world,i);var m=machine(world,i);
                check(m.getProgress()==row.get("progress").getAsInt()&&m.getEnergyStorageInternal().getAmount()==row.get("energy").getAsLong(),"Saved paid progress and energy "+i);
                var savedItem=ItemStack.parse(world.registryAccess(),TagParser.parseTag(row.get("input_nbt").getAsString())).orElse(ItemStack.EMPTY);
                check(ItemStack.isSameItemSameComponents(savedItem,m.getItem(0)),"Saved complete input components "+i);
            }
            for(int i:ACTIVE){var s=session(world,i);check(machine(world,i).getProgress()==1300&&!machine(world,i).hasHeldScanData(),"1300 paid work resumes "+i);
                check(s.getString("completion_kind").equals(i==1?"FINITE":"KNOWN_DENIED")&&s.getLong("paid_tick")==0&&s.getLong("pending_payment")==0,"Saved kind and payment are stable "+i);power(world,i,512000);}
            groups.add("real-new-jvm-restores-1300-paid-ticks-and-complete-identities");
        }
        initialized=true;
    }
    private void reloads(ServerLevel world,int tick)throws Exception{
        if(stage==0&&tick==100){command(world,tick,"reload");stage=1;}
        if(stage==1){
            check(tick-pendingTick<120,"Already-enabled datapack reload completes before bounded deadline");
            if(UuQuoteBook.generation(world.getServer())>originalGeneration){resource(world,CATALOG,baselineCatalog);resource(world,ELIGIBILITY,baselineEligibility);priorGeneration=UuQuoteBook.generation(world.getServer());groups.add("ordinary-enabled-datapack-reload-publishes-new-generation");stage=2;}
        }else if(stage==2&&tick>=300){writeResource(CATALOG,conflictingCatalog);command(world,tick,"reload");stage=3;
        }else if(stage==3){
            check(tick-pendingTick<120,"Conflict reload reaches failed generation");
            if(report(world).status().equals("FAILED_NO_QUOTES")){
                check(UuQuoteBook.generation(world.getServer())==-1,"Conflict retires all old prices and eligibility");resource(world,CATALOG,conflictingCatalog);
                for(int i=0;i<6;i++)check(classify(world,inputs[i]).disposition()==UuQuoteBook.Disposition.UNAVAILABLE,"Failure is not a known denial "+i);
                snapshot(world);holdUntil=tick+50;stage=4;events.add(Map.of("tick",tick,"event","failed-generation-confirmed"));
            }
        }else if(stage==4){
            noPayment(world,0,1,2,3,4,5);
            if(tick==holdUntil){writeResource(CATALOG,baselineCatalog);command(world,tick,"reload");stage=5;}
        }else if(stage==5){
            check(tick-pendingTick<120,"Restored catalog publishes valid generation");
            if(UuQuoteBook.generation(world.getServer())>priorGeneration){resource(world,CATALOG,baselineCatalog);ordinaryClassifications(world);priorGeneration=UuQuoteBook.generation(world.getServer());groups.add("conflicting-generation-pauses-all-payment-and-restores");nextAction=tick+40;stage=6;}
        }else if(stage==6&&tick>=nextAction){
            writeResource(ELIGIBILITY,"{\"schema\":1,\"denied_scan_items\":[]}\n");command(world,tick,"reload");stage=7;
        }else if(stage==7){
            check(tick-pendingTick<120,"Eligibility-only reload completes");
            if(UuQuoteBook.generation(world.getServer())>priorGeneration){
                var a=classify(world,inputs[0]);check(a.disposition()==UuQuoteBook.Disposition.KNOWN_DENIED&&!a.deniedScanEligible(),"Revocation preserves known denial but stops admission");
                check(classify(world,inputs[1]).disposition()==UuQuoteBook.Disposition.FINITE,"Revocation keeps finite generation operational");
                resource(world,ELIGIBILITY,"{\"schema\":1,\"denied_scan_items\":[]}\n");priorGeneration=UuQuoteBook.generation(world.getServer());snapshot(world);holdUntil=tick+50;stage=8;
            }
        }else if(stage==8){
            noPayment(world,0,2,3,4,5);
            if(tick==holdUntil){check(machine(world,1).getProgress()==pausedProgress[1]+50&&machine(world,1).getEnergyStorageInternal().getAmount()==pausedEnergy[1]-12800,"Only eligible-denied work pauses; finite machine keeps paying normally");writeResource(ELIGIBILITY,baselineEligibility);command(world,tick,"reload");stage=9;}
        }else if(stage==9){
            check(tick-pendingTick<120,"Restored eligibility generation completes");
            if(UuQuoteBook.generation(world.getServer())>priorGeneration){ordinaryClassifications(world);resource(world,ELIGIBILITY,baselineEligibility);groups.add("eligibility-revocation-pauses-denied-only-and-restores");stage=10;}
        }
    }
    private void controls(ServerLevel world){
        for(int i:new int[]{2,3,4}){unchangedInput(world,i);check(machine(world,i).getProgress()==0&&machine(world,i).getEnergyStorageInternal().getAmount()==512000,"Control has never paid "+i);}
        check(library(world).getStoredCount()==0&&library(world).getEnergyStorageInternal().getAmount()==1000,"Denied library has neither pattern nor storage debit");
    }
    private List<Map<String,Object>> accounts(ServerLevel world){
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<6;i++){var m=machine(world,i);var row=new LinkedHashMap<String,Object>();row.put("index",i);row.put("progress",m.getProgress());row.put("funded_eu",funded[i]);row.put("energy",m.getEnergyStorageInternal().getAmount());row.put("consumed_eu",funded[i]-m.getEnergyStorageInternal().getAmount());row.put("input_nbt",m.getItem(0).isEmpty()?"{}":m.getItem(0).save(world.registryAccess()).toString());row.put("session_nbt",session(world,i).toString());row.put("state",m.getScanState().name());row.put("denied_complete",m.isDeniedScanComplete());rows.add(row);}
        return rows;
    }
    private Map<String,Object> finish(ServerLevel world){
        var out=new LinkedHashMap<String,Object>();out.put("passed",true);out.put("phase",restart?"restart":"initial");out.put("groups",List.copyOf(groups));out.put("assertions",assertions);out.put("probe_class_sha256",probeHash);out.put("catalog_sha256",fixture.get("catalog_sha256").getAsString());out.put("eligibility_sha256",fixture.get("eligibility_sha256").getAsString());out.put("eligibility_count",fixture.get("eligibility_count").getAsInt());out.put("eligible_items",fixture.getAsJsonArray("eligible_items"));out.put("machines",accounts(world));out.put("events",events);out.put("current_report",report(world));out.put("scope","Actual 3300x256 scan work, cold JVM continuation, normal reload failure and eligibility revocation; partial receipt and unknown debit intent are contract-only, no connected client or full parity claim");return out;
    }
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception{
        if(tick==20)initialize(world);if(!initialized)return null;
        if(!restart){
            if(tick==60){check(machine(world,0).getProgress()==0&&machine(world,0).getEnergyStorageInternal().getAmount()==255,"Forty natural ticks with255EU cannot fund a256EU work step");power(world,0,256);}
            if(tick==62){check(machine(world,0).getProgress()==1&&machine(world,0).getEnergyStorageInternal().getAmount()==0,"Adding one EU authorizes exactly one full work step");power(world,0,512000);groups.add("actual-insufficient-energy-boundary-never-creates-partial-receipt");}
            reloads(world,tick);
            for(int i:ACTIVE){int progress=machine(world,i).getProgress();check(progress<=1300,"Initial work cannot exceed checkpoint "+i);if(progress==1300&&!parked[i]){check(stage==10,"All reload tests finish before checkpoint");power(world,i,0);parked[i]=true;}}
            if(tick==1600){
                controls(world);check(stage==10,"All normal reload transitions completed");
                for(int i:ACTIVE){check(parked[i]&&machine(world,i).getProgress()==1300&&funded[i]==332800&&machine(world,i).getEnergyStorageInternal().getAmount()==0,"Exact1300ticks332800EU checkpoint "+i);unchangedInput(world,i);}
                var saved=new LinkedHashMap<String,Object>();saved.put("game_time",world.getGameTime());saved.put("machines",accounts(world));saved.put("probe_class_sha256",probeHash);saved.put("catalog_sha256",fixture.get("catalog_sha256").getAsString());
                Files.writeString(CHECKPOINT,gson.toJson(saved));groups.add("1300-paid-tick-stable-normal-save-checkpoint");
            }
            if(tick==1650){for(int i:ACTIVE)check(machine(world,i).getProgress()==1300&&machine(world,i).getEnergyStorageInternal().getAmount()==0,"Checkpoint remains stable for normal save-stop "+i);check(groups.size()==6,"Six initial groups");var result=finish(world);Files.writeString(Path.of("scanner-denied-world-result.json"),gson.toJson(result));return result;}
        }else{
            if(tick==2060){
                controls(world);
                for(int i:ACTIVE){var m=machine(world,i);check(m.getProgress()==3300&&funded[i]-m.getEnergyStorageInternal().getAmount()==844800,"Natural3300ticks exact844800EU across JVMs "+i);check(!m.hasHeldScanData(),"Known saved work is not corrupt "+i);}
                for(int i:new int[]{0,5}){var m=machine(world,i);unchangedInput(world,i);check(m.isDeniedScanComplete()&&m.getScanState()==mio_icif_scanner_elc.State.FAILED&&!m.isScanComplete()&&m.getScanResult()==null&&!m.storeResult()&&!m.storeResult(),"Completed denial retains input and never stores "+i);}
                check(!memory.hasData(machine(world,0).getItem(2)),"Denied crystal remains blank");
                var finite=machine(world,1);check(finite.isScanComplete()&&finite.getItem(0).isEmpty()&&finite.getScanResult().item.is(Items.STONE),"Finite control consumed one real input and holds result");
                // Fund completed machines so the no-replay check cannot pass merely because their buffers are empty.
                for(int i:ACTIVE)power(world,i,1024);
                snapshot(world);groups.add("natural-3300-tick-denied-completion-retains-input-and-no-pattern");
            }
            if(tick==2090){
                for(int i:ACTIVE)check(machine(world,i).getProgress()==pausedProgress[i]&&machine(world,i).getEnergyStorageInternal().getAmount()==pausedEnergy[i],"Completed work cannot restart or debit automatically "+i);
                var terminalAccounts=accounts(world);
                var finite=machine(world,1);check(finite.storeResult()&&!finite.storeResult(),"Finite result stores exactly once");var crystal=finite.getItem(2);
                check(memory.hasData(crystal)&&ItemStack.isSameItemSameComponents(memory.getStoredItemStack(crystal),inputs[1]),"Finite memory retains exact item");
                var quote=UuQuoteBook.quote(world.getServer(),inputs[1]);check(quote!=null&&Math.abs(memory.getUuMatterCost(crystal)-quote.buckets())<1e-12,"Finite memory uses current authoritative quote");
                groups.add("finite-store-once-and-negative-terminal-reset");check(groups.size()==3,"Three restart groups");
                // Capture terminal accounting before exercising explicit reset; the result preserves all3300ticks.
                var result=finish(world);result.put("machines",terminalAccounts);var negative=machine(world,0);negative.discardResult();
                check(negative.getProgress()==0&&negative.removeItem(0,1).is(Items.MILK_BUCKET),"Explicit reset releases the retained original input");
                result.put("assertions",assertions);result.put("negative_reset_released_original",true);
                Files.writeString(Path.of("scanner-denied-world-result.json"),gson.toJson(result));return result;
            }
        }
        return null;
    }
}
