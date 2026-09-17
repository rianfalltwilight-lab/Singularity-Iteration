// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.GsonBuilder;
import com.singularity_iteration.mio_icif.api.item.ArmorFeatureInfo;
import com.singularity_iteration.mio_icif.api.item.IElectricArmorItem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** SI public calls only: never reflect on, inspect, or override the five sampled defaults. */
public final class ArmorApiDefaultsProbe {
    private static final Path OUTPUT=Path.of("armor-api-defaults-r128-result.json");
    private static final String INTERFACE_RESOURCE="com/singularity_iteration/mio_icif/api/item/IElectricArmorItem.class";
    private static final String BASELINE_CLASS_SHA="ba4a470c6025c2e69a75da05afc35cb70c0c29e9a0ddaef3669e2ba70d64bd3c";
    private static final String ENERGY="scex_r128_fixture_energy";
    private static final long CAPACITY=16;
    private boolean finished;

    private static void require(boolean value,String reason) {
        if(!value)throw new IllegalStateException("R128 armor defaults observation: "+reason);
    }
    private static long read(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getLong(ENERGY);
    }
    private static void write(ItemStack stack,long value) {
        var data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        data.putLong(ENERGY,value);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
    }

    /** Only the two required armor getters and the six abstract IBatteryItem methods are implemented. */
    private static final class ControlledArmor implements IElectricArmorItem {
        private final int tier;
        private final long perTick;
        private final List<Map<String,Object>> calls=new ArrayList<>();
        private ControlledArmor(int tier,long perTick){this.tier=tier;this.perTick=perTick;}
        private long answer(String method,long answer) {
            calls.add(Map.of("method",method,"returned",answer));return answer;
        }
        private long transfer(ItemStack stack,long requested,boolean charging) {
            long before=read(stack);
            long accepted=requested<=0?0:Math.min(requested,charging?CAPACITY-before:before);
            if(accepted>0)write(stack,before+(charging?accepted:-accepted));
            calls.add(Map.of("method",charging?"addEnergy":"extractEnergy","requested",requested,
                    "before",before,"returned",accepted,"after",read(stack)));
            return accepted;
        }
        @Override public long getEnergyPerTick(){return answer("getEnergyPerTick",perTick);}
        @Override public int getArmorTier(){return (int)answer("getArmorTier",tier);}
        @Override public long getMaxEnergy(){return answer("getMaxEnergy",CAPACITY);}
        @Override public long getEnergy(ItemStack stack){return answer("getEnergy",read(stack));}
        @Override public long addEnergy(ItemStack stack,long amount){return transfer(stack,amount,true);}
        @Override public long extractEnergy(ItemStack stack,long amount){return transfer(stack,amount,false);}
        @Override public boolean isFull(ItemStack stack){boolean value=read(stack)>=CAPACITY;calls.add(Map.of("method","isFull","returned",value));return value;}
        @Override public boolean isEmpty(ItemStack stack){boolean value=read(stack)<=0;calls.add(Map.of("method","isEmpty","returned",value));return value;}
    }

    @FunctionalInterface private interface Operation { Object run(ControlledArmor armor,ItemStack stack)throws Exception; }

