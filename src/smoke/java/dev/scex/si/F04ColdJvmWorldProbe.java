// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.Environment.fluid.mio_icif_fluids;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_replicator_elc;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_scanner_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_replicator_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_scanner_elc;
import com.singularity_iteration.mio_icif.Items.Resource.mio_icif_memory;
import dev.scex.si.processing.UuPricingLifecycle;
import dev.scex.si.processing.UuQuoteBook;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Two ordinary JVMs: paid scanner/replicator state, same-price reload and changed-price hold. */
public final class F04ColdJvmWorldProbe {
    private static final double DIAMOND=.02410159310182532,DIAMOND_BLOCK=.21692433791642787;
    private static final long INITIAL_SCAN_EU=30L*mio_icif_scanner_elc.DEFAULT_ENERGY_PER_TICK;
    private static final long INITIAL_REPLICATION_EU=10L*mio_icif_replicator_elc.DEFAULT_ENERGY_PER_TICK;
    private final BlockPos scanSame=new BlockPos(32,80,16),scanChanged=new BlockPos(34,80,16);
    private final BlockPos repSame=new BlockPos(36,80,16),repChanged=new BlockPos(38,80,16);
    private final Path checkpoint=Path.of("world/scex-f04-cold-r173-checkpoint.json");
    private final Path catalogPath=Path.of("world/datapacks/scex-f04-r173/data/mio_icif/uu/observed_1122.json");
    private final boolean restart;
    private int assertions;
    private long restartGeneration,changedEnergy,scanFundedSame=INITIAL_SCAN_EU,scanFundedChanged=INITIAL_SCAN_EU;
    private int changedTank,changedOutput;
    private double changedCredit,changedProcessed;
    private long changedCompleted;

