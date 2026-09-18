// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.Environment.fluid.mio_icif_fluids;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_replicator_elc;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_scanner_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_replicator_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_scanner_elc;
import com.singularity_iteration.mio_icif.Items.Resource.mio_icif_memory;
import dev.scex.si.processing.UuPricingLifecycle;
import dev.scex.si.processing.UuQuoteBook;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Real F04 chain plus a frozen-load baseline. Timings are observations, not a
 * performance acceptance threshold and never replace connected-client evidence.
 */
public final class F04LifecyclePerformanceWorldProbe {
    private static final double STONE=.00015,CHANGED_STONE=.0003;
    private static final int MACHINES=64,QUERIES_PER_TICK=1024;
    private final MinecraftServer server;
    private final Path pack=Path.of("world/datapacks/scex-f04-r172");
    private final BlockPos scannerPos=new BlockPos(25,80,24),chainPos=new BlockPos(27,80,24);
    private final List<BlockPos> machines=new ArrayList<>();
    private final List<Long> tickNanos=new ArrayList<>(),tickAllocated=new ArrayList<>(),queryNanos=new ArrayList<>();
    private final List<Map<String,Object>> reports=new ArrayList<>();
    private final com.sun.management.ThreadMXBean allocationBean;
    private boolean sampling,finished;
    private long tickStarted=-1,allocatedStarted=-1,lastGeneration=Long.MIN_VALUE,queryChecksum;
    private long heapBefore,gcCountBefore,gcMillisBefore,heapAfter,gcCountAfter,gcMillisAfter,scannerInjectedEu;
    private int assertions,scannerProgressBeforeChange;
    private String catalog;

