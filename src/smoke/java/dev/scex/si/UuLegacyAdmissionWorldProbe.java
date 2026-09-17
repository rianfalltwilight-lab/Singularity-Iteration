// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_scanner_elc;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_replicator_elc;
import com.singularity_iteration.mio_icif.Blocks.Environment.fluid.mio_icif_fluids;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_scanner_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_replicator_elc;
import com.singularity_iteration.mio_icif.Items.Resource.mio_icif_memory;
import dev.scex.si.processing.UuMappedCatalog;
import dev.scex.si.processing.UuPricingLifecycle;
import dev.scex.si.processing.UuQuoteBook;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** R130 admission: normal loader and real scanner tickers; never installs quotes or edits progress. */
public final class UuLegacyAdmissionWorldProbe {
    private static final int COUNT=32, MACHINES=64;
    private final JsonObject fixture;
    private final ItemStack[] inputs=new ItemStack[MACHINES];
    private final long[] funded=new long[MACHINES];
    private final boolean[] denied=new boolean[COUNT];
    private final ItemStack[] staleCrystals=new ItemStack[4];
    private final List<String> groups=new ArrayList<>();
    private final List<Map<String,Object>> identities=new ArrayList<>();
    private final BlockPos origin;
    private mio_icif_memory memory;
    private long generation;
    private int assertions;
    private boolean initialized;
    private String probeHash;
    private List<Map<String,Object>> terminalAccounts;

