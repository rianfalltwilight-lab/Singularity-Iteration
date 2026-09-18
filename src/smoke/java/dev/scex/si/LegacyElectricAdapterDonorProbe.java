// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.singularity_iteration.mio_icif.api.item.electric.ISpecialElectricItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.registries.RegisterEvent;

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

/** Registered test armor and ordinary detached ServerPlayer public calls. No IC2 or old implementation access. */
public final class LegacyElectricAdapterDonorProbe {
    private static final Path MARKER=Path.of("legacy-electric-donor-candidate-r135.json");
    private static final Path CASES=Path.of("legacy-electric-donor-cases-r135.json");
    private static final Path ROWS=Path.of("legacy-electric-donor-candidate-r135-observations.jsonl");
    private static final Path OUTPUT=Path.of("legacy-electric-donor-candidate-r135-result.json");
    private static final String CASES_SHA="1a37dbbb04a0517a0e4489f7679a7603301347498531137c217914cf93c3cf49";
    private static final String CLASS_JSON="{\"com/singularity_iteration/mio_icif/api/item/BatteryElectricAdapter.class\":\"87057ce8d8422d6b84b1e311dfb6cead93e1262ff5fa93a8652538ccdeeed31e\",\"com/singularity_iteration/mio_icif/api/item/electric/ISpecialElectricItem.class\":\"8723300c3156951fd5d6a75f7d831bd11e783d43383135cf29f5bb66c7f88a04\",\"com/singularity_iteration/mio_icif/api/item/electric/IElectricItemManager.class\":\"f0f0691f5d5716de259242fad336de1acbf9f00650ede829f00c9ce5fc00bd3b\",\"com/singularity_iteration/mio_icif/api/item/electric/IElectricItem.class\":\"41cb06fde0a5c50cb7760d82688f3fefbd263a21f9ac4179008442708e56c858\",\"com/singularity_iteration/mio_icif/api/item/electric/IBackupElectricItemManager.class\":\"622fb888fe9e0e65c69002dbe61c457781823b5c46ac3c9ef5a779e270f7a29b\",\"com/singularity_iteration/mio_icif/api/item/BatteryElectricAdapter$ElectricItemWrapper.class\":\"a6cea4356ef2503fdd4b97e7adc8aa41d4278270d97fbc1376d907e2827f98bc\",\"com/singularity_iteration/mio_icif/api/item/BatteryElectricAdapter$BatteryItemWrapper.class\":\"ec4c645a24a479e246b00a331b97abb34daf624ec4dbfa2b7958a9b7a168b07a\"}";
    private static final int EXPECTED_ROWS=840;
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
        var stack=item(world,str(row,"target_kind","paper").equals("legacy")?"scex_si_smoke:r135_legacy_head":"minecraft:paper",(int)number(row,"stack_count",1));
        if(stack.isEmpty())return stack;
        var tag=new CompoundTag();tag.putLong(ENERGY,number(row,"initial_energy",333));
        tag.putLong(CAPACITY,number(row,"capacity",1000));tag.putLong(RATE,number(row,"charge_rate",64));
        tag.putString("scex_r133_keep","unrelated sentinel");tag.putString("r135_label","target");tag.putLong("r135_tier",1);tag.putBoolean("r135_provide",true);
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
            row.put("owner_label",stack==null?"none":data(stack).getString("r135_label"));row.put("original_stack_identity",stack==original);row.put("before",stack==null?null:read(stack,ENERGY));
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
            String mode=data(stack).contains("r135_mode")?data(stack).getString("r135_mode"):this.mode;
            var row=call(name,stack);row.put("requested",amount);row.put("tier",tier);
            row.put("boolean_arguments",flags);row.put("fixture_mode",mode);
            if(mode.equals("THROW_BEFORE")){row.put("thrown","ProviderFault/before");throw new ProviderFault("controlled pre-mutation failure");}
            long before=read(stack,ENERGY),capacity=read(stack,CAPACITY);
            long room=charging?Math.max(0,capacity-before):Math.max(0,before);
            long changed=amount<=0?0:Math.min(amount,room);
            if(name.startsWith("manager.")&&str(spec,"manager_policy","").equals("OBSERVED_WRAPPER_FLAGS")
                    && flags.length>=2 && (flags[1] || !charging&&flags.length>=3&&!flags[2])) {
                row.put("fixture_simulation",true);row.put("reported",changed);row.put("after",before);return changed;
            }
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