    public F04ColdJvmWorldProbe()throws Exception{
        var fixture=JsonParser.parseString(Files.readString(Path.of("f04-cold-r173.json"))).getAsJsonObject();
        restart=fixture.get("phase").getAsString().equals("restart");
        check(fixture.get("revision").getAsString().equals("R173"),"frozen revision");
    }
    private void check(boolean value,String why){assertions++;if(!value)throw new AssertionError("R173 F04 cold: "+why);}
    private void near(double actual,double expected,String why){check(Math.abs(actual-expected)<1e-12,why+" actual="+actual+" expected="+expected);}
    private Block block(Class<?> type){var found=BuiltInRegistries.BLOCK.stream().filter(type::isInstance).toList();check(found.size()==1,"unique "+type.getSimpleName());return found.getFirst();}
    private mio_icif_memory memoryItem(){var found=BuiltInRegistries.ITEM.stream().filter(mio_icif_memory.class::isInstance).toList();check(found.size()==1,"unique memory crystal");return (mio_icif_memory)found.getFirst();}
    private mio_icif_scanner_elc scanner(ServerLevel world,BlockPos pos){return (mio_icif_scanner_elc)world.getBlockEntity(pos);}
    private mio_icif_replicator_elc replicator(ServerLevel world,BlockPos pos){return (mio_icif_replicator_elc)world.getBlockEntity(pos);}
    private double quote(ServerLevel world,Item item){var quote=UuQuoteBook.quote(world.getServer(),new ItemStack(item));check(quote!=null,"finite quote "+item);return quote.buckets();}
    private double used(mio_icif_replicator_elc machine){return (1000-machine.getUuMatterAmount())/1000.0-machine.getUuCreditBuckets();}
    private void placeScanner(ServerLevel world,BlockPos pos,Item item){
        check(world.setBlockAndUpdate(pos,block(mio_icif_block_scanner_elc.class).defaultBlockState()),"place scanner "+pos);
        var machine=scanner(world,pos);machine.setItem(mio_icif_scanner_elc.SCANNER_SLOT,new ItemStack(item));
        machine.setItem(mio_icif_scanner_elc.MEMORY_SLOT,new ItemStack(memoryItem()));
        machine.getEnergyStorageInternal().setEnergy(INITIAL_SCAN_EU);
        check(machine.getEnergyStorageInternal().getAmount()==INITIAL_SCAN_EU,"initial bounded scanner energy "+pos);
    }
    private void placeReplicator(ServerLevel world,BlockPos pos,Item item,double price){
        check(world.setBlockAndUpdate(pos,block(mio_icif_block_replicator_elc.class).defaultBlockState()),"place replicator "+pos);
        var memory=memoryItem();var crystal=new ItemStack(memory);
        check(memory.tryStoreData(crystal,new ItemStack(item),price,mio_icif_scanner_elc.TOTAL_ENERGY_COST),"store current pattern "+item);
        var machine=replicator(world,pos);machine.setItem(mio_icif_replicator_elc.MEMORY_SLOT,crystal);
        machine.getEnergyStorageInternal().setEnergy(INITIAL_REPLICATION_EU);
        check(machine.getFluidHandlerCapability(Direction.UP).fill(new FluidStack(mio_icif_fluids.UUMATTER.get(),1000),IFluidHandler.FluidAction.EXECUTE)==1000,
            "fill UU "+pos);
        machine.generateOnce();
    }
    private void verifyPaused(ServerLevel world){
        for(var pos:new BlockPos[]{scanSame,scanChanged}){
            var machine=scanner(world,pos);check(machine.getProgress()==30&&machine.getEnergyStorageInternal().getAmount()==0,"scanner paused after 30 paid ticks "+pos);
            check(!machine.hasHeldScanData()&&!machine.isScanComplete(),"scanner resumable "+pos);
        }
        for(var pos:new BlockPos[]{repSame,repChanged}){
            var machine=replicator(world,pos);near(machine.getProcessedUuBuckets(),.001,"replicator ten paid steps "+pos);
            check(machine.getEnergyStorageInternal().getAmount()==0&&machine.getTotalProcessed()==0,"replicator paused before output "+pos);
            check(machine.getItemHandler().getStackInSlot(mio_icif_replicator_elc.OUTPUT_SLOT).isEmpty()&&!machine.hasHeldReplicationData(),"replicator resumable "+pos);
            near(used(machine),.001,"replicator exact initial UU debit "+pos);
        }
    }
    private void writeCheckpoint(ServerLevel world)throws Exception{
        var report=UuPricingLifecycle.report(world.getServer());check(report!=null&&report.status().equals("LOADED_SCOPED_REFERENCE_AND_RECIPES"),"initial report");
        var data=new JsonObject();data.addProperty("game_time",world.getGameTime());data.addProperty("generation",report.generation());
        data.addProperty("diamond_price",quote(world,Items.DIAMOND));data.addProperty("diamond_block_price",quote(world,Items.DIAMOND_BLOCK));
        data.addProperty("scan_progress",30);data.addProperty("rep_processed",.001);data.addProperty("rep_used",.001);
        data.addProperty("scan_funded",INITIAL_SCAN_EU);data.addProperty("rep_funded",INITIAL_REPLICATION_EU);
        Files.writeString(checkpoint,new Gson().toJson(data),StandardCharsets.UTF_8);
    }
    private void verifyRestart(ServerLevel world)throws Exception{
        var data=JsonParser.parseString(Files.readString(checkpoint)).getAsJsonObject();
        check(world.getGameTime()>data.get("game_time").getAsLong(),"saved world timeline resumed in new JVM");
        near(quote(world,Items.DIAMOND),data.get("diamond_price").getAsDouble(),"diamond startup quote restored");
        near(quote(world,Items.DIAMOND_BLOCK),data.get("diamond_block_price").getAsDouble(),"diamond block startup quote restored");
        near(DIAMOND,data.get("diamond_price").getAsDouble(),"frozen diamond reference");near(DIAMOND_BLOCK,data.get("diamond_block_price").getAsDouble(),"frozen diamond block reference");
        verifyPaused(world);
        check(scanner(world,scanSame).getItem(mio_icif_scanner_elc.SCANNER_SLOT).is(Items.DIAMOND_BLOCK)
            &&scanner(world,scanChanged).getItem(mio_icif_scanner_elc.SCANNER_SLOT).is(Items.DIAMOND),"scanner inputs restored");
        check(replicator(world,repSame).getWorkMode()==mio_icif_replicator_elc.WorkMode.SINGLE
            &&replicator(world,repChanged).getWorkMode()==mio_icif_replicator_elc.WorkMode.SINGLE,"single modes restored");
        var report=UuPricingLifecycle.report(world.getServer());check(report!=null,"restart report");restartGeneration=report.generation();
    }
    private void fundRestart(ServerLevel world){
        for(var pos:new BlockPos[]{scanSame,scanChanged}){
            var machine=scanner(world,pos);machine.getEnergyStorageInternal().setEnergy(mio_icif_scanner_elc.DEFAULT_CAPACITY);
            check(machine.getEnergyStorageInternal().getAmount()==mio_icif_scanner_elc.DEFAULT_CAPACITY,"restart scanner capacity "+pos);
        }
        scanFundedSame+=mio_icif_scanner_elc.DEFAULT_CAPACITY;scanFundedChanged+=mio_icif_scanner_elc.DEFAULT_CAPACITY;
        replicator(world,repSame).getEnergyStorageInternal().setEnergy(mio_icif_replicator_elc.DEFAULT_CAPACITY);
        replicator(world,repChanged).getEnergyStorageInternal().setEnergy(512000);
    }
    private void editDiamondPrice()throws Exception{
        var catalog=JsonParser.parseString(Files.readString(catalogPath)).getAsJsonObject();int edits=0;
        for(var value:catalog.getAsJsonArray("entries")){
            var row=value.getAsJsonObject();
            if(row.get("legacy_stack").getAsString().equals("{id:\"minecraft:diamond\",Count:1b,Damage:0s}")){
                var doubled=new BigDecimal(row.get("raw_value").getAsString()).multiply(BigDecimal.valueOf(2));
                row.addProperty("raw_value",doubled.toPlainString());edits++;
            }
        }
        check(edits==1,"one exact diamond catalog edit");Files.writeString(catalogPath,new Gson().toJson(catalog),StandardCharsets.UTF_8);
    }
    private void snapshotChanged(ServerLevel world){
        var machine=replicator(world,repChanged);changedEnergy=machine.getEnergyStorageInternal().getAmount();changedTank=machine.getUuMatterAmount();
        changedCredit=machine.getUuCreditBuckets();changedProcessed=machine.getProcessedUuBuckets();changedCompleted=machine.getTotalProcessed();
        changedOutput=machine.getItemHandler().getStackInSlot(mio_icif_replicator_elc.OUTPUT_SLOT).getCount();
    }
    private void verifyChangedFrozen(ServerLevel world,String phase){
        var machine=replicator(world,repChanged);check(machine.hasHeldReplicationData(),"changed-price replicator held "+phase);
        check(machine.getEnergyStorageInternal().getAmount()==changedEnergy&&machine.getUuMatterAmount()==changedTank,"held energy/tank unchanged "+phase);
        near(machine.getUuCreditBuckets(),changedCredit,"held credit unchanged "+phase);near(machine.getProcessedUuBuckets(),changedProcessed,"held progress unchanged "+phase);
        check(machine.getTotalProcessed()==changedCompleted&&machine.getItemHandler().getStackInSlot(mio_icif_replicator_elc.OUTPUT_SLOT).getCount()==changedOutput,
            "held completion/output unchanged "+phase);
        near(machine.getCurrentUuCostBuckets(),DIAMOND,"held machine preserves paid original price "+phase);
    }
    private Map<String,Object> result(String phase){
        var out=new LinkedHashMap<String,Object>();out.put("passed",true);out.put("phase",phase);out.put("assertions",assertions);
        out.put("scope","Two ordinary JVMs; real scanner and replicator save/recovery, same-price generation continuation, changed-price held state, exact energy/UU and explicit current-price scan commit; no client, multiplayer or performance claim");return out;
    }
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception{
        if(!restart){
            if(tick==20){
                near(quote(world,Items.DIAMOND),DIAMOND,"initial diamond quote");near(quote(world,Items.DIAMOND_BLOCK),DIAMOND_BLOCK,"initial diamond block quote");
                placeScanner(world,scanSame,Items.DIAMOND_BLOCK);placeScanner(world,scanChanged,Items.DIAMOND);
                placeReplicator(world,repSame,Items.DIAMOND_BLOCK,DIAMOND_BLOCK);placeReplicator(world,repChanged,Items.DIAMOND,DIAMOND);
            }
            if(tick==60){verifyPaused(world);writeCheckpoint(world);}
            if(tick==80){var out=result("initial");Files.writeString(Path.of("f04-cold-r173-result.json"),new Gson().toJson(out));return out;}
            return null;
        }
        if(tick==20){verifyRestart(world);fundRestart(world);}
        if(tick==80){
            var report=UuPricingLifecycle.report(world.getServer());check(report.generation()>restartGeneration,"same-price reload advances generation");restartGeneration=report.generation();
            near(quote(world,Items.DIAMOND),DIAMOND,"same-price reload diamond");near(quote(world,Items.DIAMOND_BLOCK),DIAMOND_BLOCK,"same-price reload diamond block");
            check(!replicator(world,repSame).hasHeldReplicationData()&&!replicator(world,repChanged).hasHeldReplicationData(),"same-price generation continues both replicators");
            check(scanner(world,scanSame).getProgress()>30&&scanner(world,scanChanged).getProgress()>30,"same-price generation continues both scanners");
        }
        if(tick==100)editDiamondPrice();
        if(tick==120)snapshotChanged(world);
        if(tick==140){
            var report=UuPricingLifecycle.report(world.getServer());check(report.generation()>restartGeneration,"changed-price reload advances generation");
            near(quote(world,Items.DIAMOND),DIAMOND*2,"changed diamond quote");near(quote(world,Items.DIAMOND_BLOCK),DIAMOND_BLOCK,"unchanged diamond block quote");
            verifyChangedFrozen(world,"after reload");check(!replicator(world,repSame).hasHeldReplicationData(),"same-price replicator continues after other price changed");
        }
        if(tick==2100){
            long refill=mio_icif_scanner_elc.TOTAL_ENERGY_COST-INITIAL_SCAN_EU-mio_icif_scanner_elc.DEFAULT_CAPACITY;
            for(var pos:new BlockPos[]{scanSame,scanChanged}){
                var machine=scanner(world,pos);check(machine.getEnergyStorageInternal().getAmount()==0,"scanner reaches bounded refill point "+pos);
                machine.getEnergyStorageInternal().setEnergy(refill);check(machine.getEnergyStorageInternal().getAmount()==refill,"scanner bounded refill "+pos);
            }
            scanFundedSame+=refill;scanFundedChanged+=refill;
        }
        if(tick==3390){
            var same=scanner(world,scanSame);var changed=scanner(world,scanChanged);
            check(same.isScanComplete()&&changed.isScanComplete(),"both cross-JVM scans complete");
            check(same.getItem(mio_icif_scanner_elc.SCANNER_SLOT).isEmpty()&&changed.getItem(mio_icif_scanner_elc.SCANNER_SLOT).isEmpty(),"both inputs consumed once");
            check(scanFundedSame==mio_icif_scanner_elc.TOTAL_ENERGY_COST&&scanFundedChanged==mio_icif_scanner_elc.TOTAL_ENERGY_COST,"exact total scanner funding");
            check(same.getEnergyStorageInternal().getAmount()==0&&changed.getEnergyStorageInternal().getAmount()==0,"exact total scanner debit");
            near(same.getScanResult().uuMatterCostBuckets,DIAMOND_BLOCK,"same-price scan result");near(changed.getScanResult().uuMatterCostBuckets,DIAMOND*2,"changed-price scan result");
            check(same.storeResult()&&changed.storeResult(),"explicit scan commits");check(!same.storeResult()&&!changed.storeResult(),"scan commits cannot replay");
            var memory=memoryItem();near(memory.getUuMatterCost(same.getItem(mio_icif_scanner_elc.MEMORY_SLOT)),DIAMOND_BLOCK,"same-price crystal");
            near(memory.getUuMatterCost(changed.getItem(mio_icif_scanner_elc.MEMORY_SLOT)),DIAMOND*2,"changed-price crystal uses current quote");
        }
        if(tick==3400){
            var same=replicator(world,repSame);check(same.getTotalProcessed()==1&&same.getWorkMode()==mio_icif_replicator_elc.WorkMode.STOPPED,"same-price single completes exactly once");
            check(same.getItemHandler().getStackInSlot(mio_icif_replicator_elc.OUTPUT_SLOT).is(Items.DIAMOND_BLOCK)
                &&same.getItemHandler().getStackInSlot(mio_icif_replicator_elc.OUTPUT_SLOT).getCount()==1,"same-price output exact");
            near(used(same),DIAMOND_BLOCK,"same-price cold UU conservation");
            long totalSteps=(long)Math.ceil(DIAMOND_BLOCK/.0001);check(same.getEnergyStorageInternal().getAmount()==mio_icif_replicator_elc.DEFAULT_CAPACITY-(totalSteps-10)*mio_icif_replicator_elc.DEFAULT_ENERGY_PER_TICK,"same-price cold EU conservation");
            verifyChangedFrozen(world,"final");
        }
        if(tick==3410){var out=result("restart");Files.writeString(Path.of("f04-cold-r173-result.json"),new Gson().toJson(out));return out;}
        return null;
    }
}