    public UuLegacyAdmissionWorldProbe()throws Exception{
        fixture=JsonParser.parseString(Files.readString(Path.of("uu-legacy-admission-world.json"))).getAsJsonObject();
        if(fixture.get("schema").getAsInt()!=1)throw new IllegalArgumentException("R133 fixture schema");
        var xyz=fixture.getAsJsonArray("origin");
        origin=new BlockPos(xyz.get(0).getAsInt(),xyz.get(1).getAsInt(),xyz.get(2).getAsInt());
    }
    private void check(boolean value,String why){assertions++;if(!value)throw new AssertionError("R133 UU admission: "+why);}
    private void near(double a,double b,String why){check(Math.abs(a-b)<1e-12,why+" actual="+a+" expected="+b);}
    private static String hash(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    private BlockPos pos(int i){return origin.offset((i%8)*4,0,(i/8)*4);}
    private mio_icif_scanner_elc machine(ServerLevel world,int i){
        var tile=world.getBlockEntity(pos(i));check(tile instanceof mio_icif_scanner_elc,"Real registered scanner "+i);return (mio_icif_scanner_elc)tile;
    }
    private mio_icif_replicator_elc replicator(ServerLevel world,int i){
        var tile=world.getBlockEntity(pos(MACHINES+i));check(tile instanceof mio_icif_replicator_elc,"Actual stale-memory replicator "+i);return (mio_icif_replicator_elc)tile;
    }
    private ItemStack stack(ServerLevel world,JsonObject entry){
        String target=entry.get("target_item").getAsString();var id=ResourceLocation.parse(target);
        check(BuiltInRegistries.ITEM.containsKey(id),"Registered identity "+target);
        return entry.has("target_stack")?UuMappedCatalog.explicitStack(entry.get("target_stack").getAsString(),target,world.registryAccess()):new ItemStack(BuiltInRegistries.ITEM.get(id));
    }
    private JsonObject resource(ServerLevel world,String name,String expectedHash)throws Exception{
        var id=ResourceLocation.fromNamespaceAndPath("mio_icif","uu/"+name);
        byte[] bytes;
        try(var stream=world.getServer().getResourceManager().getResource(id).orElseThrow().open()){bytes=stream.readAllBytes();}
        check(hash(bytes).equals(expectedHash),"Normal resource manager selects exact frozen "+name);
        return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private void addEnergy(ServerLevel world,int i,long amount){
        var m=machine(world,i);long now=m.getEnergyStorageInternal().getAmount();
        check(amount>=0&&now+amount<=mio_icif_scanner_elc.DEFAULT_CAPACITY,"Bounded counted fixture funding "+i);
        m.getEnergyStorageInternal().setEnergy(now+amount);funded[i]=Math.addExact(funded[i],amount);
    }
    private void sameInput(ServerLevel world,int i){
        var actual=machine(world,i).getItem(0);check(actual.getCount()==1&&ItemStack.isSameItemSameComponents(actual,inputs[i]),"Exact input retained "+i);
    }
    private void loader(ServerLevel world){
        var report=UuPricingLifecycle.report(world.getServer());
        check(report!=null&&report.generation()==generation&&UuQuoteBook.generation(world.getServer())==generation,"Valid immutable normal generation remains active");
    }
    private void catalog(ServerLevel world)throws Exception{
        var doc=resource(world,"mapped_ic2.json",fixture.get("mapped_sha256").getAsString());
        check(doc.get("schema").getAsInt()==3&&doc.getAsJsonArray("entries").size()==379,"Frozen schema3 catalog has379 rows");
        int finite=0,negative=0;var targets=new HashSet<String>();
        for(var value:doc.getAsJsonArray("entries")){
            var row=value.getAsJsonObject();String id=row.get("target_item").getAsString();check(targets.add(id),"Unique mapped target "+id);
            var item=stack(world,row);var a=UuQuoteBook.classify(world.getServer(),item);
            check(a.generation()==generation,"Exact row has active generation "+id);
            if(row.get("raw_value").getAsString().equals("Infinity")){
                negative++;check(a.disposition()==UuQuoteBook.Disposition.KNOWN_DENIED&&a.finite()==null,"Known exclusion remains distinct from missing identity "+id);
            }else{
                finite++;check(a.disposition()==UuQuoteBook.Disposition.FINITE&&a.finite()!=null&&!a.deniedScanEligible(),"Finite anchor "+id);
                near(a.finite().buckets(),Double.parseDouble(row.get("raw_value").getAsString())/100000,"Authoritative price "+id);
            }
        }
        check(finite==165&&negative==214,"All379 explicit rows:165finite214known-denied");
        var eligibility=resource(world,"scan_eligibility.json",fixture.get("eligibility_sha256").getAsString());
        check(eligibility.getAsJsonArray("denied_scan_items").size()==22&&eligibility.getAsJsonArray("denied_scan_stacks").size()==33,"Original41 plus14 explicit observed prototypes");
        var names=new HashSet<String>();
        for(var value:eligibility.getAsJsonArray("denied_scan_items")){
            var row=new JsonObject();row.addProperty("target_item",value.getAsString());admitted(world,row,names);
        }
        for(var value:eligibility.getAsJsonArray("denied_scan_stacks"))admitted(world,value.getAsJsonObject(),names);
        check(names.size()==55,"All55 eligibility identities checked via ordinary loader");
        groups.add("all379-exact-prices-denials-and-all55-eligibility-identities");
    }
    private void admitted(ServerLevel world,JsonObject row,HashSet<String> names){
        var id=row.get("target_item").getAsString();check(names.add(id),"Unique admission "+id);
        var a=UuQuoteBook.classify(world.getServer(),stack(world,row));
        check(a.disposition()==UuQuoteBook.Disposition.KNOWN_DENIED&&a.deniedScanEligible()&&a.finite()==null&&a.generation()==generation,"Exact admitted negative prototype "+id);
    }
    private void initialize(ServerLevel world)throws Exception{
        try(var stream=UuLegacyAdmissionWorldProbe.class.getResourceAsStream("UuLegacyAdmissionWorldProbe.class")){check(stream!=null,"Own compiled probe");probeHash=hash(stream.readAllBytes());}
        check(probeHash.equals(fixture.get("probe_class_sha256").getAsString()),"Frozen new probe class identity");
        generation=UuQuoteBook.generation(world.getServer());check(generation>0,"Normal startup generation");loader(world);catalog(world);
        var blocks=BuiltInRegistries.BLOCK.stream().filter(b->b instanceof mio_icif_block_scanner_elc).toList();
        var memories=BuiltInRegistries.ITEM.stream().filter(i->i instanceof mio_icif_memory).toList();
        check(blocks.size()==1&&memories.size()==1,"Unique actual scanner and memory registration");memory=(mio_icif_memory)memories.getFirst();
        var rows=fixture.getAsJsonArray("added_rows");check(rows.size()==COUNT,"All32 observed prototypes");
        var ops=world.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for(int i=0;i<COUNT;i++){
            var row=rows.get(i).getAsJsonObject();inputs[i]=stack(world,row);denied[i]=row.get("raw_value").getAsString().equals("Infinity");
            check(inputs[i].getCount()==1,"One actual default input "+i);
            var actualComponents=DataComponentMap.CODEC.encodeStart(ops,inputs[i].getComponents()).getOrThrow();
            check(actualComponents.equals(TagParser.parseTag(row.get("full_default_components").getAsString())),"All R129 full default components preserved "+i);
            var a=UuQuoteBook.classify(world.getServer(),inputs[i]);
            check(a.disposition()==(denied[i]?UuQuoteBook.Disposition.KNOWN_DENIED:UuQuoteBook.Disposition.FINITE)&&a.deniedScanEligible()==denied[i],"New default classification and eligibility "+i);
            var changed=row.getAsJsonObject("changed_state");inputs[COUNT+i]=ItemStack.parse(world.registryAccess(),TagParser.parseTag(changed.get("stack").getAsString())).orElseThrow();
            check(!ItemStack.isSameItemSameComponents(inputs[i],inputs[COUNT+i]),"Real one-state change or explicit cable marker is distinct "+i);
            var b=UuQuoteBook.classify(world.getServer(),inputs[COUNT+i]);
            check(b.disposition()==UuQuoteBook.Disposition.UNSUPPORTED&&!b.deniedScanEligible()&&b.finite()==null,"Changed state has neither quote nor scan eligibility "+i);
            identities.add(Map.of("source_row",row.get("source_row").getAsInt(),"target_item",row.get("target_item").getAsString(),
                "default_stack",inputs[i].save(world.registryAccess()).toString(),"full_components",actualComponents.toString(),
                "changed_stack",inputs[COUNT+i].save(world.registryAccess()).toString(),"default_disposition",a.disposition().name(),"denied_scan_eligible",a.deniedScanEligible()));
        }
        for(int i=0;i<MACHINES;i++){
            check(world.isEmptyBlock(pos(i)),"Isolated empty fixture position "+i);
            check(world.setBlockAndUpdate(pos(i),blocks.getFirst().defaultBlockState()),"Place actual scanner "+i);
            var m=machine(world,i);m.setItem(0,inputs[i].copy());m.setItem(2,new ItemStack(memory));sameInput(world,i);addEnergy(world,i,512000);
        }
        var reps=BuiltInRegistries.BLOCK.stream().filter(b->b instanceof mio_icif_block_replicator_elc).toList();
        var stale=fixture.getAsJsonArray("stale_finite_memories");check(reps.size()==1&&stale.size()==4,"Four prior finite anchors now denied");
        for(int i=0;i<4;i++){
            var row=stale.get(i).getAsJsonObject();var old=stack(world,row);var a=UuQuoteBook.classify(world.getServer(),old);
            check(a.disposition()==UuQuoteBook.Disposition.KNOWN_DENIED&&a.finite()==null,"Prior derived price cannot override exact exclusion "+i);
            check(world.isEmptyBlock(pos(MACHINES+i))&&world.setBlockAndUpdate(pos(MACHINES+i),reps.getFirst().defaultBlockState()),"Place isolated stale-memory machine "+i);
            var crystal=new ItemStack(memory);check(memory.tryStoreData(crystal,old,row.get("prior_buckets").getAsDouble(),844800),"Prepare observed old finite price through public memory API "+i);
            staleCrystals[i]=crystal.copy();var m=replicator(world,i);m.setItem(mio_icif_replicator_elc.MEMORY_SLOT,crystal);m.getEnergyStorageInternal().setEnergy(2000000);
            check(m.getFluidHandlerCapability(Direction.UP).fill(new FluidStack(mio_icif_fluids.UUMATTER.get(),4000),IFluidHandler.FluidAction.EXECUTE)==4000,"Normal public UU supply "+i);m.generateOnce();
        }
        groups.add("32-full-default-snapshots-and32-distinct-state-controls");initialized=true;
    }
    private void accounting(ServerLevel world){
        loader(world);
        for(int i=0;i<MACHINES;i++){
            var m=machine(world,i);check(!m.hasHeldScanData(),"Unambiguous actual scan state "+i);
            long paid=funded[i]-m.getEnergyStorageInternal().getAmount();
            if(i<COUNT){check(m.getProgress()>=0&&m.getProgress()<=3300&&paid==256L*m.getProgress(),"Conserved actual EU equals paid work "+i);}
            else{check(m.getProgress()==0&&paid==0&&!m.isScanComplete()&&!m.isDeniedScanComplete(),"Unsupported state never pays or completes "+i);sameInput(world,i);}
        }
    }
    private List<Map<String,Object>> accounts(ServerLevel world){
        var out=new ArrayList<Map<String,Object>>();
        for(int i=0;i<MACHINES;i++){
            var m=machine(world,i);var row=new LinkedHashMap<String,Object>();
            row.put("index",i);row.put("source_row",fixture.getAsJsonArray("added_rows").get(i%COUNT).getAsJsonObject().get("source_row").getAsInt());
            row.put("control",i>=COUNT);row.put("progress",m.getProgress());row.put("state",m.getScanState().name());
            row.put("funded_eu",funded[i]);row.put("remaining_eu",m.getEnergyStorageInternal().getAmount());row.put("paid_eu",funded[i]-m.getEnergyStorageInternal().getAmount());
            row.put("input",m.getItem(0).isEmpty()?"{}":m.getItem(0).save(world.registryAccess()).toString());row.put("has_success_result",m.isScanComplete());row.put("denied_complete",m.isDeniedScanComplete());out.add(row);
        }
        return out;
    }
    private void staleControls(ServerLevel world){
        for(int i=0;i<4;i++){
            var m=replicator(world,i);check(m.getEnergyStorageInternal().getAmount()==2000000&&m.getUuMatterAmount()==4000,"Denied stale memory pays neither EU nor UU "+i);
            check(m.getTotalProcessed()==0&&m.getItemHandler().getStackInSlot(mio_icif_replicator_elc.OUTPUT_SLOT).isEmpty(),"Denied stale memory produces nothing "+i);
            near(m.getUuCreditBuckets(),0,"No hidden credit "+i);near(m.getProcessedUuBuckets(),0,"No hidden paid work "+i);
            check(ItemStack.matches(m.getItemHandler().getStackInSlot(mio_icif_replicator_elc.MEMORY_SLOT),staleCrystals[i]),"Unquoted old memory remains available for explicit recovery "+i);
        }
    }
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception{
        if(tick==20)initialize(world);if(!initialized)return null;
        if(tick==80)staleControls(world);
        if(tick<=3420&&tick%50==0)accounting(world);
        if(tick==1900){
            for(int i=0;i<COUNT;i++){check(machine(world,i).getProgress()>1700&&machine(world,i).getProgress()<2000,"Natural initial progress "+i);addEnergy(world,i,332800);check(funded[i]==844800,"Total exact3300tick supply "+i);}
            groups.add("counted-public-energy-fixture-and-natural-work-ledger");
        }
        if(tick==3350){
            for(int i=0;i<COUNT;i++){
                var m=machine(world,i);check(m.getProgress()==3300&&funded[i]-m.getEnergyStorageInternal().getAmount()==844800,"Natural3300ticks844800EU "+i);
                if(denied[i]){
                    sameInput(world,i);check(m.isDeniedScanComplete()&&m.getScanState()==mio_icif_scanner_elc.State.FAILED&&!m.isScanComplete()&&m.getScanResult()==null,"Paid negative is no successful pattern "+i);
                    check(!m.storeResult()&&!m.storeResult()&&!memory.hasData(m.getItem(2)),"Negative result cannot store "+i);
                }else{
                    check(m.isScanComplete()&&!m.isDeniedScanComplete()&&m.getItem(0).isEmpty(),"Finite success consumes exactly one input "+i);
                    var result=m.getScanResult();check(result!=null&&result.item.getCount()==1&&ItemStack.isSameItemSameComponents(result.item,inputs[i]),"Success retains all actual SI components "+i);
                    near(result.uuMatterCostBuckets,UuQuoteBook.quote(world.getServer(),inputs[i]).buckets(),"Finite result authoritative cost "+i);
                }
            }
            terminalAccounts=accounts(world);
            for(int i=0;i<COUNT;i++)addEnergy(world,i,1024);
            groups.add("18-finite-successes14-paid-negatives32-zero-payment-controls");
        }
        if(tick==3420){
            for(int i=0;i<COUNT;i++){
                var m=machine(world,i);check(m.getProgress()==3300&&m.getEnergyStorageInternal().getAmount()==1024,"Seventy funded terminal ticks never replay "+i);
                if(!denied[i]){
                    check(m.storeResult()&&!m.storeResult(),"Finite pattern stores exactly once "+i);var crystal=m.getItem(2);
                    check(memory.hasData(crystal)&&ItemStack.isSameItemSameComponents(memory.getStoredItemStack(crystal),inputs[i]),"Real memory preserves exact prototype "+i);
                    near(memory.getUuMatterCost(crystal),UuQuoteBook.quote(world.getServer(),inputs[i]).buckets(),"Memory price uses current quote "+i);
                }
            }
            groups.add("funded-terminal-no-replay-and18-exact-store-once-results");
        }
        if(tick==3440){
            loader(world);staleControls(world);groups.add("four-prior-finite-memories-cannot-bypass-new-denial");
            for(int i=0;i<COUNT;i++)if(denied[i]){
                var m=machine(world,i);check(m.getProgress()==3300&&m.getEnergyStorageInternal().getAmount()==1024,"Negative remains stable until explicit reset "+i);sameInput(world,i);
                m.discardResult();check(m.getProgress()==0&&ItemStack.isSameItemSameComponents(m.removeItem(0,1),inputs[i]),"Explicit reset releases original negative input "+i);
            }
            check(groups.size()==6,"Six independent groups");
            var result=new LinkedHashMap<String,Object>();result.put("passed",true);result.put("assertions",assertions);result.put("groups",groups);result.put("probe_class_sha256",probeHash);
            result.put("mapped_sha256",fixture.get("mapped_sha256").getAsString());result.put("eligibility_sha256",fixture.get("eligibility_sha256").getAsString());
            result.put("generation",generation);result.put("cases",32);result.put("finite_successes",18);result.put("paid_failures",14);result.put("unsupported_controls",32);
            result.put("identities",identities);result.put("terminal_before_store_or_reset",terminalAccounts);result.put("explicit_negative_reset_passed",true);
            result.put("stale_finite_memory_controls",4);result.put("scope","Normal candidate loader, actual3300tick scanners, SI memory storage and stale-denied copy refusal; no reference GUI storage, cold restart or arbitrary paid legacy progress migration claim");
            Files.writeString(Path.of("uu-legacy-admission-world-result.json"),new Gson().toJson(result));return result;
        }
        return null;
    }
}
