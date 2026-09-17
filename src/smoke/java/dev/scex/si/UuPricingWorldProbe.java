// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_replicator_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_replicator_elc;
import com.singularity_iteration.mio_icif.Blocks.Environment.fluid.mio_icif_fluids;
import com.singularity_iteration.mio_icif.Items.Resource.mio_icif_memory;
import dev.scex.si.processing.UuPricingLifecycle;
import dev.scex.si.processing.UuQuoteBook;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Normal constructor/start/reload path. Never calls UuQuoteBook.install or rebuild. */
public final class UuPricingWorldProbe {
    private static final double STONE=.00015,IRON=.0007463641798863822;
    private final BlockPos stonePos=new BlockPos(2000,80,4),ironPos=new BlockPos(2004,80,4);
    private final Path pack=Path.of("world/datapacks/scex-uu-pricing-test");
    private final List<String> groups=new ArrayList<>();
    private final List<UuPricingLifecycle.Report> timings=new ArrayList<>();
    private String catalog;private int assertions;private long originalGeneration,reloadGeneration;
    private long stoppedEu;private int stoppedFluid;private double stoppedCredit,ironProgress;
    private void check(boolean ok,String label){assertions++;if(!ok)throw new AssertionError("R96 UU: "+label);}
    private void near(double a,double b,String label){check(Math.abs(a-b)<1e-12,label+" actual="+a+" expected="+b);}
    private mio_icif_replicator_elc machine(ServerLevel world,BlockPos pos){return (mio_icif_replicator_elc)world.getBlockEntity(pos);}
    private void create(ServerLevel world,BlockPos pos,Item item,long eu){
        var blocks=BuiltInRegistries.BLOCK.stream().filter(b->b instanceof mio_icif_block_replicator_elc).toList();
        var memories=BuiltInRegistries.ITEM.stream().filter(i->i instanceof mio_icif_memory).toList();
        check(blocks.size()==1&&memories.size()==1,"Registered machine and crystal identities");
        check(world.setBlockAndUpdate(pos,blocks.getFirst().defaultBlockState()),"Actual machine placement");
        var crystal=new ItemStack(memories.getFirst());
        check(((mio_icif_memory)memories.getFirst()).tryStoreData(crystal,new ItemStack(item),99,999999),"Deliberately stale crystal price");
        var tile=machine(world,pos);tile.setItem(mio_icif_replicator_elc.MEMORY_SLOT,crystal);
        tile.getEnergyStorageInternal().setEnergy(eu);
        check(tile.getFluidHandlerCapability(Direction.UP).fill(new FluidStack(mio_icif_fluids.UUMATTER.get(),1000),IFluidHandler.FluidAction.EXECUTE)==1000,"Normal fluid capability");
        tile.loopGeneration();
    }
    private void writeCatalog(String value)throws Exception{
        var path=pack.resolve("data/mio_icif/uu/observed_1122.json");Files.createDirectories(path.getParent());Files.writeString(path,value);
    }
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception{
        if(tick==20){
            var report=UuPricingLifecycle.report(world.getServer());
            check(report!=null&&report.generation()>=0&&report.reference()==359&&report.denied()==148,"Production startup loaded reference prices");
            originalGeneration=report.generation();
            timings.add(report);
            near(UuQuoteBook.quote(world.getServer(),new ItemStack(Items.STONE)).buckets(),STONE,"Production stone quote");
            near(UuQuoteBook.quote(world.getServer(),new ItemStack(Items.IRON_INGOT)).buckets(),IRON,"Production iron quote");
            try(var input=UuPricingLifecycle.class.getResourceAsStream("/data/mio_icif/uu/observed_1122.json")){
                check(input!=null,"Catalog included in actual mod resource overlay");catalog=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            }
            var fixture=JsonParser.parseString(Files.readString(Path.of("uu-pricing-world.json"))).getAsJsonObject();
            check(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(catalog.getBytes(java.nio.charset.StandardCharsets.UTF_8))).equals(fixture.get("catalog_sha256").getAsString()),"Actual resource hash matches source");
            create(world,stonePos,Items.STONE,2000000);create(world,ironPos,Items.IRON_INGOT,512);
            groups.add("normal-startup-reference-resource-and-generation");
        }
        if(tick==60){
            writeCatalog(catalog);
            Files.writeString(pack.resolve("pack.mcmeta"),"{\"pack\":{\"pack_format\":48,\"description\":\"Isolated UU lifecycle fixture\"}}");
            var path=pack.resolve("data/scex/recipe/uu_pricing_extension.json");Files.createDirectories(path.getParent());
            Files.writeString(path,"{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:stone\"},{\"item\":\"minecraft:stone\"}],\"result\":{\"id\":\"minecraft:amethyst_shard\",\"count\":4}}");
        }
        if(tick==170){
            var report=UuPricingLifecycle.report(world.getServer());
            check(report!=null&&report.generation()>originalGeneration,"Real datapack enable rebuilt quote generation");reloadGeneration=report.generation();
            timings.add(report);check(report.migratedItems()==0,"Unchanged catalog reuses owned identity mappings");
            near(UuQuoteBook.quote(world.getServer(),new ItemStack(Items.AMETHYST_SHARD)).buckets(),(STONE*2+.00001)/4,"Actual new crafting recipe evaluated by production loader");
            var stone=machine(world,stonePos);check(stone.getTotalProcessed()==64,"Normal lifecycle quotes produced full stone stack");
            near((1000-stone.getUuMatterAmount())/1000.0-stone.getUuCreditBuckets(),STONE*64,"Actual production quote used for fractional debit");
            check(stone.getEnergyStorageInternal().getAmount()==2000000-64*1024,"Actual EU payment");
            var iron=machine(world,ironPos);ironProgress=iron.getProcessedUuBuckets();check(ironProgress>0&&ironProgress<IRON,"Partially paid iron retained at power loss");
            groups.add("actual-datapack-reload-derived-recipe-and-production-copy");
        }
        if(tick==200){
            machine(world,stonePos).getItemHandler().extractItem(mio_icif_replicator_elc.OUTPUT_SLOT,64,false);
            writeCatalog("{\"schema\":999,\"data_version\":1343,\"entries\":[]}");
        }
        if(tick==260){
            check(UuPricingLifecycle.report(world.getServer()).status().equals("FAILED_NO_QUOTES"),"Malformed catalog clears old generation after real reload");
            check(UuQuoteBook.quote(world.getServer(),new ItemStack(Items.STONE))==null,"Old prices cannot remain authorized");
            var stone=machine(world,stonePos);stoppedEu=stone.getEnergyStorageInternal().getAmount();stoppedFluid=stone.getUuMatterAmount();stoppedCredit=stone.getUuCreditBuckets();
        }
        if(tick==310){
            var stone=machine(world,stonePos);check(stone.getEnergyStorageInternal().getAmount()==stoppedEu&&stone.getUuMatterAmount()==stoppedFluid,"No EU or UU debit while quotes invalid");near(stone.getUuCreditBuckets(),stoppedCredit,"Credit preserved during failure");
            writeCatalog(catalog);groups.add("failed-reload-invalidates-prices-without-debit");
        }
        if(tick==430){
            var report=UuPricingLifecycle.report(world.getServer());check(report.generation()>reloadGeneration,"Corrected resource resumes through real reload");
            timings.add(report);check(report.migratedItems()==0,"Recovery reuses identities without stale prices");
            check(machine(world,stonePos).getEnergyStorageInternal().getAmount()<stoppedEu,"Machine resumes after restored authority");
            near(machine(world,ironPos).getProcessedUuBuckets(),ironProgress,"Reload does not erase paid iron work");groups.add("valid-reload-recovers-with-paid-progress-preserved");
        }
        if(tick==460){
            var changed=JsonParser.parseString(catalog).getAsJsonObject();int edits=0;
            for(var entry:changed.getAsJsonArray("entries")){
                var row=entry.getAsJsonObject();if(row.get("legacy_stack").getAsString().contains("minecraft:iron_ingot")){row.addProperty("raw_value","150");edits++;}
            }
            check(edits==1,"One explicit fixture price change");writeCatalog(new Gson().toJson(changed));
        }
        if(tick==570){
            near(UuQuoteBook.quote(world.getServer(),new ItemStack(Items.IRON_INGOT)).buckets(),.0015,"Changed datapack value published");
            var report=UuPricingLifecycle.report(world.getServer());timings.add(report);check(report.migratedItems()==0,"Price-only edit does not rerun identity migration");
            var iron=machine(world,ironPos);check(iron.hasHeldReplicationData(),"Price change during paid work is held for explicit migration");
            near(iron.getProcessedUuBuckets(),ironProgress,"Held work keeps paid progress");check(iron.getTotalProcessed()==0,"No item awarded at mismatched old quote");
            groups.add("price-change-retains-paid-work-without-cheap-copy");
            var result=Map.<String,Object>of("passed",true,"assertions",assertions,"groups",groups,"timings",timings,"scope","Actual automatic startup, 4 datapack reloads, real crafting derivation and machine debit; vanilla reference subset only, no scanner replacement or full item parity");
            Files.writeString(Path.of("uu-pricing-world-result.json"),new Gson().toJson(result));return result;
        }
        return null;
    }
}
