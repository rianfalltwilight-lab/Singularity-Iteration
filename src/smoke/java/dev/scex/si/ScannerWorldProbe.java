// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_scanner_elc;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_pattern_storage;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_replicator_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_scanner_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_pattern_storage;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_replicator_elc;
import com.singularity_iteration.mio_icif.Blocks.Environment.fluid.mio_icif_fluids;
import com.singularity_iteration.mio_icif.Items.Resource.mio_icif_memory;
import dev.scex.si.processing.UuQuoteBook;
import dev.scex.si.processing.PatternMenuData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Two actual JVMs, actual tickers and ordinary quote startup; fixture funds are explicitly counted. */
public final class ScannerWorldProbe {
    private final BlockPos a=new BlockPos(2000,80,4),b=new BlockPos(2004,80,4),c=new BlockPos(2008,80,4),library=new BlockPos(2005,80,4),copy=new BlockPos(2016,80,4);
    private final Path checkpoint=Path.of("world/scex-scanner-checkpoint.json");
    private final List<String> groups=new ArrayList<>();
    private final boolean restart;
    private int assertions,pausedProgress;
    private long fundedA,fundedB;
    public ScannerWorldProbe()throws Exception{restart=JsonParser.parseString(Files.readString(Path.of("scanner-world.json"))).getAsJsonObject().get("phase").getAsString().equals("restart");}
    private void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError("R97 scanner: "+why);}
    private void near(double actual,double expected,String why){check(Math.abs(actual-expected)<1e-12,why+" actual="+actual);}
    private Block block(Class<?> type){var values=BuiltInRegistries.BLOCK.stream().filter(type::isInstance).toList();check(values.size()==1,"Unique actual block "+type.getSimpleName());return values.getFirst();}
    private mio_icif_scanner_elc scanner(ServerLevel world,BlockPos pos){return (mio_icif_scanner_elc)world.getBlockEntity(pos);}
    private mio_icif_pattern_storage library(ServerLevel world){return (mio_icif_pattern_storage)world.getBlockEntity(library);}
    private void power(ServerLevel world,BlockPos pos,long amount){
        var m=scanner(world,pos);long delta=amount-m.getEnergyStorageInternal().getAmount();
        if(pos.equals(a))fundedA+=delta;else if(pos.equals(b))fundedB+=delta;
        m.getEnergyStorageInternal().setEnergy(amount);
    }
    private void place(ServerLevel world,BlockPos pos,Item item,boolean crystal){
        check(world.setBlockAndUpdate(pos,block(mio_icif_block_scanner_elc.class).defaultBlockState()),"Actual scanner placed");
        var m=scanner(world,pos);m.setItem(0,new ItemStack(item));
        if(crystal){var crystals=BuiltInRegistries.ITEM.stream().filter(i->i instanceof mio_icif_memory).toList();check(crystals.size()==1,"Actual crystal identity");m.setItem(2,new ItemStack(crystals.getFirst()));}
        power(world,pos,512000);
    }
    private Map<String,Object> finish(){return Map.of("passed",true,"phase",restart?"restart":"initial","assertions",assertions,"groups",List.copyOf(groups),"fundedA",fundedA,"fundedB",fundedB,"scope","Actual scanner -> crystal/library -> replicator; real JVM restart; no full parity or connected client acceptance");}
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception{
        if(!restart){
            if(tick==20){
                check(UuQuoteBook.quote(world.getServer(),new ItemStack(Items.STONE))!=null,"Ordinary startup prices exist");
                place(world,a,Items.STONE,true);place(world,b,Items.IRON_INGOT,false);place(world,c,Items.STONE,false);
                check(world.setBlockAndUpdate(library,block(mio_icif_block_pattern_storage.class).defaultBlockState()),"Actual adjacent library placed");
                library(world).getEnergyStorageInternal().setEnergy(1000);
                groups.add("registered-scanners-and-production-prices");
            }
            if(tick==100){
                check(scanner(world,a).getProgress()==80&&scanner(world,b).getProgress()==80,"Ordinary block tickers progress once per tick");
                check(scanner(world,c).getProgress()==0&&scanner(world,c).getEnergyStorageInternal().getAmount()==512000,"No-storage machine remains uncharged");
                check(scanner(world,a).getItemHandler().extractItem(0,1,false).isEmpty(),"Menu input remains owned while scanning");
            }
            if(tick==800){pausedProgress=scanner(world,a).getProgress();power(world,a,0);}
            if(tick==840){
                check(scanner(world,a).getProgress()==pausedProgress&&scanner(world,a).getEnergyStorageInternal().getAmount()==0,"Forty-tick power interruption preserves work");
                power(world,a,512000);groups.add("power-loss-preserves-paid-work");
            }
            if(tick==1600){
                power(world,a,0);power(world,b,0);
                int pa=scanner(world,a).getProgress(),pb=scanner(world,b).getProgress();
                check(pa==1540&&pb==1580,"Expected work before saved-JVM boundary");
                check(fundedA==(long)pa*256&&fundedB==(long)pb*256,"Funding minus removed balance equals every paid work tick");
                var data=new JsonObject();data.addProperty("progressA",pa);data.addProperty("progressB",pb);data.addProperty("fundedA",fundedA);data.addProperty("fundedB",fundedB);
                data.addProperty("gameTime",world.getGameTime());Files.writeString(checkpoint,new Gson().toJson(data));
                groups.add("uncompleted-work-checkpoint");
            }
            if(tick==1650){
                check(scanner(world,a).getProgress()==1540&&scanner(world,b).getProgress()==1580,"Checkpoint remains stable before normal save-stop");
                var result=finish();Files.writeString(Path.of("scanner-world-result.json"),new Gson().toJson(result));return result;
            }
        }else{
            if(tick==20){
                var data=JsonParser.parseString(Files.readString(checkpoint)).getAsJsonObject();fundedA=data.get("fundedA").getAsLong();fundedB=data.get("fundedB").getAsLong();
                check(world.getGameTime()>data.get("gameTime").getAsLong(),"Saved world timeline resumed in another JVM");
                check(scanner(world,a).getProgress()==data.get("progressA").getAsInt()&&scanner(world,b).getProgress()==data.get("progressB").getAsInt(),"Actual server restart retained both paid progress counters");
                check(!scanner(world,a).hasHeldScanData()&&!scanner(world,b).hasHeldScanData(),"Owned current schema resumes normally");
                check(scanner(world,a).getItem(0).is(Items.STONE)&&scanner(world,b).getItem(0).is(Items.IRON_INGOT),"Inputs survived actual disk save");
                power(world,a,512000);power(world,b,512000);groups.add("new-jvm-restores-paid-scans");
            }
            if(tick==1820){
                var ma=scanner(world,a);var mb=scanner(world,b);
                check(ma.isScanComplete()&&mb.isScanComplete()&&ma.getItem(0).isEmpty()&&mb.getItem(0).isEmpty(),"Both original inputs consumed exactly at completed scan");
                check(fundedA-ma.getEnergyStorageInternal().getAmount()==844800&&fundedB-mb.getEnergyStorageInternal().getAmount()==844800,"Both cross-JVM scans consumed observed total 844800 EU");
                check(library(world).getStoredCount()==0,"Scan completion remains separate from explicit saving");
                check(ma.storeResult()&&mb.storeResult(),"Actual crystal and library storage succeed");
                check(!ma.storeResult()&&!mb.storeResult(),"Repeated save buttons cannot replay completed results");
                var crystal=ma.getItem(2);var memory=(mio_icif_memory)crystal.getItem();
                check(memory.getStoredItemStack(crystal).is(Items.STONE),"Actual filled crystal contains selected identity");
                near(memory.getUuMatterCost(crystal),.00015,"Crystal uses authoritative fractional quote");
                near(library(world).getCurrentUuCost(),.0007463641798863822,"Library uses authoritative iron quote");
                check(library(world).getStoredCount()==1&&library(world).getEnergyStorageInternal().getAmount()==900,"Library commits one pattern and one storage payment");
                var wire=ma.getContainerData();var received=new SimpleContainerData(wire.getCount());
                for(int i=0;i<wire.getCount();i++)received.set(i,(short)wire.get(i));
                check(PatternMenuData.read(received,3,2)==ma.getEnergyStorageInternal().getAmount(),"Menu signed-short limbs retain full actual EU");
                check(world.setBlockAndUpdate(copy,block(mio_icif_block_replicator_elc.class).defaultBlockState()),"Actual replicator placed");
                var rep=(mio_icif_replicator_elc)world.getBlockEntity(copy);rep.setItem(mio_icif_replicator_elc.MEMORY_SLOT,ma.removeItem(2,1));rep.getEnergyStorageInternal().setEnergy(2000000);
                check(rep.getFluidHandlerCapability(Direction.UP).fill(new FluidStack(mio_icif_fluids.UUMATTER.get(),1000),IFluidHandler.FluidAction.EXECUTE)==1000,"Actual UU capability funded");rep.generateOnce();
                groups.add("explicit-store-and-fractional-menu-data");
            }
            if(tick==1840){
                var rep=(mio_icif_replicator_elc)world.getBlockEntity(copy);
                check(rep.getItemHandler().getStackInSlot(mio_icif_replicator_elc.OUTPUT_SLOT).getCount()==1,"Scanned crystal produced one real item");
                near((1000-rep.getUuMatterAmount())/1000.0-rep.getUuCreditBuckets(),.00015,"End-to-end copy paid exact observed UU");
                check(rep.getEnergyStorageInternal().getAmount()==1998976,"End-to-end copy paid two 512 EU work steps");
                check(scanner(world,c).getItem(0).is(Items.STONE)&&scanner(world,c).getEnergyStorageInternal().getAmount()==512000,"No-storage input and energy survived both JVMs");
                groups.add("scanner-crystal-replicator-closed-loop");
            }
            if(tick==1860){var result=finish();Files.writeString(Path.of("scanner-world-result.json"),new Gson().toJson(result));return result;}
        }
        return null;
    }
}
