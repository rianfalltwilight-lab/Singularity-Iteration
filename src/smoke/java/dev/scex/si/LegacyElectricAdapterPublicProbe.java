// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.singularity_iteration.mio_icif.api.item.BatteryElectricAdapter;
import com.singularity_iteration.mio_icif.api.item.IBatteryItem;
import com.singularity_iteration.mio_icif.api.item.electric.IElectricItem;
import com.singularity_iteration.mio_icif.api.item.electric.IElectricItemManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Old SI public calls only. No reflection, registration, world placement or player inventory access. */
public final class LegacyElectricAdapterPublicProbe {
    private static final Path MARKER=Path.of("legacy-electric-adapter-r133.json");
    private static final Path CASES=Path.of("legacy-electric-adapter-cases-r133.json");
    private static final Path ROWS=Path.of("legacy-electric-adapter-r133-observations.jsonl");
    private static final Path OUTPUT=Path.of("legacy-electric-adapter-r133-result.json");
    private static final String CASES_SHA="424b2b188b95ebce4946b4eb7aac351cc1866348c034059b04c280db74f581f8";
    private static final String CLASS_JSON="{\"com/singularity_iteration/mio_icif/api/item/BatteryElectricAdapter.class\":\"f756ecb97e77e10dddf897b756dcfbc21885f655c658effcc54c1c20555cf20f\",\"com/singularity_iteration/mio_icif/api/item/electric/ISpecialElectricItem.class\":\"c51d114718c429c007206191f9eebc18d3f566f4f7df8cca9bbd46f33d746f20\",\"com/singularity_iteration/mio_icif/api/item/electric/IElectricItemManager.class\":\"08093abe3e7f023e0c38ce25fcca0d3476118537fa09ef34ea7b525db1f5e080\",\"com/singularity_iteration/mio_icif/api/item/electric/IElectricItem.class\":\"71b45a365fbcb585350944516acdca11130235f66cd6040b243ed5a37e05febe\",\"com/singularity_iteration/mio_icif/api/item/electric/IBackupElectricItemManager.class\":\"023aa95d53c209099978e6bd1dd3fb52852c1ac237f81e604472a52ff9de68be\",\"com/singularity_iteration/mio_icif/api/item/BatteryElectricAdapter$ElectricItemWrapper.class\":\"eca1dab490e4129647f03bb81e7911393e73e3c26157f92592f6a4210c51094c\",\"com/singularity_iteration/mio_icif/api/item/BatteryElectricAdapter$BatteryItemWrapper.class\":\"0fd48912f407b90a565d844b24986335d922c6793fe4199604d36172b013934e\"}";
    private static final int EXPECTED_ROWS=2885;
    private static final Gson GSON=new GsonBuilder().serializeNulls().create();
    private static final String ENERGY="scex_r133_fixture_energy", CAPACITY="scex_r133_fixture_capacity", RATE="scex_r133_fixture_rate";
    private JsonArray cases;
    private final Map<String,Integer> groups=new LinkedHashMap<>();
    private final List<Map<String,Object>> classIdentities=new ArrayList<>();
    private final List<Map<String,Object>> contextIdentities=new ArrayList<>();
    private int next,thrown,lastTick=-1,startedTick=-1;
    private boolean initialized,finished;

