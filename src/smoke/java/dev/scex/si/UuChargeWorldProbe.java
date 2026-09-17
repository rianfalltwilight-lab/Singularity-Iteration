// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.Environment.fluid.mio_icif_fluids;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_scanner_elc;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_replicator_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_scanner_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_replicator_elc;
import com.singularity_iteration.mio_icif.Items.Resource.mio_icif_memory;
import com.singularity_iteration.mio_icif.api.item.IBatteryItem;
import dev.scex.si.processing.UuMappedCatalog;
import dev.scex.si.processing.UuQuoteBook;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Actual full-duration tickers with counted fixture funding, no quote/progress/rate overrides. */
public final class UuChargeWorldProbe {
    private final JsonObject fixture;
    private final ItemStack[] inputs=new ItemStack[12],controls=new ItemStack[12];
    private final long[] funded=new long[12],replicaFunded=new long[2];
    private final List<String> groups=new ArrayList<>();
    private final List<Map<String,Object>> scans=new ArrayList<>(),copies=new ArrayList<>();
    private final int[] finiteIndices=new int[2];
    private final double[] costs=new double[2];
    private mio_icif_memory memory;
    private int assertions;
    public UuChargeWorldProbe()throws Exception{
        fixture=JsonParser.parseString(Files.readString(Path.of("uu-charge-world.json"))).getAsJsonObject();
        check(fixture.get("schema").getAsInt()==1&&fixture.getAsJsonArray("rows").size()==12,"Twelve frozen cases");
    }
    private void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError("R108 charge: "+message);}
    private void near(double a,double b,String message){check(Math.abs(a-b)<1e-9,message+" actual="+a+" expected="+b);}
    private BlockPos pos(int i){return new BlockPos(2100+i*8,80,4);}
    private BlockPos replicaPos(int i){return new BlockPos(2196+i*4,80,4);}
    private Block block(Class<?> type){var list=BuiltInRegistries.BLOCK.stream().filter(type::isInstance).toList();check(list.size()==1,"Unique block "+type.getSimpleName());return list.getFirst();}
    private mio_icif_scanner_elc scanner(ServerLevel world,int i,boolean control){
        var tile=world.getBlockEntity(control?pos(i).east(2):pos(i));
        check(tile instanceof mio_icif_scanner_elc,"Actual scanner "+i);return (mio_icif_scanner_elc)tile;
    }
    private mio_icif_replicator_elc replica(ServerLevel world,int i){
        var tile=world.getBlockEntity(replicaPos(i));check(tile instanceof mio_icif_replicator_elc,"Actual replicator "+i);return (mio_icif_replicator_elc)tile;
    }
    private void unsupported(ServerLevel world,ItemStack stack,String message){
        var a=UuQuoteBook.classify(world.getServer(),stack);
        check(a.disposition()==UuQuoteBook.Disposition.UNSUPPORTED&&a.finite()==null&&!a.deniedScanEligible(),message+" classification="+a);
    }
    private void initialize(ServerLevel world)throws Exception{
        check(UuQuoteBook.generation(world.getServer())>0,"Actual loader generation after catalog reload regression");
        var memories=BuiltInRegistries.ITEM.stream().filter(mio_icif_memory.class::isInstance).toList();
        check(memories.size()==1,"Unique memory");memory=(mio_icif_memory)memories.getFirst();
        int finite=0;var rows=fixture.getAsJsonArray("rows");var scannerBlock=block(mio_icif_block_scanner_elc.class);
        var ops=world.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for(int i=0;i<12;i++){
            var row=rows.get(i).getAsJsonObject();String target=row.get("target_item").getAsString();
            var stack=UuMappedCatalog.explicitStack(row.get("target_stack").getAsString(),target,world.registryAccess());inputs[i]=stack;
            check(stack.getItem() instanceof IBatteryItem,"Public owned item energy interface "+target);
            var battery=(IBatteryItem)stack.getItem();long capacity=row.get("max_eu").getAsLong();
            check(battery.getEnergy(stack)==0&&battery.getMaxEnergy(stack)==capacity,"Actual empty SI energy "+target);
            var factory=new ItemStack(stack.getItem());
            var expected=TagParser.parseTag(row.get("default_components").getAsString());
            var actual=DataComponentMap.CODEC.encodeStart(ops,factory.getComponents()).getOrThrow();
            check(actual.equals(expected)&&battery.getEnergy(factory)==capacity,"All prior factory components unchanged "+target);
            var discharged=factory.copy();battery.setEnergy(discharged,0);
            check(ItemStack.isSameItemSameComponents(discharged,stack),"Ordinary SI energy setter reaches exact prototype "+target);
            var roundtrip=ItemStack.parse(world.registryAccess(),stack.save(world.registryAccess())).orElseThrow();
            check(ItemStack.isSameItemSameComponents(roundtrip,stack)&&battery.getEnergy(roundtrip)==0,"Empty save roundtrip "+target);
            var assessment=UuQuoteBook.classify(world.getServer(),stack);boolean denied=row.get("raw_value").getAsString().equals("Infinity");
            if(denied)check(assessment.disposition()==UuQuoteBook.Disposition.KNOWN_DENIED&&assessment.deniedScanEligible(),"Exact observed denial "+target);
            else{
                check(assessment.disposition()==UuQuoteBook.Disposition.FINITE,"Exact observed finite "+target);
                check(finite<2,"Bound finite count");costs[finite]=Double.parseDouble(row.get("raw_value").getAsString())/100000.0;
                near(assessment.finite().buckets(),costs[finite],"Observed canonical price "+target);finiteIndices[finite++]=i;
            }
            unsupported(world,factory,"Full default is distinct "+target);
            var charged=stack.copy();battery.setEnergy(charged,1);check(battery.getEnergy(charged)==1,"One EU variant "+target);
            unsupported(world,charged,"One EU never normalized "+target);controls[i]=charged;
            var named=stack.copy();named.set(DataComponents.CUSTOM_NAME,Component.literal("Unobserved R108"));unsupported(world,named,"Name never stripped "+target);
            check(world.setBlockAndUpdate(pos(i),scannerBlock.defaultBlockState()),"Place empty scanner");
            var machine=scanner(world,i,false);machine.setItem(0,stack);machine.setItem(2,new ItemStack(memory));
            check(ItemStack.isSameItemSameComponents(machine.getItem(0),stack),"Input accepts exact empty state");
            check(world.setBlockAndUpdate(pos(i).east(2),scannerBlock.defaultBlockState()),"Place charged control scanner");
            var control=scanner(world,i,true);control.setItem(0,charged);control.setItem(2,new ItemStack(memory));control.getEnergyStorageInternal().setEnergy(512);
            check(ItemStack.isSameItemSameComponents(control.getItem(0),charged),"Control retains charged input");
        }
        check(finite==2,"Two finite and ten denied empty states");groups.add("twelve-exact-empty-identities-and-charged-component-boundaries");
    }
    private void finishScans(ServerLevel world){
        for(int i=0;i<12;i++){
            var tile=scanner(world,i,false);long consumed=funded[i]-tile.getEnergyStorageInternal().getAmount();
            check(tile.getProgress()==3300&&!tile.hasHeldScanData()&&consumed==844800,"Full natural 3300 paid ticks "+i);
            var control=scanner(world,i,true);check(control.getProgress()==0&&control.getEnergyStorageInternal().getAmount()==512
                    &&ItemStack.isSameItemSameComponents(control.getItem(0),controls[i]),"Charged control never debits "+i);
            boolean finite=i==finiteIndices[0]||i==finiteIndices[1];
            if(finite){
                check(tile.isScanComplete()&&tile.getItem(0).isEmpty(),"Finite scan consumes exactly one input "+i);
                check(ItemStack.isSameItemSameComponents(tile.getScanResult().item,inputs[i]),"Finite result keeps empty components "+i);
            }else{
                check(tile.isDeniedScanComplete()&&!tile.isScanComplete()&&tile.getItem(0).getCount()==1
                        &&ItemStack.isSameItemSameComponents(tile.getItem(0),inputs[i]),"Paid denial retains exact input without pattern "+i);
                check(!tile.storeResult()&&!memory.hasData(tile.getItem(2)),"Denial cannot store fabricated pattern "+i);
            }
            scans.add(Map.of("target",BuiltInRegistries.ITEM.getKey(inputs[i].getItem()).toString(),"funded",funded[i],"consumed_eu",consumed,
                    "progress",3300,"finite",finite,"empty_stack",inputs[i].save(world.registryAccess()).toString()));
        }
        groups.add("twelve-full-natural-paid-scans-and-twelve-one-eu-no-payment-controls");
        var replicaBlock=block(mio_icif_block_replicator_elc.class);
        for(int i=0;i<2;i++){
            var tile=scanner(world,finiteIndices[i],false);check(tile.storeResult(),"Store completed scan in actual memory");
            var crystal=tile.removeItem(2,1);check(crystal.getCount()==1&&memory.hasData(crystal),"Extract actual scanner memory");
            check(ItemStack.isSameItemSameComponents(memory.getStoredItemStack(crystal),inputs[finiteIndices[i]]),"Stored crystal retains empty item");
            near(memory.getUuMatterCost(crystal),costs[i],"Stored crystal exact quote");
            check(world.setBlockAndUpdate(replicaPos(i),replicaBlock.defaultBlockState()),"Place actual replicator");
            var machine=replica(world,i);machine.setItem(mio_icif_replicator_elc.MEMORY_SLOT,crystal);
            check(machine.getFluidHandlerCapability(Direction.UP).fill(new FluidStack(mio_icif_fluids.UUMATTER.get(),10000),IFluidHandler.FluidAction.EXECUTE)==10000,"Counted actual UU fill");
            machine.generateOnce();
        }
        groups.add("ordinary-scanner-memory-to-replicator-transfer");
    }
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception{
        if(tick==500)initialize(world);
        if(tick>=500&&tick<3900)for(int i=0;i<12;i++){
            var tile=scanner(world,i,false);long before=tile.getEnergyStorageInternal().getAmount();
            long received=tile.getEnergyStorageInternal().receive(512,false);funded[i]+=received;
            check(received>=0&&received<=512&&tile.getEnergyStorageInternal().getAmount()==before+received,"Counted scanner funding");
        }
        if(tick==3900)finishScans(world);
        if(tick>=3900&&tick<38000)for(int i=0;i<2;i++){
            var tile=replica(world,i);if(tile.getTotalProcessed()==0){
                long before=tile.getEnergyStorageInternal().getAmount();long received=tile.getEnergyStorageInternal().receive(512,false);
                replicaFunded[i]+=received;check(received>=0&&received<=512&&tile.getEnergyStorageInternal().getAmount()==before+received,"Counted replicator funding");
            }
        }
        if(tick==38000){
            for(int i=0;i<2;i++){
                var tile=replica(world,i);var output=tile.getItemHandler().getStackInSlot(mio_icif_replicator_elc.OUTPUT_SLOT);
                check(tile.getTotalProcessed()==1&&tile.getWorkMode()==mio_icif_replicator_elc.WorkMode.STOPPED&&!tile.hasHeldReplicationData(),"Exactly one naturally completed copy");
                check(output.getCount()==1&&ItemStack.isSameItemSameComponents(output,inputs[finiteIndices[i]])&&((IBatteryItem)output.getItem()).getEnergy(output)==0,"Replica has no free stored EU");
                long paid=replicaFunded[i]-tile.getEnergyStorageInternal().getAmount();long expected=(long)Math.ceil(costs[i]/0.0001)*512;
                check(paid==expected,"Exact 512 EU for each paid replication tick");
                near((10000-tile.getUuMatterAmount())/1000.0-tile.getUuCreditBuckets(),costs[i],"Full observed UU price paid");
                near(tile.getProcessedUuBuckets(),0,"No unpaid completed remainder");
                copies.add(Map.of("stack",output.save(world.registryAccess()).toString(),"price_buckets",costs[i],"paid_eu",paid,"funded_eu",replicaFunded[i],"empty_eu",0));
            }
            groups.add("two-full-price-full-duration-empty-replicas-without-free-energy");
            var result=Map.of("passed",true,"assertions",assertions,"groups",groups,"scans",scans,"copies",copies,
                    "scope","All normal scan/replication work ticks, counted fixture energy and ordinary inventory/fluid APIs. Server tick rate 1000 only speeds wall time; no performance, real-time, multiplayer or cold restart claim.");
            Files.writeString(Path.of("uu-charge-world-result.json"),new Gson().toJson(result));return result;
        }
        return null;
    }
}