    public F04LifecyclePerformanceWorldProbe(MinecraftServer server)throws Exception{
        this.server=server;
        var fixture=JsonParser.parseString(Files.readString(Path.of("f04-lifecycle-performance-r172.json"))).getAsJsonObject();
        check(fixture.get("machines").getAsInt()==MACHINES&&fixture.get("queries_per_tick").getAsInt()==QUERIES_PER_TICK,
            "frozen load profile");
        var bean=ManagementFactory.getThreadMXBean();
        allocationBean=bean instanceof com.sun.management.ThreadMXBean candidate&&candidate.isThreadAllocatedMemorySupported()?candidate:null;
        if(allocationBean!=null&&!allocationBean.isThreadAllocatedMemoryEnabled())allocationBean.setThreadAllocatedMemoryEnabled(true);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,this::beforeTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL,this::loadTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,this::afterTick);
    }

    private void check(boolean value,String why){assertions++;if(!value)throw new AssertionError("R172 F04: "+why);}
    private static long heap(){var runtime=Runtime.getRuntime();return runtime.totalMemory()-runtime.freeMemory();}
    private static long gcCount(){long sum=0;for(var bean:ManagementFactory.getGarbageCollectorMXBeans())if(bean.getCollectionCount()>0)sum+=bean.getCollectionCount();return sum;}
    private static long gcMillis(){long sum=0;for(var bean:ManagementFactory.getGarbageCollectorMXBeans())if(bean.getCollectionTime()>0)sum+=bean.getCollectionTime();return sum;}
    private long allocated(){return allocationBean==null?-1:allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId());}
    private void beforeTick(ServerTickEvent.Pre event){
        if(event.getServer()!=server||!sampling)return;
        tickStarted=System.nanoTime();allocatedStarted=allocated();
    }
    private void loadTick(ServerTickEvent.Pre event){
        if(event.getServer()!=server||!sampling||queryNanos.size()>=240)return;
        queryBatch(server.overworld());
    }
    private void afterTick(ServerTickEvent.Post event){
        if(event.getServer()!=server||!sampling||tickStarted<0)return;
        tickNanos.add(System.nanoTime()-tickStarted);
        long now=allocated();if(now>=0&&allocatedStarted>=0&&now>=allocatedStarted)tickAllocated.add(now-allocatedStarted);
        tickStarted=allocatedStarted=-1;
    }
    private void observeReport(ServerLevel world){
        var report=UuPricingLifecycle.report(world.getServer());
        if(report==null||report.generation()==lastGeneration)return;
        lastGeneration=report.generation();
        var row=new LinkedHashMap<String,Object>();
        row.put("generation",report.generation());row.put("reference",report.reference());row.put("derived",report.derived());
        row.put("denied",report.denied());row.put("work",report.work());row.put("catalog_ms",report.catalogMillis());
        row.put("solve_ms",report.solveMillis());row.put("status",report.status());reports.add(row);
    }
    private mio_icif_replicator_elc replicator(ServerLevel world,BlockPos pos){return (mio_icif_replicator_elc)world.getBlockEntity(pos);}
    private mio_icif_scanner_elc scanner(ServerLevel world){return (mio_icif_scanner_elc)world.getBlockEntity(scannerPos);}
    private static mio_icif_memory memoryItem(){
        var found=BuiltInRegistries.ITEM.stream().filter(mio_icif_memory.class::isInstance).toList();
        if(found.size()!=1)throw new IllegalStateException("Expected one SI memory crystal");
        return (mio_icif_memory)found.getFirst();
    }
    private static net.minecraft.world.level.block.Block replicatorBlock(){
        var found=BuiltInRegistries.BLOCK.stream().filter(mio_icif_block_replicator_elc.class::isInstance).toList();
        if(found.size()!=1)throw new IllegalStateException("Expected one SI replicator");return found.getFirst();
    }
    private static net.minecraft.world.level.block.Block scannerBlock(){
        var found=BuiltInRegistries.BLOCK.stream().filter(mio_icif_block_scanner_elc.class::isInstance).toList();
        if(found.size()!=1)throw new IllegalStateException("Expected one SI scanner");return found.getFirst();
    }
    private void fillReplicator(ServerLevel world,BlockPos pos,ItemStack crystal){
        check(world.setBlockAndUpdate(pos,replicatorBlock().defaultBlockState()),"place registered replicator "+pos);
        var machine=replicator(world,pos);machine.setItem(mio_icif_replicator_elc.MEMORY_SLOT,crystal.copy());
        machine.getEnergyStorageInternal().setEnergy(2_000_000);
        check(machine.getFluidHandlerCapability(Direction.UP).fill(new FluidStack(mio_icif_fluids.UUMATTER.get(),1000),IFluidHandler.FluidAction.EXECUTE)==1000,
            "fill replicator "+pos);
        machine.loopGeneration();
    }
    private void prepareLoad(ServerLevel world)throws Exception{
        var quote=UuQuoteBook.quote(world.getServer(),new ItemStack(Items.STONE));check(quote!=null&&quote.buckets()==STONE,"startup stone quote");
        var memory=memoryItem();var crystal=new ItemStack(memory);check(memory.tryStoreData(crystal,new ItemStack(Items.STONE),99,844800),"prepare deliberately stale memory");
        for(int i=0;i<MACHINES;i++){
            var pos=new BlockPos(16+i%8,80,16+i/8);machines.add(pos);fillReplicator(world,pos,crystal);
        }
        check(world.setBlockAndUpdate(scannerPos,scannerBlock().defaultBlockState()),"place registered scanner");
        var scanner=scanner(world);scanner.setItem(mio_icif_scanner_elc.SCANNER_SLOT,new ItemStack(Items.STONE));
        scanner.setItem(mio_icif_scanner_elc.MEMORY_SLOT,new ItemStack(memory));
        scanner.getEnergyStorageInternal().setEnergy(mio_icif_scanner_elc.DEFAULT_CAPACITY);
        scannerInjectedEu=mio_icif_scanner_elc.DEFAULT_CAPACITY;
        check(scanner.getEnergyStorageInternal().getAmount()==mio_icif_scanner_elc.DEFAULT_CAPACITY,
            "initial scanner energy respects real capacity");
        try(var input=UuPricingLifecycle.class.getResourceAsStream("/data/mio_icif/uu/observed_1122.json")){
            check(input!=null,"catalog resource present");catalog=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        var data=pack.resolve("data/mio_icif/uu/observed_1122.json");Files.createDirectories(data.getParent());Files.writeString(data,catalog);
        Files.writeString(pack.resolve("pack.mcmeta"),"{\"pack\":{\"pack_format\":48,\"description\":\"F04 R172 frozen lifecycle load\"}}");
        heapBefore=heap();gcCountBefore=gcCount();gcMillisBefore=gcMillis();sampling=true;
    }
    private void queryBatch(ServerLevel world){
        var stone=new ItemStack(Items.STONE);var iron=new ItemStack(Items.IRON_INGOT);long started=System.nanoTime();
        for(int i=0;i<QUERIES_PER_TICK;i++){
            var stack=(i&1)==0?stone:iron;var quote=UuQuoteBook.quote(world.getServer(),stack);var assessment=UuQuoteBook.classify(world.getServer(),stack);
            if(quote==null||assessment.finite()==null)throw new AssertionError("R172 F04: finite query disappeared");
            queryChecksum=Long.rotateLeft(queryChecksum,1)^Double.doubleToRawLongBits(quote.buckets())^quote.generation();
        }
        queryNanos.add(System.nanoTime()-started);
    }
    private void changeStonePrice()throws Exception{
        var changed=JsonParser.parseString(catalog).getAsJsonObject();int edits=0;
        for(var element:changed.getAsJsonArray("entries")){
            var row=element.getAsJsonObject();
            if(row.get("legacy_stack").getAsString().equals("{id:\"minecraft:stone\",Count:1b,Damage:0s}")){
                row.addProperty("raw_value","30");edits++;
            }
        }
        check(edits==1,"one explicit stone price edit");
        Files.writeString(pack.resolve("data/mio_icif/uu/observed_1122.json"),new Gson().toJson(changed));
    }
    private static Map<String,Long> distribution(List<Long> raw){
        if(raw.isEmpty())return Map.of();var values=new ArrayList<>(raw);Collections.sort(values);long sum=0;for(long value:values)sum=Math.addExact(sum,value);
        return Map.of("samples",(long)values.size(),"mean",sum/values.size(),"p50",values.get((values.size()-1)*50/100),
            "p95",values.get((values.size()-1)*95/100),"p99",values.get((values.size()-1)*99/100),"max",values.getLast());
    }
    private void verifyLoad(ServerLevel world){
        double expected=MACHINES*64*STONE;long expectedEu=2_000_000L-64L*1024L;
        for(var pos:machines){
            var machine=replicator(world,pos);check(!machine.hasHeldReplicationData()&&machine.getTotalProcessed()==64,"64-copy lifecycle "+pos);
            check(machine.getItemHandler().getStackInSlot(mio_icif_replicator_elc.OUTPUT_SLOT).getCount()==64&&machine.getEnergyStorageInternal().getAmount()==expectedEu,
                "exact output and EU "+pos);
            double used=(1000-machine.getUuMatterAmount())/1000.0-machine.getUuCreditBuckets();
            check(Math.abs(used-64*STONE)<1e-12,"exact UU conservation "+pos);
        }
        check(expected>.6&&queryNanos.size()>=230&&tickNanos.size()>=230,"frozen load sample count");
    }
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception{
        observeReport(world);
        if(tick==20)prepareLoad(world);
        if(tick==260){sampling=false;heapAfter=heap();gcCountAfter=gcCount();gcMillisAfter=gcMillis();verifyLoad(world);}
        if(tick==290){scannerProgressBeforeChange=scanner(world).getProgress();check(scannerProgressBeforeChange>0&&!scanner(world).hasHeldScanData(),"scanner paid work before price edit");}
        if(tick==300)changeStonePrice();
        if(tick==400){
            var quote=UuQuoteBook.quote(world.getServer(),new ItemStack(Items.STONE));check(quote!=null&&quote.buckets()==CHANGED_STONE,"changed price published");
            check(scanner(world).getProgress()>scannerProgressBeforeChange&&!scanner(world).hasHeldScanData(),"scanner continues fixed-EU work across changed catalog generation");
        }
        if(tick==1900){
            var scanner=scanner(world);long refill=mio_icif_scanner_elc.TOTAL_ENERGY_COST-scannerInjectedEu;
            long before=scanner.getEnergyStorageInternal().getAmount();
            check(refill>0&&before+refill<=mio_icif_scanner_elc.DEFAULT_CAPACITY,"bounded scanner refill fits real capacity");
            scanner.getEnergyStorageInternal().setEnergy(before+refill);scannerInjectedEu+=refill;
            check(scanner.getEnergyStorageInternal().getAmount()==before+refill,"bounded scanner refill applied exactly");
        }
        if(tick==3340){
            var scanner=scanner(world);check(scanner.isScanComplete()&&scanner.getItem(mio_icif_scanner_elc.SCANNER_SLOT).isEmpty()
                &&scanner.getEnergyStorageInternal().getAmount()==0,"scanner completes exactly once after four reloads");
            check(scannerInjectedEu==mio_icif_scanner_elc.TOTAL_ENERGY_COST,"scanner received exact total EU without over-capacity setup");
            check(scanner.getScanResult()!=null&&scanner.getScanResult().uuMatterCostBuckets==CHANGED_STONE,"completed scan observes current quote");
            check(scanner.storeResult(),"commit completed result to memory");
            var crystal=scanner.getItemHandler().extractItem(mio_icif_scanner_elc.MEMORY_SLOT,1,false);
            var memory=(mio_icif_memory)crystal.getItem();check(memory.getUuMatterCost(crystal)==CHANGED_STONE,"memory commit uses current generation price");
            fillReplicator(world,chainPos,crystal);replicator(world,chainPos).generateOnce();
        }
        if(tick==3360&&!finished){
            finished=true;var chain=replicator(world,chainPos);check(chain.getTotalProcessed()==1
                &&chain.getItemHandler().getStackInSlot(mio_icif_replicator_elc.OUTPUT_SLOT).is(Items.STONE),"scanner memory drives one real replication");
            check(chain.getEnergyStorageInternal().getAmount()==2_000_000-3*512,"chain exact EU at changed price");
            double used=(1000-chain.getUuMatterAmount())/1000.0-chain.getUuCreditBuckets();check(Math.abs(used-CHANGED_STONE)<1e-12,"chain exact UU at changed price");
            check(reports.size()>=5,"startup plus four real generations observed");
            var performance=new LinkedHashMap<String,Object>();performance.put("scenario_tick_wall_ns",distribution(tickNanos));
            int active=Math.min(128,tickNanos.size());
            performance.put("active_prefix_scenario_tick_wall_ns",distribution(tickNanos.subList(0,active)));
            performance.put("settled_tail_scenario_tick_wall_ns",distribution(tickNanos.subList(active,tickNanos.size())));
            performance.put("server_thread_allocated_bytes",distribution(tickAllocated));performance.put("quote_batch_ns",distribution(queryNanos));
            performance.put("query_pairs",(long)queryNanos.size()*QUERIES_PER_TICK);
            performance.put("quote_calls",(long)queryNanos.size()*QUERIES_PER_TICK);
            performance.put("classify_calls",(long)queryNanos.size()*QUERIES_PER_TICK);performance.put("machines",MACHINES);
            performance.put("heap_used_delta",heapAfter-heapBefore);performance.put("gc_count_delta",gcCountAfter-gcCountBefore);
            performance.put("gc_millis_delta",gcMillisAfter-gcMillisBefore);performance.put("query_checksum",queryChecksum);
            var result=new LinkedHashMap<String,Object>();result.put("passed",true);result.put("assertions",assertions);
            result.put("generations",reports);result.put("performance_baseline",performance);
            result.put("scope","Real scanner-catalog-replicator chain, four reloads, 64 machines and frozen query load; mixed lifecycle baseline separates the 128-tick active prefix from the output-full settled tail; scenario tick wall time includes the query batch and machine ticks but ends before scenario evidence serialization; baseline only, no performance acceptance, client or multiplayer claim");
            Files.writeString(Path.of("f04-lifecycle-performance-r172-result.json"),new Gson().toJson(result));return result;
        }
        return null;
    }
}