    private static State activeState;
    private static int suppressedPackets;
    private static State active(){if(activeState==null)throw new IllegalStateException("fixture callback outside observed call");return activeState;}
    public static class LegacyArmor extends ArmorItem implements IElectricItem,ISpecialElectricItem {
        public LegacyArmor(ArmorItem.Type type){super(ArmorMaterials.LEATHER,type,new Item.Properties().stacksTo(1));}
        @Override public boolean canProvideEnergy(ItemStack stack){return active().query("donor.canProvideEnergy",stack,data(stack).getBoolean("r135_provide"));}
        @Override public long getMaxCharge(ItemStack stack){return active().query("donor.getMaxCharge",stack,read(stack,CAPACITY));}
        @Override public int getTier(ItemStack stack){return (int)active().query("donor.getTier",stack,read(stack,"r135_tier"));}
        @Override public long getTransferLimit(ItemStack stack){return active().query("donor.getTransferLimit",stack,read(stack,RATE));}
        @Override public IElectricItemManager getManager(ItemStack stack){active().call("donor.getManager",stack);return new ControlledElectric(active());}
    }
    public static final class DualArmor extends LegacyArmor implements IBatteryItem {
        public DualArmor(ArmorItem.Type type){super(type);}
        @Override public long getMaxEnergy(){return 1000;}
        @Override public long getMaxEnergy(ItemStack stack){return active().query("dual.getMaxEnergy",stack,read(stack,CAPACITY));}
        @Override public long getEnergy(ItemStack stack){return active().query("dual.getEnergy",stack,read(stack,ENERGY));}
        @Override public long addEnergy(ItemStack stack,long amount){return active().transfer("dual.addEnergy",stack,amount,true,null);}
        @Override public long extractEnergy(ItemStack stack,long amount){return active().transfer("dual.extractEnergy",stack,amount,false,null);}
        @Override public boolean isFull(ItemStack stack){return getEnergy(stack)>=getMaxEnergy(stack);}
        @Override public boolean isEmpty(ItemStack stack){return getEnergy(stack)<=0;}
        @Override public long getChargeRate(ItemStack stack){return active().query("dual.getChargeRate",stack,read(stack,RATE));}
    }
    public static void registerItems(RegisterEvent event){
        for(String kind:List.of("legacy","dual"))for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET)){
            var type=switch(slot){case HEAD->ArmorItem.Type.HELMET;case CHEST->ArmorItem.Type.CHESTPLATE;case LEGS->ArmorItem.Type.LEGGINGS;case FEET->ArmorItem.Type.BOOTS;default->throw new IllegalArgumentException();};
            event.register(Registries.ITEM,ResourceLocation.fromNamespaceAndPath("scex_si_smoke","r135_"+kind+"_"+slot.name().toLowerCase(java.util.Locale.ROOT)),
                ()->kind.equals("legacy")?new LegacyArmor(type):new DualArmor(type));
        }
    }
    private static LivingEntity actor(ServerLevel world,JsonObject spec){
        var profile=new GameProfile(UUID.nameUUIDFromBytes(("SCEX-R135-"+str(spec,"id","")).getBytes(StandardCharsets.UTF_8)),"R135DonorProbe");
        require(world.getServer().getPlayerList().getPlayer(profile.getId())==null,"fixture UUID is not a connected player");
        var actor=new ServerPlayer(world.getServer(),world,profile,ClientInformation.createDefault());
        // Keep ordinary ServerPlayer inventory/equipment methods; only outgoing test packets are discarded.
        new ServerGamePacketListenerImpl(world.getServer(),new Connection(PacketFlow.SERVERBOUND),actor,CommonListenerCookie.createInitial(profile,false)){
            @Override public void send(Packet<?> packet){suppressedPackets++;}
            @Override public void send(Packet<?> packet,PacketSendListener listener){suppressedPackets++;}
        };
        require(actor.getClass()==ServerPlayer.class&&!world.getServer().getPlayerList().getPlayers().contains(actor),"ordinary detached ServerPlayer");
        var seen=new java.util.HashSet<EquipmentSlot>();
        for(var value:spec.getAsJsonArray("donors")){
            var donor=value.getAsJsonObject();var slot=EquipmentSlot.valueOf(donor.get("slot").getAsString());require(seen.add(slot),"unique equipped donor slot");
            var stack=item(world,"scex_si_smoke:r135_"+donor.get("kind").getAsString()+"_"+slot.name().toLowerCase(java.util.Locale.ROOT),1);
            require(stack.getItem() instanceof ArmorItem&&stack.getItem() instanceof IElectricItem&&stack.getItem() instanceof ISpecialElectricItem,"normal registered legacy armor donor");
            var tag=new CompoundTag();tag.putLong(ENERGY,donor.get("energy").getAsLong());tag.putLong(CAPACITY,1000);tag.putLong(RATE,donor.get("rate").getAsLong());
            tag.putLong("r135_tier",donor.get("tier").getAsLong());tag.putBoolean("r135_provide",donor.get("can_provide").getAsBoolean());
            tag.putString("r135_mode",donor.get("mode").getAsString());tag.putString("r135_label",slot.name());tag.putString("r135_sentinel","preserve-donor-components");
            stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));actor.setItemSlot(slot,stack);
            require(actor.getItemBySlot(slot)==stack&&actor.getInventory().armor.get(slot.getIndex())==stack,"real ServerPlayer armor inventory receives exact donor");
        }
        return actor;
    }
    private static List<Map<String,Object>> equipment(ServerLevel world,LivingEntity actor){
        var rows=new ArrayList<Map<String,Object>>();
        for(var slot:EquipmentSlot.values()){
            var stack=actor.getItemBySlot(slot);var row=new LinkedHashMap<String,Object>();row.put("slot",slot.name());row.put("stack",snapshot(world,stack));
            row.put("fixture_energy",read(stack,ENERGY));row.put("legacy_electric",stack.getItem() instanceof IElectricItem);row.put("special_manager",stack.getItem() instanceof ISpecialElectricItem);
            row.put("battery_interface",stack.getItem() instanceof IBatteryItem);if(!stack.isEmpty())row.put("actual_item_class",stack.getItem().getClass().getName());rows.add(row);
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
        row.put("phase","constructor");activeState=state;
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
            state.reentry=null;activeState=null;if(actor instanceof ServerPlayer player)player.getTextFilter().leave();
        }
        row.put("provider_callbacks",List.copyOf(state.calls));row.put("after",snapshot(world,stack));
        row.put("equipment_after",equipment(world,actor));row.put("components_and_count_unchanged",ItemStack.matches(before,stack));
        row.put("fixture_energy_delta",read(stack,ENERGY)-originalEnergy);row.put("ordinary_server_player",actor.getClass()==ServerPlayer.class);
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
            try(var stream=LegacyElectricAdapterDonorProbe.class.getClassLoader().getResourceAsStream(entry.getKey())){
                require(stream!=null,"baseline class resource exists: "+entry.getKey());bytes=stream.readNBytes(1024*1024+1);
            }
            require(bytes.length<=1024*1024,"class resource bound");String actual=hash(bytes);
            classIdentities.add(Map.of("path",entry.getKey(),"sha256",actual,"expected",entry.getValue().getAsString()));
            require(actual.equals(entry.getValue().getAsString()),"frozen candidate seven SI classes required: "+entry.getKey());
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
            try(var stream=LegacyElectricAdapterDonorProbe.class.getClassLoader().getResourceAsStream(resource)){
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
        result.put("passed",failure==null&&next==EXPECTED_ROWS);result.put("status",failure==null?"PASS_SCOPED_CANDIDATE_LEGACY_DONOR_OBSERVATION":"FAIL_REGISTERED_LEGACY_DONOR_HARNESS");
        result.put("cases",next);result.put("expected_cases",EXPECTED_ROWS);result.put("caught_call_exceptions",thrown);
        result.put("group_counts",groups);result.put("started_tick",startedTick);result.put("finished_tick",tick);
        result.put("cases_sha256",CASES_SHA);result.put("baseline_class_resources",classIdentities);
        result.put("surrounding_context_resources",contextIdentities);
        result.put("reflection_used",false);result.put("old_method_code_decoded",false);result.put("candidate_activated",true);result.put("sampled_class_role","INDEPENDENT_R135_CANDIDATE");
        result.put("entities_added_to_world",0);result.put("real_player_inventories_touched",0);result.put("blocks_changed",0);
        result.put("detached_fixture_inventories_used",true);result.put("ordinary_server_player",true);result.put("test_items_registered",8);result.put("suppressed_packet_calls",suppressedPackets);
        result.put("behavior_equivalence_approved",false);result.put("full_mod_gate","UNCHANGED_INCOMPLETE");
        if(Files.exists(ROWS)){result.put("observations_file",ROWS.toString());result.put("observations_bytes",Files.size(ROWS));result.put("observations_sha256",hash(Files.readAllBytes(ROWS)));}
        if(failure!=null){result.put("failure_type",failure.getClass().getName());result.put("failure_message",String.valueOf(failure.getMessage()));}
        result.put("scope","Independent R135 candidate constructor/public-method observations against unchanged frozen inputs. Exceptions, refused transfers and callback mutations are raw observations. Ordinary detached ServerPlayer and two explicit controlled manager policies; no automatic parity, external addon, connected-client or IC2 behavior claim.");
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
