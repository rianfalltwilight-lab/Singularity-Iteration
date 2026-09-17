// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.api.item.IBatteryItem;
import com.singularity_iteration.mio_icif.Items.Reactor.mio_icif_component_heat_vent;
import dev.scex.si.processing.UuQuoteBook;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** SI public item calls only. Excluded implementation bodies are neither read nor rewritten. */
public final class HeldItemStateProbe {
    private int assertions;
    private void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError("R109 public states: "+message);}
    private String saved(ServerLevel world,ItemStack stack){
        var tag=stack.save(world.registryAccess());var roundtrip=ItemStack.parse(world.registryAccess(),tag).orElseThrow();
        check(roundtrip.getCount()==1&&ItemStack.isSameItemSameComponents(stack,roundtrip),"Exact saved item roundtrip");return tag.toString();
    }
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception{
        if(tick!=20)return null;
        var fixture=JsonParser.parseString(Files.readString(Path.of("held-item-states.json"))).getAsJsonObject();
        var cases=fixture.getAsJsonArray("cases");check(cases.size()==11,"Exact eleven held identities");
        var rows=new ArrayList<Map<String,Object>>();var ops=world.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for(var value:cases){
            var entry=value.getAsJsonObject();String target=entry.get("target_item").getAsString();var id=ResourceLocation.parse(target);
            check(BuiltInRegistries.ITEM.containsKey(id),"Actual registered target");var item=BuiltInRegistries.ITEM.get(id);var stack=new ItemStack(item);
            check(item.getClass().getName().equals(entry.getAsJsonObject("r101_observation").get("runtime_class").getAsString()),"Exact observed runtime class");
            var all=DataComponentMap.CODEC.encodeStart(ops,stack.getComponents()).getOrThrow();
            check(all.equals(TagParser.parseTag(entry.getAsJsonObject("r101_observation").get("all_components_snbt").getAsString())),"R101 registered factory still matches");
            var row=new LinkedHashMap<String,Object>();row.put("case_id",entry.get("case_id").getAsString());row.put("target_item",target);
            row.put("default_stack",saved(world,stack));row.put("default_components",all.toString());row.put("runtime_class",item.getClass().getName());
            row.put("default_instance",saved(world,item.getDefaultInstance()));row.put("default_classification",UuQuoteBook.classify(world.getServer(),stack));
            if(item instanceof IBatteryItem battery){
                long amount=battery.getEnergy(stack),capacity=battery.getMaxEnergy(stack);
                check(capacity>0&&amount>=0&&amount<=capacity,"Public bounded energy");
                var empty=stack.copy();battery.setEnergy(empty,0);check(battery.getEnergy(empty)==0,"Public empty state");
                var charged=empty.copy();battery.setEnergy(charged,1);check(battery.getEnergy(charged)==1,"Public one EU state");
                check(!ItemStack.isSameItemSameComponents(empty,charged)&&battery.getEnergy(stack)==amount,"Independent exact component identities and unchanged factory");
                row.put("default_eu",amount);row.put("max_eu",capacity);row.put("empty_stack",saved(world,empty));row.put("one_eu_stack",saved(world,charged));
                row.put("empty_components",DataComponentMap.CODEC.encodeStart(ops,empty.getComponents()).getOrThrow().toString());
                row.put("empty_classification",UuQuoteBook.classify(world.getServer(),empty));
            }else if(item instanceof mio_icif_component_heat_vent vent){
                int heat=vent.getStoredHeat(stack);check(heat==0&&!vent.isMelted(stack),"Public pristine component heat state");
                var copy=stack.copy();vent.setStoredHeat(copy,0);check(ItemStack.isSameItemSameComponents(copy,stack),"Zero heat setter keeps exact factory identity");
                row.put("stored_heat",heat);row.put("melted",false);row.put("empty_stack",saved(world,stack));
            }else throw new AssertionError("Unobserved public state interface "+target);
            rows.add(row);
        }
        var result=Map.of("passed",true,"assertions",assertions,"groups",List.of("eleven-registered-pristine-public-states-and-exact-serialization"),"rows",rows,
                "scope","Observation of SI-owned public energy/heat calls only. No source-body inspection, mapping admission, full equipment behavior or provenance clearance.");
        Files.writeString(Path.of("held-item-states-result.json"),new Gson().toJson(result));return result;
    }
}