    private static ItemStack fixture(ServerLevel world,long energy,int count) {
        var registry=world.registryAccess().lookupOrThrow(Registries.ITEM);
        var holder=registry.getOrThrow(ResourceKey.create(Registries.ITEM,ResourceLocation.parse("minecraft:paper")));
        var stack=new ItemStack(holder,count);
        var data=new CompoundTag();data.putString("scex_r128_keep","unrelated marker");data.putLong(ENERGY,energy);
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));return stack;
    }
    private static boolean sameComponents(DataComponentMap a,DataComponentMap b) {
        if(!a.keySet().equals(b.keySet()))return false;
        for(var type:a.keySet())if(!Objects.equals(a.get(type),b.get(type)))return false;
        return true;
    }
    private static Map<String,Object> snapshot(ServerLevel world,ItemStack stack) {
        var ops=world.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var full=ItemStack.STRICT_CODEC.encodeStart(ops,stack).getOrThrow();
        var copy=ItemStack.STRICT_CODEC.parse(ops,full).getOrThrow();
        require(ItemStack.matches(stack,copy),"strict fixture stack roundtrip");
        var components=DataComponentMap.CODEC.encodeStart(ops,stack.getComponents()).getOrThrow();
        require(sameComponents(stack.getComponents(),DataComponentMap.CODEC.parse(ops,components).getOrThrow()),"full fixture components roundtrip");
        var result=new LinkedHashMap<String,Object>();
        result.put("energy",read(stack));result.put("count",stack.getCount());
        result.put("stack_snbt",full.toString());result.put("all_components_snbt",components.toString());
        result.put("patch_snbt",DataComponentPatch.CODEC.encodeStart(ops,stack.getComponentsPatch()).getOrThrow().toString());
        result.put("strict_roundtrip",true);return result;
    }
    private static Object features(List<ArmorFeatureInfo> features) {
        if(features==null)return null;
        var rows=new ArrayList<Map<String,Object>>();
        require(features.size()<=64,"bounded feature observation");
        for(var feature:features){
            var row=new LinkedHashMap<String,Object>();
            if(feature==null){row.put("null_entry",true);rows.add(row);continue;}
            row.put("slot",feature.slot()==null?null:feature.slot().name());
            row.put("feature_key",feature.featureKey());row.put("feature_name_key",feature.featureNameKey());
            row.put("is_mode",feature.isMode());
            row.put("current_mode_text",feature.currentModeName()==null?null:feature.currentModeName().getString());
            rows.add(row);
        }
        return rows;
    }
    private static void observe(ServerLevel world,List<Map<String,Object>> rows,String method,int tier,long fee,
            long energy,int count,String argument,Object argumentValue,Operation call) {
        var row=new LinkedHashMap<String,Object>();rows.add(row);
        row.put("index",rows.size());row.put("method",method);row.put("fixture_tier",tier);
        row.put("fixture_energy_per_tick",fee);row.put("fixture_capacity",CAPACITY);
        row.put("fixture_energy",energy);row.put("fixture_count",count);
        if(argument!=null)row.put(argument,argumentValue);
        var armor=new ControlledArmor(tier,fee);var stack=fixture(world,energy,count);var before=stack.copy();
        row.put("before",snapshot(world,stack));
        try {
            Object value=call.run(armor,stack);
            if(value instanceof Float number&&!Float.isFinite(number))value=number.toString();
            row.put("returned",value);row.put("call_status","RETURNED");
        }catch(Exception failure){
            row.put("call_status","THREW");row.put("exception_type",failure.getClass().getName());
            row.put("exception_message",String.valueOf(failure.getMessage()));
        }
        row.put("provider_callbacks",List.copyOf(armor.calls));
        row.put("after",snapshot(world,stack));row.put("components_and_count_unchanged",ItemStack.matches(before,stack));
        row.put("actual_debit",energy-read(stack));
    }

    /** Baseline SI world only; run alongside the separate 32-target component observer. */
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception {
        if(finished||tick<20)return null;finished=true;
        var result=new LinkedHashMap<String,Object>();var rows=new ArrayList<Map<String,Object>>();
        result.put("tick",tick);result.put("rows",rows);result.put("candidate_activated",false);
        result.put("world_or_inventory_mutations",0);result.put("reflection_used",false);
        result.put("behavior_equivalence_approved",false);result.put("full_mod_gate","UNCHANGED_INCOMPLETE");
        try {
            require(world.getServer().isSameThread(),"normal server thread");
            byte[] bytes;
            try(var stream=ArmorApiDefaultsProbe.class.getClassLoader().getResourceAsStream(INTERFACE_RESOURCE)) {
                require(stream!=null,"baseline interface class resource exists");
                bytes=stream.readNBytes(1024*1024+1);require(bytes.length<=1024*1024,"class resource bound");
            }
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            result.put("interface_class_resource_sha256",actual);
            require(actual.equals(BASELINE_CLASS_SHA),"sample the frozen SI baseline interface, not the replacement draft");
            for(int tier:new int[]{0,5,6})for(long energy:new long[]{0,1,7}) {
                for(long fee:new long[]{0,1,3})observe(world,rows,"hasEnoughEnergy",tier,fee,energy,1,null,null,
                        (armor,stack)->armor.hasEnoughEnergy(stack));
                long[] amounts={-1,0,1,3,energy,energy+1};
                String[] labels={"negative","zero","one","ordinary","exact_balance","over_balance"};
                for(int i=0;i<amounts.length;i++){
                    final long amount=amounts[i];
                    observe(world,rows,"consumeEnergy",tier,1,energy,1,"amount",amount,
                            (armor,stack)->armor.consumeEnergy(stack,amount));
                    rows.getLast().put("boundary",labels[i]);
                }
                observe(world,rows,"getFeatures",tier,1,energy,1,null,null,(armor,stack)->features(armor.getFeatures(stack)));
                observe(world,rows,"getEnergyPerDamage",tier,1,energy,1,null,null,(armor,stack)->armor.getEnergyPerDamage());
                for(var slot:EquipmentSlot.values())observe(world,rows,"getDamageAbsorptionRatio",tier,1,energy,1,"slot",slot.name(),
                        (armor,stack)->armor.getDamageAbsorptionRatio(slot));
            }
            for(int tier:new int[]{0,5,6})observe(world,rows,"consumeEnergy",tier,1,7,2,"amount",1L,
                    (armor,stack)->armor.consumeEnergy(stack,1));
            int expected=9*(3+6+1+1+EquipmentSlot.values().length)+3;
            require(rows.size()==expected,"all bounded default-method cases observed");
            result.put("expected_cases",expected);result.put("equipment_slots",java.util.Arrays.stream(EquipmentSlot.values()).map(Enum::name).toList());
            result.put("passed",true);result.put("status","PASS_SCOPED_PUBLIC_DEFAULT_OBSERVATION");
        }catch(Exception failure){
            result.put("passed",false);result.put("status","FAIL_PUBLIC_DEFAULT_OBSERVATION_HARNESS");
            result.put("failure_type",failure.getClass().getName());result.put("failure_message",String.valueOf(failure.getMessage()));
        }
        result.put("cases",rows.size());
        result.put("caught_call_exceptions",rows.stream().filter(r->"THREW".equals(r.get("call_status"))).count());
        result.put("mutating_queries",rows.stream().filter(r->!"consumeEnergy".equals(r.get("method"))
                &&Boolean.FALSE.equals(r.get("components_and_count_unchanged"))).count());
        result.put("scope","Direct calls to five inherited SI defaults with a controlled implementation object and actual registered paper carrier; no item behavior, armor balance or IC2 API claim. Exceptions and mutation are observations, not automatic semantic passes.");
        Files.writeString(OUTPUT,new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(result)+"\n",StandardCharsets.UTF_8);
        require(Boolean.TRUE.equals(result.get("passed")),"observation harness failed; retained result "+OUTPUT);
        return result;
    }
}
