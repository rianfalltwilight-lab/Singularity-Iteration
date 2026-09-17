// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_scanner_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_scanner_elc;
import com.singularity_iteration.mio_icif.Items.Resource.mio_icif_memory;
import com.singularity_iteration.mio_icif.Items.Reactor.mio_icif_component_heat_vent;
import com.singularity_iteration.mio_icif.Items.Tools.mio_icif_laser_miner;
import com.singularity_iteration.mio_icif.api.item.IBatteryItem;
import dev.scex.si.processing.UuMappedCatalog;
import dev.scex.si.processing.UuQuoteBook;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** New exact identities through ordinary paid-denial tickers, with unobserved-state controls. */
public final class HeldScanWorldProbe {
    private final JsonObject fixture;
    private final ItemStack[] inputs=new ItemStack[10],variants=new ItemStack[10];
    private final boolean[] eligible=new boolean[10];
    private final long[] funded=new long[10],terminalEnergy=new long[10];
    private final List<Map<String,Object>> results=new ArrayList<>();
    private final List<String> groups=new ArrayList<>();
    private mio_icif_memory memory;
    private int assertions;
    public HeldScanWorldProbe()throws Exception{
        fixture=JsonParser.parseString(Files.readString(Path.of("held-scan-world.json"))).getAsJsonObject();
        check(fixture.get("schema").getAsInt()==1&&fixture.getAsJsonArray("rows").size()==10,"Ten explicit identities");
    }
    private void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError("R109 scan: "+message);}
    private BlockPos pos(int i,boolean variant){return new BlockPos(2400+i*8+(variant?2:0),80,4);}
    private mio_icif_scanner_elc tile(ServerLevel world,int i,boolean variant){
        var tile=world.getBlockEntity(pos(i,variant));check(tile instanceof mio_icif_scanner_elc,"Actual scanner");return (mio_icif_scanner_elc)tile;
    }
    private void unsupported(ServerLevel world,ItemStack stack){var a=UuQuoteBook.classify(world.getServer(),stack);
        check(a.disposition()==UuQuoteBook.Disposition.UNSUPPORTED&&a.finite()==null&&!a.deniedScanEligible(),"Unobserved components not normalized or inferred: "+a);}
    private void initialize(ServerLevel world)throws Exception{
        var blocks=BuiltInRegistries.BLOCK.stream().filter(mio_icif_block_scanner_elc.class::isInstance).toList();
        var memories=BuiltInRegistries.ITEM.stream().filter(mio_icif_memory.class::isInstance).toList();
        check(blocks.size()==1&&memories.size()==1,"Unique actual registrations");memory=(mio_icif_memory)memories.getFirst();
        var rows=fixture.getAsJsonArray("rows");var ops=world.registryAccess().createSerializationContext(NbtOps.INSTANCE);int count=0;
        for(int i=0;i<10;i++){
            var row=rows.get(i).getAsJsonObject();inputs[i]=UuMappedCatalog.explicitStack(row.get("target_stack").getAsString(),row.get("target_item").getAsString(),world.registryAccess());
            var input=inputs[i];var item=input.getItem();var actual=DataComponentMap.CODEC.encodeStart(ops,input.getComponents()).getOrThrow();
            check(actual.equals(TagParser.parseTag(row.get("expected_components").getAsString())),"All components match public state observation");
            var saved=input.save(world.registryAccess());var copy=ItemStack.parse(world.registryAccess(),saved).orElseThrow();
            check(copy.getCount()==1&&ItemStack.isSameItemSameComponents(copy,input),"Exact pristine stack roundtrip");
            var named=input.copy();named.set(DataComponents.CUSTOM_NAME,Component.literal("Unobserved R109"));unsupported(world,named);
            if(row.get("kind").getAsString().equals("EMPTY_ENERGY")){
                check(item instanceof IBatteryItem,"Owned public energy interface");var battery=(IBatteryItem)item;
                check(battery.getEnergy(input)==0&&battery.getMaxEnergy(input)==row.get("max_eu").getAsLong(),"Actual empty energy");
                var charged=input.copy();battery.setEnergy(charged,1);check(battery.getEnergy(charged)==1,"One EU control");unsupported(world,charged);variants[i]=charged;
                var full=input.copy();battery.setEnergy(full,battery.getMaxEnergy(input));unsupported(world,full);
                if(item instanceof mio_icif_laser_miner laser)check(laser.getMode(input)==mio_icif_laser_miner.MODE_MINING,"Public SI default mining mode matches reference tooltip");
                eligible[i]=true;count++;
            }else{
                check(item instanceof mio_icif_component_heat_vent,"Actual component heat vent");var vent=(mio_icif_component_heat_vent)item;
                check(vent.getStoredHeat(input)==0&&!vent.isMelted(input),"Public zero heat factory");variants[i]=named;
            }
            var a=UuQuoteBook.classify(world.getServer(),input);
            check(a.disposition()==UuQuoteBook.Disposition.KNOWN_DENIED&&a.finite()==null&&a.deniedScanEligible()==eligible[i],"Separate explicit denial and paid-scan authority");
            for(boolean control:new boolean[]{false,true}){
                check(world.setBlockAndUpdate(pos(i,control),blocks.getFirst().defaultBlockState()),"Place actual scanner");
                var machine=tile(world,i,control);machine.setItem(0,control?variants[i]:input);machine.setItem(2,new ItemStack(memory));
                check(ItemStack.isSameItemSameComponents(machine.getItem(0),control?variants[i]:input),"Exact accepted input");
                if(control)machine.getEnergyStorageInternal().setEnergy(512);
            }
        }
        check(count==9,"Nine observed paid scans; R93-only heat vent remains ineligible");
        groups.add("ten-exact-identities-with-nine-empty-energy-and-pristine-zero-heat");
    }
    private void unchanged(ServerLevel world,int i){
        var machine=tile(world,i,false);var control=tile(world,i,true);
        check(machine.getItem(0).getCount()==1&&ItemStack.isSameItemSameComponents(machine.getItem(0),inputs[i]),"Exact denied input retained");
        check(control.getItem(0).getCount()==1&&ItemStack.isSameItemSameComponents(control.getItem(0),variants[i])
                &&control.getProgress()==0&&control.getEnergyStorageInternal().getAmount()==512,"Variant has no progress or payment");
    }
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception{
        if(tick==500)initialize(world);
        if(tick>=500&&tick<3900)for(int i=0;i<10;i++){
            var machine=tile(world,i,false);long before=machine.getEnergyStorageInternal().getAmount();long received=machine.getEnergyStorageInternal().receive(512,false);
            check(received>=0&&received<=512&&machine.getEnergyStorageInternal().getAmount()==before+received,"Counted explicit EU receipt");funded[i]+=received;
        }
        if(tick==3900){
            for(int i=0;i<10;i++){
                var machine=tile(world,i,false);unchanged(world,i);terminalEnergy[i]=machine.getEnergyStorageInternal().getAmount();
                long consumed=funded[i]-terminalEnergy[i];
                check(machine.getProgress()==(eligible[i]?3300:0)&&consumed==(eligible[i]?844800:0),"Exact paid duration or ineligible zero debit");
                check(machine.isDeniedScanComplete()==eligible[i]&&!machine.isScanComplete()&&!machine.hasHeldScanData(),"Correct terminal denial state");
                check(!machine.storeResult()&&!memory.hasData(machine.getItem(2)),"No quote or fabricated pattern from Infinity");
                results.add(Map.of("target_item",BuiltInRegistries.ITEM.getKey(inputs[i].getItem()).toString(),"eligible",eligible[i],
                        "paid_eu",consumed,"progress",machine.getProgress(),"input",inputs[i].save(world.registryAccess()).toString()));
            }
            groups.add("nine-full-3300-tick-paid-denials-and-ineligible-heat-vent-zero-payment");
        }
        if(tick==4000){
            for(int i=0;i<10;i++){var machine=tile(world,i,false);unchanged(world,i);
                check(machine.getEnergyStorageInternal().getAmount()==terminalEnergy[i]&&machine.getProgress()==(eligible[i]?3300:0),"No repeat charge in terminal plateau");}
            groups.add("ten-changed-component-controls-and-100-tick-terminal-plateau");
            var result=Map.of("passed",true,"assertions",assertions,"groups",groups,"rows",results,
                    "scope","Exact observed identity and paid failure only; no quarantined source clearance, complete tool/armor behavior, cold restart, client, multiplayer or performance acceptance.");
            Files.writeString(Path.of("held-scan-world-result.json"),new Gson().toJson(result));return result;
        }
        return null;
    }
}