    private static void require(boolean condition,String message) {
        if(!condition)throw new IllegalStateException("R133 adapter harness: "+message);
    }
    private static String str(JsonObject object,String key,String fallback) {
        return object.has(key)?object.get(key).getAsString():fallback;
    }
    private static long number(JsonObject object,String key,long fallback) {
        return object.has(key)?object.get(key).getAsLong():fallback;
    }
    private static boolean bool(JsonObject object,String key,boolean fallback) {
        return object.has(key)?object.get(key).getAsBoolean():fallback;
    }
    private static String hash(byte[] bytes)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static CompoundTag data(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
    }
    private static long read(ItemStack stack,String key){return data(stack).getLong(key);}
    private static void write(ItemStack stack,String key,long value) {
        var tag=data(stack);tag.putLong(key,value);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
    }
    private static ItemStack item(ServerLevel world,String id,int count) {
        var holder=world.registryAccess().lookupOrThrow(Registries.ITEM)
            .getOrThrow(ResourceKey.create(Registries.ITEM,ResourceLocation.parse(id)));
        return new ItemStack(holder,count);
    }
    private static ItemStack carrier(ServerLevel world,JsonObject row) {
        var stack=item(world,"minecraft:paper",(int)number(row,"stack_count",1));
        if(stack.isEmpty())return stack;
        var tag=new CompoundTag();tag.putLong(ENERGY,number(row,"initial_energy",333));
        tag.putLong(CAPACITY,number(row,"capacity",1000));tag.putLong(RATE,number(row,"charge_rate",64));
        tag.putString("scex_r133_keep","unrelated sentinel");
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));return stack;
    }
    private static boolean sameComponents(DataComponentMap a,DataComponentMap b) {
        if(!a.keySet().equals(b.keySet()))return false;
        for(var type:a.keySet())if(!Objects.equals(a.get(type),b.get(type)))return false;
        return true;
    }
    private static Map<String,Object> snapshot(ServerLevel world,ItemStack stack) {
        var ops=world.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var result=new LinkedHashMap<String,Object>();
        result.put("count",stack.getCount());result.put("is_empty",stack.isEmpty());
        result.put("fixture_energy",read(stack,ENERGY));result.put("fixture_capacity",read(stack,CAPACITY));
        result.put("fixture_rate",read(stack,RATE));
        if(!stack.isEmpty()){
            var full=ItemStack.STRICT_CODEC.encodeStart(ops,stack).getOrThrow();
            var copy=ItemStack.STRICT_CODEC.parse(ops,full).getOrThrow();
            require(ItemStack.matches(stack,copy),"full nonempty stack codec roundtrip");
            result.put("stack_snbt",full.toString());result.put("strict_stack_roundtrip",true);
        }else{
            // STRICT_CODEC intentionally rejects empty stacks. Preserve components
            // separately; do not turn a legal empty-boundary sample into a failure.
            result.put("stack_snbt",null);result.put("strict_stack_roundtrip","NOT_APPLICABLE_EMPTY");
        }
        var encoded=DataComponentMap.CODEC.encodeStart(ops,stack.getComponents()).getOrThrow();
        require(sameComponents(stack.getComponents(),DataComponentMap.CODEC.parse(ops,encoded).getOrThrow()),"all components roundtrip");
        result.put("all_components_snbt",encoded.toString());
        result.put("patch_snbt",DataComponentPatch.CODEC.encodeStart(ops,stack.getComponentsPatch()).getOrThrow().toString());
        return result;
    }
    private static final class ProviderFault extends RuntimeException {
        private static final long serialVersionUID=1L;
        private ProviderFault(String message){super(message);}
    }

    /** Caller-owned input. Legacy manager flags have no semantics here: record them verbatim. */
    private static final class State {
        private final JsonObject spec;
        private final ItemStack original;
        private final List<Map<String,Object>> calls=new ArrayList<>();
        private final String mode;
        private Runnable reentry;
        private boolean reentered;
        private State(JsonObject spec,ItemStack original) {
            this.spec=spec;this.original=original;this.mode=str(spec,"provider_mode","NORMAL");
        }
        private Map<String,Object> call(String name,ItemStack stack) {
            if(calls.size()>=256)throw new ProviderFault("controlled callback budget exceeded");
            var row=new LinkedHashMap<String,Object>();row.put("method",name);
            row.put("original_stack_identity",stack==original);row.put("before",stack==null?null:read(stack,ENERGY));
            row.put("count",stack==null?null:stack.getCount());calls.add(row);return row;
        }
        private long query(String name,ItemStack stack,long value) {
            var row=call(name,stack);
            if(mode.equals("THROW_QUERY")){row.put("thrown","ProviderFault/query");throw new ProviderFault("controlled query failure");}
            row.put("returned",value);return value;
        }
        private boolean query(String name,ItemStack stack,boolean value) {
            query(name,stack,value?1:0);return value;
        }
        private long transfer(String name,ItemStack stack,long amount,boolean charging,Integer tier,boolean... flags) {
            var row=call(name,stack);row.put("requested",amount);row.put("tier",tier);
            row.put("boolean_arguments",flags);row.put("fixture_mode",mode);
            if(mode.equals("THROW_BEFORE")){row.put("thrown","ProviderFault/before");throw new ProviderFault("controlled pre-mutation failure");}
            long before=read(stack,ENERGY),capacity=read(stack,CAPACITY);
            long room=charging?Math.max(0,capacity-before):Math.max(0,before);
            long changed=amount<=0?0:Math.min(amount,room);
            if(mode.equals("REFUSE"))changed=0;
            if(mode.equals("PARTIAL"))changed=Math.min(3,changed);
            if(mode.equals("REENTER_ONCE")&&!reentered&&reentry!=null){
                reentered=true;row.put("reentered_once",true);reentry.run();
                before=read(stack,ENERGY);room=charging?Math.max(0,read(stack,CAPACITY)-before):Math.max(0,before);
                changed=amount<=0?0:Math.min(amount,room);
            }
            if(changed>0)write(stack,ENERGY,before+(charging?changed:-changed));
            if(mode.equals("WRONG_DIRECTION"))write(stack,ENERGY,charging?Math.max(0,before-1):Math.min(capacity,before+1));
            if(mode.equals("CAPACITY_DRIFT"))write(stack,CAPACITY,Math.max(0,capacity-1));
            if(mode.equals("CHANGE_COUNT"))stack.setCount(2);
            if(mode.equals("CHANGE_ORIGINAL"))write(original,ENERGY,Math.min(read(original,CAPACITY),read(original,ENERGY)+7));
            long reported=mode.equals("LIE")?changed+1:changed;
            row.put("reported",reported);row.put("after",read(stack,ENERGY));row.put("original_after",read(original,ENERGY));
            if(mode.equals("THROW_AFTER")){row.put("thrown","ProviderFault/after");throw new ProviderFault("controlled post-mutation failure");}
            return reported;
        }
    }

    private static final class ControlledBattery implements IBatteryItem {
        private final State state;
        private ControlledBattery(State state){this.state=state;}
        @Override public long getMaxEnergy(){return state.query("battery.getMaxEnergy()",null,number(state.spec,"capacity",1000));}
        @Override public long getMaxEnergy(ItemStack stack){return state.query("battery.getMaxEnergy(stack)",stack,read(stack,CAPACITY));}
        @Override public long getEnergy(ItemStack stack){return state.query("battery.getEnergy",stack,read(stack,ENERGY));}
        @Override public long addEnergy(ItemStack stack,long amount){return state.transfer("battery.addEnergy",stack,amount,true,null);}
        @Override public long extractEnergy(ItemStack stack,long amount){return state.transfer("battery.extractEnergy",stack,amount,false,null);}
        @Override public boolean isFull(ItemStack stack){return state.query("battery.isFull",stack,read(stack,ENERGY)>=read(stack,CAPACITY));}
        @Override public boolean isEmpty(ItemStack stack){return state.query("battery.isEmpty",stack,read(stack,ENERGY)<=0);}
        @Override public long getChargeRate(ItemStack stack){return state.query("battery.getChargeRate",stack,read(stack,RATE));}
        @Override public void setEnergy(ItemStack stack,long value){var row=state.call("battery.setEnergy",stack);row.put("requested",value);write(stack,ENERGY,value);}
    }
    private static final class ControlledElectric implements IElectricItem,IElectricItemManager {
        private final State state;
        private ControlledElectric(State state){this.state=state;}
        @Override public boolean canProvideEnergy(ItemStack stack){return state.query("electric.canProvideEnergy",stack,bool(state.spec,"can_provide",true));}
        @Override public long getMaxCharge(ItemStack stack){return state.query("electric.getMaxCharge",stack,read(stack,CAPACITY));}
        @Override public int getTier(ItemStack stack){return (int)state.query("electric.getTier",stack,number(state.spec,"tier",1));}
        @Override public long getTransferLimit(ItemStack stack){return state.query("electric.getTransferLimit",stack,read(stack,RATE));}
        @Override public long charge(ItemStack stack,long amount,int tier,boolean b0,boolean b1){return state.transfer("manager.charge",stack,amount,true,tier,b0,b1);}
        @Override public long discharge(ItemStack stack,long amount,int tier,boolean b0,boolean b1,boolean b2){return state.transfer("manager.discharge",stack,amount,false,tier,b0,b1,b2);}
        @Override public long getCharge(ItemStack stack){return state.query("manager.getCharge",stack,read(stack,ENERGY));}
        @Override public boolean canUse(ItemStack stack,long amount){return state.query("manager.canUse",stack,amount>=0&&read(stack,ENERGY)>=amount);}
        @Override public boolean use(ItemStack stack,long amount,LivingEntity entity){
            var row=state.call("manager.use",stack);row.put("requested",amount);row.put("entity_null",entity==null);
            if(amount<0||read(stack,ENERGY)<amount)return false;
            return state.transfer("manager.use.payment",stack,amount,false,null)==amount;
        }
        @Override public void chargeFromArmor(ItemStack stack,LivingEntity entity){var row=state.call("manager.chargeFromArmor",stack);row.put("entity_null",entity==null);row.put("controlled_rule","log only, no armor reads");}
        @Override public String getToolTip(ItemStack stack){state.call("manager.getToolTip",stack);return "controlled-fixture-tooltip";}
    }

    private static void equip(ServerLevel world,LivingEntity actor,EquipmentSlot slot,String id) {
        var stack=item(world,id,1);
        if(stack.getItem() instanceof IBatteryItem battery){
            long before=battery.getEnergy(stack);
            if(before>0)battery.extractEnergy(stack,before);
            require(battery.getEnergy(stack)==0,"donor clear via public API "+id);
            long wanted=Math.min(777,battery.getMaxEnergy(stack));
            require(wanted>0&&battery.addEnergy(stack,wanted)==wanted&&battery.getEnergy(stack)==wanted,"donor public initialization "+id);
        }
        actor.setItemSlot(slot,stack);
    }
    private static LivingEntity actor(ServerLevel world,JsonObject spec) {
        if(str(spec,"entity","null").equals("null"))return null;
        // Neither object is added to the world, PlayerList or a tick list. The
        // FakePlayer's dummy connection sends no network traffic; damage/tick
        // semantics are irrelevant to this public item API-only observation.
        LivingEntity actor=str(spec,"entity","").equals("player")
            ?new FakePlayer(world,new GameProfile(UUID.nameUUIDFromBytes(str(spec,"id","").getBytes(StandardCharsets.UTF_8)),"R133AdapterProbe"))
            :new ArmorStand(world,0,0,0);
        String mode=str(spec,"equipment","none");
        if(mode.equals("vanilla"))equip(world,actor,EquipmentSlot.CHEST,"minecraft:iron_chestplate");
        if(mode.equals("nano-head")||mode.equals("nano-all")||mode.equals("nano-and-batpack"))equip(world,actor,EquipmentSlot.HEAD,"mio_icif:armor/item_armor_nano_helmet");
        if(mode.equals("nano-chest")||mode.equals("nano-all"))equip(world,actor,EquipmentSlot.CHEST,"mio_icif:armor/item_armor_nano_chestplate");
        if(mode.equals("nano-legs")||mode.equals("nano-all"))equip(world,actor,EquipmentSlot.LEGS,"mio_icif:armor/item_armor_nano_leggings");
        if(mode.equals("nano-feet")||mode.equals("nano-all"))equip(world,actor,EquipmentSlot.FEET,"mio_icif:armor/item_armor_nano_boots");
        if(mode.equals("batpack")||mode.equals("nano-and-batpack"))equip(world,actor,EquipmentSlot.CHEST,"mio_icif:armor/item_armor_batpack");
        if(mode.equals("iron-tool-in-chest"))equip(world,actor,EquipmentSlot.CHEST,"mio_icif:item_tool_iron_driller");
        return actor;
    }
    private static List<Map<String,Object>> equipment(ServerLevel world,LivingEntity actor) {
        var rows=new ArrayList<Map<String,Object>>();if(actor==null)return rows;
        for(var slot:EquipmentSlot.values()){
            var stack=actor.getItemBySlot(slot);var row=new LinkedHashMap<String,Object>();
            row.put("slot",slot.name());row.put("stack",snapshot(world,stack));
            if(!stack.isEmpty())row.put("actual_item_class",stack.getItem().getClass().getName());
            if(!stack.isEmpty()&&stack.getItem() instanceof IBatteryItem battery){row.put("public_energy",battery.getEnergy(stack));row.put("public_capacity",battery.getMaxEnergy(stack));}
            rows.add(row);
        }
        return rows;
    }
    private static boolean flag(JsonObject spec,int index) {
        var flags=spec.getAsJsonArray("booleans");return flags!=null&&index<flags.size()&&flags.get(index).getAsBoolean();
    }
    private static Map<String,Object> type(Object value) {
        var row=new LinkedHashMap<String,Object>();row.put("is_null",value==null);
        if(value!=null){row.put("runtime_class",value.getClass().getName());row.put("IBatteryItem",value instanceof IBatteryItem);
            row.put("IElectricItem",value instanceof IElectricItem);row.put("IElectricItemManager",value instanceof IElectricItemManager);}
        return row;
    }
    private static Object forward(BatteryElectricAdapter.ElectricItemWrapper wrapper,ItemStack stack,LivingEntity actor,JsonObject spec) {
        long amount=number(spec,"requested",65);int tier=(int)number(spec,"tier",1);
        return switch(str(spec,"operation","")){
            case "construct" -> type(wrapper);
            case "canProvideEnergy" -> wrapper.canProvideEnergy(stack);
            case "getTier" -> wrapper.getTier(stack);
            case "getMaxCharge" -> wrapper.getMaxCharge(stack);
            case "getTransferLimit" -> wrapper.getTransferLimit(stack);
            case "getCharge" -> wrapper.getCharge(stack);
            case "getToolTip" -> wrapper.getToolTip(stack);
            case "charge" -> wrapper.charge(stack,amount,tier,flag(spec,0),flag(spec,1));
            case "discharge" -> wrapper.discharge(stack,amount,tier,flag(spec,0),flag(spec,1),flag(spec,2));
            case "canUse" -> wrapper.canUse(stack,amount);
            case "use" -> wrapper.use(stack,amount,actor);
            case "chargeFromArmor" -> {wrapper.chargeFromArmor(stack,actor);yield "void";}
            case "chargeFromArmorTwice" -> {wrapper.chargeFromArmor(stack,actor);long first=read(stack,ENERGY);wrapper.chargeFromArmor(stack,actor);yield Map.of("first_target_energy",first,"second_target_energy",read(stack,ENERGY));}
            default -> throw new IllegalArgumentException("Unknown forward operation "+spec);
        };
    }
    private static Object reverse(IBatteryItem wrapper,ItemStack stack,JsonObject spec,ServerLevel world) {
        long amount=number(spec,"requested",65);
        return switch(str(spec,"operation","")){
            case "construct" -> type(wrapper);
            case "getMaxEnergyNoStack" -> wrapper.getMaxEnergy();
            case "getMaxEnergy" -> wrapper.getMaxEnergy(stack);
            case "getEnergy" -> wrapper.getEnergy(stack);
            case "isFull" -> wrapper.isFull(stack);
            case "isEmpty" -> wrapper.isEmpty(stack);
            case "getChargeRate" -> wrapper.getChargeRate(stack);
            case "addEnergy" -> wrapper.addEnergy(stack,amount);
            case "extractEnergy" -> wrapper.extractEnergy(stack,amount);
            case "setEnergy" -> {wrapper.setEnergy(stack,123);yield "void";}
            case "alternateCapacity" -> {
                var other=carrier(world,spec);write(other,CAPACITY,2000);
                var values=new ArrayList<Object>();
                // Catch each no-stack call separately, so a refusal does not hide
                // the remaining dynamic-stack observations in this sequence.
                values.add(noStackCapacity(wrapper));values.add(wrapper.getMaxEnergy(stack));
                values.add(noStackCapacity(wrapper));values.add(wrapper.getMaxEnergy(other));
                values.add(noStackCapacity(wrapper));values.add(wrapper.getMaxEnergy(stack));yield values;
            }
            default -> throw new IllegalArgumentException("Unknown reverse operation "+spec);
        };
    }
    private static Object noStackCapacity(IBatteryItem wrapper) {
        try{return Map.of("status","RETURNED","value",wrapper.getMaxEnergy());}
        catch(RuntimeException failure){return Map.of("status","THREW","type",failure.getClass().getName(),"message",String.valueOf(failure.getMessage()));}
    }

    private Map<String,Object> observe(ServerLevel world,JsonObject spec,int tick) {
        var row=new LinkedHashMap<String,Object>();row.put("index",next);row.put("tick",tick);row.put("input",spec);
        var stack=carrier(world,spec);var before=stack.copy();long originalEnergy=read(stack,ENERGY);var state=new State(spec,stack);
        var actor=actor(world,spec);row.put("before",snapshot(world,stack));row.put("equipment_before",equipment(world,actor));
        row.put("entity_added_to_world",false);row.put("entity_type",actor==null?null:actor.getClass().getName());
        String construction=str(spec,"construction","factory"),nulls=str(spec,"nulls","none");
        row.put("phase","constructor");
        try{
            Object value;
            if(str(spec,"direction","").equals("IBatteryItem-to-ElectricItemWrapper")){
                IBatteryItem provider=nulls.equals("provider")?null:new ControlledBattery(state);
                var wrapper=construction.equals("nested")?new BatteryElectricAdapter.ElectricItemWrapper(provider):BatteryElectricAdapter.asElectricItem(provider);
                row.put("constructed",type(wrapper));row.put("phase","public_call");
                state.reentry=()->{long returned=wrapper.charge(stack,1,0,true,false);var nested=new LinkedHashMap<String,Object>();nested.put("method","fixture.reentry.result");nested.put("returned",returned);state.calls.add(nested);};
                if(construction.equals("factory_twice")){var second=BatteryElectricAdapter.asElectricItem(provider);value=Map.of("same_instance",wrapper==second,"first",type(wrapper),"second",type(second));}
                else value=forward(wrapper,stack,actor,spec);
            }else{
                var controlled=new ControlledElectric(state);
                IElectricItem provider=nulls.equals("provider")||nulls.equals("both")?null:controlled;
                IElectricItemManager manager=nulls.equals("manager")||nulls.equals("both")?null:controlled;
                IBatteryItem wrapper=construction.equals("nested")?new BatteryElectricAdapter.BatteryItemWrapper(provider,manager):BatteryElectricAdapter.asBatteryItem(provider,manager);
                row.put("constructed",type(wrapper));row.put("phase","public_call");
                state.reentry=()->{long returned=wrapper.addEnergy(stack,1);var nested=new LinkedHashMap<String,Object>();nested.put("method","fixture.reentry.result");nested.put("returned",returned);state.calls.add(nested);};
                if(construction.equals("factory_twice")){var second=BatteryElectricAdapter.asBatteryItem(provider,manager);value=Map.of("same_instance",wrapper==second,"first",type(wrapper),"second",type(second));}
                else value=reverse(wrapper,stack,spec,world);
            }
            row.put("call_status","RETURNED");row.put("returned",value);
        }catch(RuntimeException failure){
            thrown++;row.put("call_status","THREW");row.put("exception_type",failure.getClass().getName());
            row.put("exception_message",String.valueOf(failure.getMessage()));
        }finally{
            // Drop callback closures before the row leaves; no cross-row retained
            // provider/stack/entity references or repeat callbacks are possible.
            state.reentry=null;
        }
        row.put("provider_callbacks",List.copyOf(state.calls));row.put("after",snapshot(world,stack));
        row.put("equipment_after",equipment(world,actor));row.put("components_and_count_unchanged",ItemStack.matches(before,stack));
        row.put("fixture_energy_delta",read(stack,ENERGY)-originalEnergy);
        row.put("semantic_assertion","NONE_OBSERVATION_ONLY");
        groups.merge(str(spec,"group","unknown"),1,Integer::sum);return row;
    }

    private void initialize(int tick)throws Exception {
        require(!Files.exists(ROWS)&&!Files.exists(OUTPUT),"fresh evidence outputs required; refuse overwrite");
        var marker=JsonParser.parseString(Files.readString(MARKER,StandardCharsets.UTF_8)).getAsJsonObject();
        require(marker.get("enabled").getAsBoolean()&&marker.get("schema_version").getAsInt()==1,"enabled schema-1 marker");
        require(marker.get("cases_file").getAsString().equals(CASES.toString())&&marker.get("cases_sha256").getAsString().equals(CASES_SHA),"frozen case identity");
        require(marker.get("expected_cases").getAsInt()==EXPECTED_ROWS&&marker.get("batch_per_tick").getAsInt()==32,"fixed batch/count");
        var expected=JsonParser.parseString(CLASS_JSON).getAsJsonObject();
        require(expected.equals(marker.getAsJsonObject("expected_class_sha256")),"fixed seven-class identity");
        for(var entry:expected.entrySet()){
            byte[] bytes;
            try(var stream=LegacyElectricAdapterPublicProbe.class.getClassLoader().getResourceAsStream(entry.getKey())){
                require(stream!=null,"baseline class resource exists: "+entry.getKey());bytes=stream.readNBytes(1024*1024+1);
            }
            require(bytes.length<=1024*1024,"class resource bound");String actual=hash(bytes);
            classIdentities.add(Map.of("path",entry.getKey(),"sha256",actual,"expected",entry.getValue().getAsString()));
            require(actual.equals(entry.getValue().getAsString()),"original seven SI classes required: "+entry.getKey());
        }
        // Context hashes identify the surrounding approved overlay; they are not
        // interpreted as old-armor/default equivalence. Only the seven sampled
        // API owners above are required to match the historic SI implementation.
        for(String resource:List.of(
                "com/singularity_iteration/mio_icif/api/item/IBatteryItem.class",
                "com/singularity_iteration/mio_icif/api/item/IElectricArmorItem.class",
                "com/singularity_iteration/mio_icif/api/item/AbstractElectricArmor.class",
                "com/singularity_iteration/mio_icif/api/item/AbstractBattery.class",
                "dev/scex/si/energy/BatteryTransfer.class")){
            try(var stream=LegacyElectricAdapterPublicProbe.class.getClassLoader().getResourceAsStream(resource)){
                require(stream!=null,"context class resource exists: "+resource);
                byte[] bytes=stream.readNBytes(1024*1024+1);require(bytes.length<=1024*1024,"context class bound");
                contextIdentities.add(Map.of("path",resource,"sha256",hash(bytes)));
            }
        }
        byte[] bytes=Files.readAllBytes(CASES);require(bytes.length<8*1024*1024&&hash(bytes).equals(CASES_SHA),"matrix bytes/hash");
        var fixture=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
        cases=fixture.getAsJsonArray("cases");require(cases.size()==EXPECTED_ROWS,"complete expected case count");
        Files.writeString(ROWS,"",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        initialized=true;startedTick=tick;
    }
    private Map<String,Object> finish(int tick,Exception failure)throws Exception {
        finished=true;var result=new LinkedHashMap<String,Object>();
        result.put("passed",failure==null&&next==EXPECTED_ROWS);result.put("status",failure==null?"PASS_SCOPED_PUBLIC_ADAPTER_OBSERVATION":"FAIL_PUBLIC_ADAPTER_OBSERVATION_HARNESS");
        result.put("cases",next);result.put("expected_cases",EXPECTED_ROWS);result.put("caught_call_exceptions",thrown);
        result.put("group_counts",groups);result.put("started_tick",startedTick);result.put("finished_tick",tick);
        result.put("cases_sha256",CASES_SHA);result.put("baseline_class_resources",classIdentities);
        result.put("surrounding_context_resources",contextIdentities);
        result.put("reflection_used",false);result.put("old_method_code_decoded",false);result.put("candidate_activated",false);
        result.put("entities_added_to_world",0);result.put("real_player_inventories_touched",0);result.put("blocks_changed",0);
        result.put("detached_fixture_inventories_used",true);
        result.put("behavior_equivalence_approved",false);result.put("full_mod_gate","UNCHANGED_INCOMPLETE");
        if(Files.exists(ROWS)){result.put("observations_file",ROWS.toString());result.put("observations_bytes",Files.size(ROWS));result.put("observations_sha256",hash(Files.readAllBytes(ROWS)));}
        if(failure!=null){result.put("failure_type",failure.getClass().getName());result.put("failure_message",String.valueOf(failure.getMessage()));}
        result.put("scope","Frozen old SI constructor/public-method observations only. Exceptions, refused transfers and callback mutations are raw observations. No automatic parity, armor balance, external addon or IC2 behavior claim.");
        Files.writeString(OUTPUT,new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(result)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        return result;
    }
    /** Call once per server tick when the marker exists; returns a summary only when finished. */
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception {
        if(finished||tick<20||tick==lastTick)return null;lastTick=tick;
        try{
            require(world.getServer().isSameThread(),"normal server thread");
            if(!initialized)initialize(tick);
            int end=Math.min(cases.size(),next+32);
            for(;next<end;next++){
                var row=observe(world,cases.get(next).getAsJsonObject(),tick);
                // Persist each completed row before advancing; an unexpected
                // harness failure cannot erase earlier results in this batch.
                Files.writeString(ROWS,GSON.toJson(row)+"\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND);
            }
            return next==cases.size()?finish(tick,null):null;
        }catch(Exception failure){
            var result=finish(tick,failure);
            throw new IllegalStateException("R133 adapter observation harness failed; retained "+OUTPUT+" "+result.get("failure_message"),failure);
        }
    }
}
