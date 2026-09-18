// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_future_elc;
import com.singularity_iteration.mio_icif.Items.Normal.mio_icif_normal;
import com.singularity_iteration.mio_icif.Menu.Producer.FutureElcMenu;
import com.singularity_iteration.mio_icif.Singularity_Iteration_Config;
import com.singularity_iteration.mio_icif.future.CommodityCategory;
import com.singularity_iteration.mio_icif.future.FutureCommodityManager;
import com.singularity_iteration.mio_icif.future.FutureMarketData;
import com.singularity_iteration.mio_icif.network.FutureTradePacket;
import dev.scex.energy.EnergyAmount;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.handling.ServerPayloadContext;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Normal player/menu/handler calls. No production tick is invoked by the probe. */
public final class FutureTradeWorldProbe {
    public static final Path MARKER = Path.of("future-trade-world-r134.json");
    public static final Path COLD_MARKER = Path.of("future-trade-cold-r134.json");
    private static final Path OUTPUT = Path.of("future-trade-world-r134-result.json");
    private static final ResourceLocation CALLBACK = ResourceLocation.parse("scex_si_smoke:future_callback");
    private static final BlockPos MAIN = new BlockPos(23408,80,100), OTHER = MAIN.east(4);
    private static final BlockPos UNLOAD = new BlockPos(23504,80,100), REMOTE = new BlockPos(24408,80,100);
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0134-0000-000000000001");
    private static Runnable itemHook;
    private static int hookCalls;
    private static final class CallbackItem extends Item {
        CallbackItem() { super(new Properties().stacksTo(64)); }
        @Override public int getMaxStackSize(ItemStack stack) {
            Runnable run = itemHook;
            if (run != null) { itemHook = null; hookCalls++; run.run(); }
            return 64;
        }
    }
    public static void registerItems(RegisterEvent event) {
        event.register(Registries.ITEM, CALLBACK, CallbackItem::new);
    }
    /** Only outbound delivery is suppressed; all inbound handlers are the real implementation. */
    /** Netty invokes Connection.channelActive through the real pipeline, populating channel attributes. */
    private static Connection embeddedConnection() {
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
        if (connection.channel() != channel || !channel.isActive())
            throw new IllegalStateException("R134 embedded connection did not become active");
        return connection;
    }
    private static void releaseConnection(ServerPlayer player) {
        if (player.connection.getConnection().channel() instanceof io.netty.channel.embedded.EmbeddedChannel channel)
            channel.finishAndReleaseAll();
    }
    private static final class ProbeConnection extends ServerGamePacketListenerImpl {
        int outbound;
        ProbeConnection(ServerLevel level, ServerPlayer player) {
            super(level.getServer(),embeddedConnection(),player,
                CommonListenerCookie.createInitial(player.getGameProfile(),false));
        }
        @Override public void send(Packet<?> packet) { outbound++; }
        @Override public void send(Packet<?> packet, PacketSendListener listener) { outbound++; }
    }
    private static final class ProbePlayer extends ServerPlayer {
        int naturalTicks;
        ProbePlayer(ServerLevel level, UUID id) {
            super(level.getServer(),level,new GameProfile(id,"SI_R134_Future"),ClientInformation.createDefault());
            connection = new ProbeConnection(level,this);
            setGameMode(GameType.SURVIVAL); setNoGravity(true);
        }
        @Override public void tick() { super.tick(); naturalTicks++; }
    }
    private static final class FailingMenu extends FutureElcMenu {
        boolean armed;
        FailingMenu(int id,Inventory inventory,mio_icif_future_elc owner) {
            super(id,inventory,owner,null,owner.getContainerData());
        }
        @Override public void broadcastChanges() {
            if (armed) { armed=false; throw new IllegalStateException("R134 controlled postcommit notification"); }
            super.broadcastChanges();
        }
    }
    private record Case(String id, Runnable prepare, Runnable run) {}
    private final List<Case> cases = new ArrayList<>();
    private final List<Map<String,Object>> rows = new ArrayList<>();
    private ServerLevel world;
    private ProbePlayer player;
    private mio_icif_future_elc machine, other, stale;
    private List<? extends String> originalCommodities;
    private int originalFee, originalLimit;
    private Map<String,Integer> originalPrices;
    private int assertions, caseIndex, phase, phaseTick, lastTick;
    private boolean initialized, finished, playerInWorld, configurationCaptured;
    private CompletableFuture<List<Boolean>> offThread;
    private Map<String,Object> before, active, persistence;
    private List<ItemStack> beforeItems;
    private long beforeEu, beforeFraction;
    private int beforeVolume;
    private int unloadObservedTick = -1;
    private void check(boolean value,String message) {
        assertions++; if(!value) throw new AssertionError("R134 future: "+message);
    }
    private Item coin() { return mio_icif_normal.COIN.get(); }
    private Item callbackItem() { return BuiltInRegistries.ITEM.get(CALLBACK); }
    private int volume() { return machine.getContainerData().get(mio_icif_future_elc.DATA_DAILY_VOLUME); }
    private long eu() { return machine.getEnergyStorageInternal().getAmount(); }
    private long fraction() { return machine.getEnergyStorageInternal().scexExactAmount().fraction(); }
    private long count(Item item) {
        long result=0; for(int i=0;i<41;i++) {var stack=player.getInventory().getItem(i);if(stack.is(item))result+=stack.getCount();}
        return result;
    }
    private List<ItemStack> inventoryCopy() {
        var list=new ArrayList<ItemStack>();
        for(int i=0;i<41;i++) list.add(player.getInventory().getItem(i).copy());
        list.add(player.containerMenu.getCarried().copy());return list;
    }
    private void unchangedItems(List<ItemStack> expected) {
        var actual=inventoryCopy();check(actual.size()==expected.size(),"inventory snapshot size");
        for(int i=0;i<actual.size();i++)check(ItemStack.matches(actual.get(i),expected.get(i)),"unchanged full stack "+i);
    }
    private void set(int slot,Item item,int count) { player.getInventory().setItem(slot,new ItemStack(item,count)); }
    private void fill() { for(int i=0;i<36;i++)set(i,Items.BEDROCK,64); }
    private void preparePartialOutput(boolean sell,int quantity) {
        fill();set(0,sell?Items.IRON_INGOT:coin(),64);
        var output=new ItemStack(sell?coin():Items.IRON_INGOT);
        var inventory=player.getInventory();
        int maximum=inventory.getMaxStackSize(output);
        check(maximum>1,"real inventory output capacity is positive");
        set(1,output.getItem(),maximum-1);
        int price=machine.getCurrentPrice(machine.getSelectedCommodity());
        long debit=sell?quantity:(long)price*quantity;
        long produced=sell?(long)price*quantity:quantity;
        check(debit>0&&debit<inventory.getItem(0).getCount(),"input debit does not free its slot");
        check(produced>1,"output exceeds the one remaining merge position");
        long room=0;
        for(int i=0;i<36;i++) {
            var existing=inventory.getItem(i);check(!existing.isEmpty(),"no empty main output slot "+i);
            if(ItemStack.isSameItemSameComponents(existing,output))room+=Math.max(0,inventory.getMaxStackSize(existing)-existing.getCount());
        }
        var offhand=inventory.getItem(40);
        if(!offhand.isEmpty()&&ItemStack.isSameItemSameComponents(offhand,output))room+=Math.max(0,inventory.getMaxStackSize(offhand)-offhand.getCount());
        check(room==1,"actual eligible pretrade output room is exactly one");
        active.put("output_capacity",Map.of("item",BuiltInRegistries.ITEM.getKey(output.getItem()).toString(),
            "item_max",output.getMaxStackSize(),"container_max",inventory.getMaxStackSize(),
            "effective_max",maximum,"existing_count",maximum-1,"pretrade_room",room,
            "input_debit",debit,"input_count",inventory.getItem(0).getCount(),"output_count",produced));
    }
    private void clearInventory() {
        for(int i=0;i<41;i++)player.getInventory().setItem(i,ItemStack.EMPTY);
        player.containerMenu.setCarried(ItemStack.EMPTY);player.getInventory().selected=0;
    }
    private ItemStack marked(Item item,int count) {
        var stack=new ItemStack(item,count);var tag=new CompoundTag();tag.putString("r134","preserve_components");
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));return stack;
    }
    private Object stack(ItemStack item) {
        if(item.isEmpty())return Map.of("empty",true);
        var ops=world.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var nbt=ItemStack.STRICT_CODEC.encodeStart(ops,item).getOrThrow();
        var copy=ItemStack.STRICT_CODEC.parse(ops,nbt).getOrThrow();
        check(ItemStack.matches(item,copy),"real inventory strict codec roundtrip");
        return Map.of("stack",nbt.toString(),"all_components",DataComponentMap.CODEC.encodeStart(ops,item.getComponents()).getOrThrow().toString());
    }
    private Map<String,Object> snapshot() {
        check(itemHook==null,"observation cannot trigger an armed item hook");
        var data=new LinkedHashMap<String,Object>();var inventory=new ArrayList<Object>();
        for(int i=0;i<41;i++)inventory.add(stack(player.getInventory().getItem(i)));
        data.put("inventory",inventory);data.put("carried",stack(player.containerMenu.getCarried()));
        data.put("inventory_revision",player.getInventory().getTimesChanged());data.put("selected",player.getInventory().selected);
        data.put("energy",eu());data.put("fraction",fraction());data.put("daily_volume",volume());
        data.put("saved_owner",machine.saveWithFullMetadata(world.registryAccess()).toString());
        data.put("network_controlled",machine.getEnergyStorageInternal().scexNetworkControlled());
        data.put("natural_player_ticks",player.naturalTicks);data.put("menu",player.containerMenu.getClass().getName());
        data.put("menu_id",player.containerMenu.containerId);data.put("owner_removed",machine.isRemoved());
        data.put("price",machine.getSelectedCommodity()==null?-1:machine.getCurrentPrice(machine.getSelectedCommodity()));
        data.put("fee",Singularity_Iteration_Config.FUTURE_ENERGY_PER_TRADE.get());
        data.put("item_entities",world.getEntitiesOfClass(ItemEntity.class,new AABB(MAIN).inflate(12)).size());
        return data;
    }
    private mio_icif_future_elc place(BlockPos at) {
        var chunk=world.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4);
        check(chunk!=null&&world.shouldTickBlocksAt(ChunkPos.asLong(at)),"fixture provided natural ticking chunk "+at);
        if(chunk.getBlockEntities().get(at) instanceof mio_icif_future_elc existing)return existing;
        var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:producer/block_future_elc"));
        check(block!=Blocks.AIR,"real future block registration");
        world.setBlockAndUpdate(at.below(),Blocks.STONE.defaultBlockState());
        check(world.setBlockAndUpdate(at,block.defaultBlockState()),"normal owner placement");
        var be=chunk.getBlockEntities().get(at);check(be instanceof mio_icif_future_elc,"real future entity factory");
        var owner=(mio_icif_future_elc)be;
        check(owner.getEnergyStorageInternal().scexNetworkControlled(),"independent ownership came from production policy");
        check(owner.getEnergyStorageInternal().getCapacity()==10000&&owner.getEnergyStorageInternal().getMaxExtract()==100,
            "capacity10000 and original external extract100 preserved");
        return owner;
    }
    private FutureElcMenu open(mio_icif_future_elc owner) {
        check(player.openMenu(owner).isPresent(),"normal ServerPlayer.openMenu accepted provider");
        check(player.containerMenu instanceof FutureElcMenu,"actual registered menu factory");
        var menu=(FutureElcMenu)player.containerMenu;check(menu.getBlockEntity()==owner,"menu owns actual BE");return menu;
    }
    private void fixtureConfig() {
        Singularity_Iteration_Config.FUTURE_COMMODITIES.set(List.of("minecraft:iron_ingot,2,0.0,mineral",CALLBACK+",2,0.0,other"));
        Singularity_Iteration_Config.FUTURE_DAILY_LIMIT.set(100);
        FutureCommodityManager.reload();
        var market=FutureMarketData.get(world);market.setPrice("minecraft:iron_ingot",2);market.setPrice(CALLBACK.toString(),2);
    }
    private void reset(int fee,long energy,int quantity,boolean selling) {
        itemHook=null;hookCalls=0;fixtureConfig();Singularity_Iteration_Config.FUTURE_ENERGY_PER_TRADE.set(fee);
        machine=place(MAIN);other=place(OTHER);
        player.setGameMode(GameType.SURVIVAL);player.setHealth(20);player.setPos(MAIN.getX()+0.5,80,102.5);
        player.closeContainer();clearInventory();
        var tag=machine.saveWithFullMetadata(world.registryAccess());tag.putInt("DailyVolume",0);
        tag.putLong("CurrentDay",world.getDayTime()/24000L);tag.putInt("Page",0);tag.putInt("TradeQuantity",quantity);
        machine.loadWithComponents(tag,world.registryAccess());machine.getEnergyStorageInternal().setEnergy(energy);
        other.getEnergyStorageInternal().setEnergy(1000);
        machine.setCategory(CommodityCategory.MINERAL);machine.setSelectedCommodity(0);machine.setTradeQuantity(quantity);
        set(0,selling?Items.IRON_INGOT:coin(),selling?quantity:quantity*2);
        open(machine);check(machine.getSelectedCommodity()!=null&&machine.getSelectedCommodity().getItem()==Items.IRON_INGOT,"configured normal item selected");
    }
    private boolean direct(boolean sell) { active.put("entry","public execute"+(sell?"Sell":"Buy"));boolean result=sell?machine.executeSell(player):machine.executeBuy(player);active.put("direct_return",result);return result; }
    private void button(int button) {
        active.put("entry","vanilla ServerGamePacketListenerImpl.handleContainerButtonClick");
        player.connection.handleContainerButtonClick(new ServerboundContainerButtonClickPacket(player.containerMenu.containerId,button));
    }
    private void payload(BlockPos at,int action,int selected,int quantity) {
        active.put("entry","actual FutureTradePacket codec + handle + ServerPayloadContext; no socket roundtrip");
        var raw=new FutureTradePacket(at,action,selected,quantity);var buffer=new FriendlyByteBuf(Unpooled.buffer());
        try {
            FutureTradePacket.CODEC.encode(buffer,raw);var decoded=FutureTradePacket.CODEC.decode(buffer);
            check(decoded.equals(raw)&&buffer.readableBytes()==0,"actual payload codec roundtrip");
            FutureTradePacket.handle(decoded,new ServerPayloadContext(player.connection,FutureTradePacket.TYPE.id()));
        } finally {buffer.release();}
    }
    private void success(boolean sell,int fee,int quantity,int price) {
        check(eu()==beforeEu-fee&&fraction()==beforeFraction,"exact fee and fractional preservation");
        check(volume()==beforeVolume+quantity,"successful daily quantity exactly once");
        long coinsBefore=beforeItems.stream().limit(41).filter(s->s.is(coin())).mapToLong(ItemStack::getCount).sum();
        long itemBefore=beforeItems.stream().limit(41).filter(s->s.is(Items.IRON_INGOT)).mapToLong(ItemStack::getCount).sum();
        check(count(coin())==coinsBefore+(sell?1:-1)*(long)price*quantity,"exact coin conservation");
        check(count(Items.IRON_INGOT)==itemBefore+(sell?-quantity:quantity),"exact commodity conservation");
    }
    private void rejected() {check(eu()==beforeEu&&fraction()==beforeFraction,"rejected payment restored only its token");check(volume()==beforeVolume,"rejected quantity unchanged");unchangedItems(beforeItems);}
    private void add(String id,Runnable prep,Runnable run) {cases.add(new Case(id,prep,run));}
    private void buildCases() {
        for(int fee:new int[]{0,1,100,101,10000})for(boolean sell:new boolean[]{false,true}) {
            String id=(sell?"sell":"buy")+"_exact_fee_"+fee;
            add(id,()->reset(fee,fee,1,sell),()->{
                if(fee==100)button(sell?FutureElcMenu.BUTTON_SELL:FutureElcMenu.BUTTON_BUY);
                else if(fee==10000)payload(MAIN,sell?FutureTradePacket.ACTION_SELL:FutureTradePacket.ACTION_BUY,0,1);
                else check(direct(sell),"exact-fee direct return");
                success(sell,fee,1,2);
            });
        }
        for(boolean sell:new boolean[]{false,true}) {
            String prefix=sell?"sell":"buy";
            add(prefix+"_fraction_preserved",()->{reset(101,1000,1,sell);check(machine.getEnergyStorageInternal().scexGenerateEnergy(new EnergyAmount(0,EnergyAmount.UNITS/2),false).fraction()==EnergyAmount.UNITS/2,"real independent fractional credit");},()->{check(direct(sell),"fraction trade");success(sell,101,1,2);});
            for(int fee:new int[]{1,101,10000})add(prefix+"_insufficient_"+fee,()->reset(fee,fee-1,1,sell),()->{check(!direct(sell),"short payment rejected");rejected();});
            add(prefix+"_insufficient_inputs",()->{reset(101,1000,2,sell);set(0,sell?Items.IRON_INGOT:coin(),sell?1:3);},()->{check(!direct(sell),"short input rejected");rejected();});
            for(int fee:new int[]{101,0})add(prefix+(fee==0?"_free_but_full":"_partial_output_room"),()->{
                reset(fee,fee==0?0:1000,2,sell);preparePartialOutput(sell,2);
            },()->{check(!direct(sell),"partial room rejects entire trade");rejected();});
            add(prefix+"_overflow_price",()->{reset(101,1000,64,sell);FutureMarketData.get(world).setPrice("minecraft:iron_ingot",Integer.MAX_VALUE);if(!sell)for(int i=0;i<36;i++)set(i,coin(),64);},()->{check(!direct(sell),"long total does not overflow to free trade");rejected();});
        }
        add("buy_consumed_slot_is_reused",()->{reset(101,1000,1,false);fill();set(0,coin(),2);},()->{check(direct(false),"freed input slot can accept output");success(false,101,1,2);check(player.getInventory().getItem(0).is(Items.IRON_INGOT),"slot0 reused");});
        add("buy_merge_order_and_components",()->{reset(101,1000,4,false);clearInventory();set(2,Items.IRON_INGOT,63);set(40,Items.IRON_INGOT,63);set(0,Items.IRON_INGOT,63);player.getInventory().setItem(3,marked(Items.IRON_INGOT,63));set(4,coin(),20);player.getInventory().selected=2;},()->{check(direct(false),"component aware merge");success(false,101,4,2);for(int i:new int[]{2,40,0})check(player.getInventory().getItem(i).getCount()==64,"preferred merge slot "+i);check(player.getInventory().getItem(1).is(Items.IRON_INGOT)&&player.getInventory().getItem(1).getCount()==1,"remaining default item in first empty main");check(ItemStack.matches(player.getInventory().getItem(3),beforeItems.get(3)),"custom component item was not merged");});
        add("sell_component_input_policy",()->{reset(101,1000,1,true);player.getInventory().setItem(0,marked(Items.IRON_INGOT,1));},()->{check(direct(true),"existing sale policy accepts tagged registered item");success(true,101,1,2);});
        add("daily_limit_exact_and_next",()->{reset(101,1000,1,false);set(0,coin(),4);var tag=machine.saveWithFullMetadata(world.registryAccess());tag.putInt("DailyVolume",99);machine.loadWithComponents(tag,world.registryAccess());},()->{check(direct(false),"exact limit accepted");check(!direct(false),"next quantity rejected");success(false,101,1,2);});
        for(String problem:List.of("closed_menu","another_owner_menu","distance_squared_over_64","different_dimension","spectator","dead_player","removed_owner","replaced_owner_same_block","off_server_thread"))
            add("reject_"+problem,()->reset(101,1000,1,false),()->permission(problem));
        add("bad_saved_quantity_page_volume",()->reset(101,1000,1,false),this::badSaved);
        add("packet_unloaded_arbitrary_position",()->reset(101,1000,1,false),()->{
            check(world.getChunkSource().getChunkNow(REMOTE.getX()>>4,REMOTE.getZ()>>4)==null,"remote target initially unloaded");
            for(int action:new int[]{0,3,4,5})payload(REMOTE,action,0,1);
            player.closeContainer();for(int action:new int[]{0,3,4,5})payload(REMOTE,action,0,1);
            rejected();check(world.getChunkSource().getChunkNow(REMOTE.getX()>>4,REMOTE.getZ()>>4)==null,"untrusted position never loaded");
        });
        add("packet_and_button_invalid_arguments",()->reset(101,1000,1,false),()->{
            payload(MAIN,Integer.MAX_VALUE,0,1);payload(MAIN,0,-1,1);payload(MAIN,0,99,1);payload(MAIN,5,0,0);payload(MAIN,5,0,65);
            button(99);check(machine.getSelectedCommodityIndex()==0&&machine.getTradeQuantity()==1,"invalid payloads/buttons leave selection unchanged");rejected();
        });
        for(String name:List.of("reenter_same_owner","reenter_other_owner","external_debit_then_throw","external_credit_then_throw","inventory_mutation","menu_closed","owner_destroyed","price_changed","fee_changed","catalog_reload"))
            add("callback_"+name,()->{reset(101,1000,1,false);machine.setCategory(CommodityCategory.OTHER);machine.setSelectedCommodity(0);set(5,Items.PAPER,7);},()->callback(name));
        add("postcommit_notification_exception",()->{
            reset(101,1000,1,false);player.openMenu(new MenuProvider(){
                @Override public Component getDisplayName(){return Component.literal("R134 future notification fixture");}
                @Override public AbstractContainerMenu createMenu(int id,Inventory inv,Player p){return new FailingMenu(id,inv,machine);}
            });check(player.containerMenu instanceof FailingMenu,"normal provider created controlled notification menu");
        },()->{((FailingMenu)player.containerMenu).armed=true;check(direct(false),"postcommit exception returns completed result");success(false,101,1,2);});
        add("extra_nonpositive_price_rejected",()->reset(101,1000,1,false),()->{for(int price:new int[]{0,-1}){FutureMarketData.get(world).setPrice("minecraft:iron_ingot",price);check(!direct(false)&&!direct(true),"nonpositive item price rejected");rejected();}});
        check(cases.size()==54,"53 frozen warm rows plus nonpositive price extension");
    }
    private void permission(String problem) {
        switch(problem) {
            case "closed_menu" -> player.closeContainer();
            case "another_owner_menu" -> open(other);
            case "distance_squared_over_64" -> player.setPos(MAIN.getX()+9.0,80,100.5);
            case "spectator" -> player.setGameMode(GameType.SPECTATOR);
            case "dead_player" -> player.setHealth(0);
            case "removed_owner" -> check(world.removeBlock(MAIN,false)&&machine.isRemoved(),"normal owner removal");
            case "replaced_owner_same_block" -> {check(world.removeBlock(MAIN,false),"remove predecessor");check(place(MAIN)!=machine,"same registered block now has new owner identity");}
            case "different_dimension" -> {
                var elsewhere=world.getServer().getLevel(Level.NETHER);check(elsewhere!=null,"normal second dimension exists");
                var visitor=new ProbePlayer(elsewhere,new UUID(134,2));visitor.setPos(MAIN.getX()+0.5,80,102.5);
                check(visitor.openMenu(machine).isPresent(),"ordinary provider opened for foreign-dimension counterparty");
                check(!machine.executeBuy(visitor)&&!machine.executeSell(visitor),"foreign world player rejected");
                visitor.closeContainer();visitor.getTextFilter().leave();releaseConnection(visitor);rejected();return;
            }
            case "off_server_thread" -> {
                var current=machine;var customer=player;
                offThread=CompletableFuture.supplyAsync(()->List.of(current.executeBuy(customer),current.executeSell(customer)));
                active.put("entry","public executeBuy/executeSell from worker; inspected on later natural tick");return;
            }
        }
        try {check(!direct(false)&&!direct(true),"unauthorized public entry rejected: "+problem);rejected();}
        finally {player.setHealth(20);player.setGameMode(GameType.SURVIVAL);player.setPos(MAIN.getX()+0.5,80,102.5);}
    }
    private void badSaved() {
        var tag=machine.saveWithFullMetadata(world.registryAccess());tag.putInt("TradeQuantity",Integer.MAX_VALUE);tag.putInt("Page",Integer.MAX_VALUE);tag.putInt("DailyVolume",Integer.MAX_VALUE);
        machine.loadWithComponents(tag,world.registryAccess());check(machine.getTradeQuantity()==64,"saved quantity upper clamp");
        check(machine.getCurrentPageCommodities().isEmpty()&&((FutureElcMenu)player.containerMenu).getCurrentPageCommodities().isEmpty(),"server and menu page overflow bounded");
        check(!direct(false)&&!direct(true),"extreme saved trade rejected");unchangedItems(beforeItems);check(eu()==beforeEu,"extreme saved trade costs nothing");
        tag.putInt("TradeQuantity",Integer.MIN_VALUE);tag.putInt("Page",-1);tag.putInt("DailyVolume",-1);machine.loadWithComponents(tag,world.registryAccess());
        check(machine.getTradeQuantity()==1&&machine.getCurrentPage()==0&&volume()==0,"negative saved bounds normalized");
    }
    private void callback(String name) {
        hookCalls=0;itemHook=()->{
            check(world.getServer().isSameThread()&&eu()==899,"callback sees exact101 EU already prepaid");active.put("callback_balance",eu());
            switch(name) {
                case "reenter_same_owner" -> {check(!machine.executeBuy(player),"same owner recursion rejected");machine.setTradeQuantity(64);check(machine.getTradeQuantity()==1,"inflight quote setter ignored");}
                case "reenter_other_owner" -> {
                    var prior=player.containerMenu;other.setCategory(CommodityCategory.MINERAL);other.setSelectedCommodity(0);
                    open(other);check(!other.executeBuy(player)&&other.getEnergyStorageInternal().getAmount()==1000,"same player cannot recurse into another owner");
                    player.closeContainer();player.containerMenu=prior;
                }
                case "external_debit_then_throw" -> {check(machine.getEnergyStorageInternal().extract(7,false)==7,"real callback debit7");throw new IllegalStateException("R134 debit callback");}
                case "external_credit_then_throw" -> {check(machine.getEnergyStorageInternal().receive(7,false)==7,"real callback credit7");throw new IllegalStateException("R134 credit callback");}
                case "inventory_mutation" -> player.getInventory().getItem(5).shrink(1);
                case "menu_closed" -> player.closeContainer();
                case "owner_destroyed" -> check(world.removeBlock(MAIN,false)&&machine.isRemoved(),"real callback removes owner");
                case "price_changed" -> FutureMarketData.get(world).setPrice(CALLBACK.toString(),3);
                case "fee_changed" -> Singularity_Iteration_Config.FUTURE_ENERGY_PER_TRADE.set(102);
                case "catalog_reload" -> FutureCommodityManager.reload();
                default -> throw new IllegalStateException(name);
            }
        };
        boolean result;
        try {result=direct(false);} finally {itemHook=null;}
        check(hookCalls==1,"one real overridable item query callback");
        boolean accepted=name.startsWith("reenter_");check(result==accepted,"callback transaction result: "+name);
        long expected=accepted?899:name.equals("external_debit_then_throw")?993:name.equals("external_credit_then_throw")?1007:1000;
        check(eu()==expected&&fraction()==0,"callback delta survived own-token settlement");
        check(volume()==(accepted?1:0),"callback daily volume");
        if(accepted){check(count(coin())==0&&count(callbackItem())==1,"only one callback item purchased");}
        else if(name.equals("inventory_mutation")){var expectedItems=new ArrayList<>(beforeItems);expectedItems.set(5,new ItemStack(Items.PAPER,6));unchangedItems(expectedItems);}
        else unchangedItems(beforeItems);
        if(name.equals("owner_destroyed"))check(world.getChunkSource().getChunkNow(MAIN.getX()>>4,MAIN.getZ()>>4).getBlockEntities().get(MAIN)==null,"cancel never resurrects owner");
        // A subsequent ordinary main-thread operation must be possible; do not erase the primary observation.
        active.put("primary_after",snapshot());
        reset(101,1000,1,false);check(machine.executeBuy(player),"all locks released for next ordinary trade");
        check(eu()==899&&volume()==1,"followup trade exact");active.put("separate_followup_passed",true);
    }
    private void beginRow(Case test,int tick) {
        active=new LinkedHashMap<>();active.put("id",test.id());active.put("start_tick",tick);
        test.prepare().run();phase=1;phaseTick=tick;
    }
    private void finishRow() {
        itemHook=null;var after=snapshot();
        check(before.get("item_entities").equals(after.get("item_entities")),"trade created no overflow item entities");
        active.put("after",after);active.put("passed",true);active.put("end_tick",lastTick);
        rows.add(active);System.out.println("SCEX_FUTURE_CASE_PASS "+active.get("id"));caseIndex++;phase=0;
    }
    private void savePersistenceBaseline() throws Exception {
        active=new LinkedHashMap<>();active.put("id","normal_save_and_cold_readback");
        reset(101,1000,1,false);machine.getEnergyStorageInternal().scexGenerateEnergy(new EnergyAmount(0,EnergyAmount.UNITS/2),false);
        check(machine.executeBuy(player)&&eu()==899,"persistence sequence paid buy");
        preparePartialOutput(true,1);check(!machine.executeSell(player)&&eu()==899,"persistence sequence rejected partial output");
        clearInventory();set(0,coin(),2);set(5,Items.PAPER,7);machine.setCategory(CommodityCategory.OTHER);machine.setSelectedCommodity(0);
        itemHook=()->{check(machine.getEnergyStorageInternal().extract(7,false)==7,"persistence callback delta");throw new IllegalStateException("R134 persistence debit");};
        try{check(!machine.executeBuy(player),"persistence callback failure");}finally{itemHook=null;}
        check(eu()==892&&fraction()==EnergyAmount.UNITS/2&&volume()==1,"persistence exact final balance and paid quantity");
        player.closeContainer();persistence=snapshot();persistence.put("uuid",PLAYER_ID.toString());persistence.put("source_pid",ProcessHandle.current().pid());
        persistence.put("source_start",ProcessHandle.current().info().startInstant().orElseThrow().toString());
        persistence.put("inventory_nbt",player.getInventory().save(new net.minecraft.nbt.ListTag()).toString());
        world.getServer().getPlayerList().remove(player);playerInWorld=false;player.getTextFilter().leave();releaseConnection(player);
        Path saved=world.getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(PLAYER_ID+".dat");
        check(Files.isRegularFile(saved),"normal PlayerList.remove saved actual player data");
        var raw=NbtIo.readCompressed(saved,NbtAccounter.unlimitedHeap());
        check(raw.getList("Inventory",10).toString().equals(persistence.get("inventory_nbt")),"actual player data contains exact full inventory");
        persistence.put("player_file",saved.toString());persistence.put("player_file_sha256",hash(Files.readAllBytes(saved)));
        active.put("warm_saved",persistence);active.put("status","WARM_SAVED_REQUIRES_NEW_JVM");active.put("passed",false);rows.add(active);
    }
    private static String hash(byte[] bytes) throws Exception {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    private void setup(ServerLevel level) throws Exception {
        world=level;check(world.getServer().isSameThread(),"normal server thread");
        JsonObject marker=JsonParser.parseString(Files.readString(MARKER)).getAsJsonObject();
        check(marker.get("schema").getAsInt()==1&&marker.get("require_independent").getAsBoolean(),"bounded frozen marker");
        check(marker.get("unforce_tick").getAsInt()==220,"fixture unforce timing contract");
        check(callbackItem()!=Items.AIR,"callback item registered through mod RegisterEvent");
        originalCommodities=List.copyOf(Singularity_Iteration_Config.FUTURE_COMMODITIES.get());originalFee=Singularity_Iteration_Config.FUTURE_ENERGY_PER_TRADE.get();originalLimit=Singularity_Iteration_Config.FUTURE_DAILY_LIMIT.get();
        originalPrices=FutureMarketData.get(world).getAllPrices();configurationCaptured=true;fixtureConfig();
        machine=place(MAIN);other=place(OTHER);stale=place(UNLOAD);
        player=new ProbePlayer(world,PLAYER_ID);player.setPos(MAIN.getX()+0.5,80,102.5);world.addNewPlayer(player);playerInWorld=true;
        check(world.players().contains(player),"normal ServerPlayer world registration");buildCases();initialized=true;
    }
    private void cleanup() {
        itemHook=null;
        if(playerInWorld&&player!=null){player.closeContainer();world.removePlayerImmediately(player,Entity.RemovalReason.DISCARDED);player.getTextFilter().leave();releaseConnection(player);playerInWorld=false;}
        if(configurationCaptured){Singularity_Iteration_Config.FUTURE_COMMODITIES.set(originalCommodities);Singularity_Iteration_Config.FUTURE_ENERGY_PER_TRADE.set(originalFee);Singularity_Iteration_Config.FUTURE_DAILY_LIMIT.set(originalLimit);FutureCommodityManager.reload();FutureMarketData.get(world).setPrices(originalPrices);configurationCaptured=false;}
    }
    private Map<String,Object> receipt(boolean passed,String status) {
        var result=new LinkedHashMap<String,Object>();result.put("passed",passed);result.put("status",status);result.put("assertions",assertions);result.put("rows",rows);result.put("tick",lastTick);
        result.put("completed_matrix_rows",Math.min(caseIndex,53));result.put("matrix_cold_row_complete",false);result.put("extra_cases",List.of("extra_nonpositive_price_rejected","extra_actual_chunk_unload"));
        result.put("cold_baseline",persistence);result.put("production_ticks_called",0);result.put("socket_roundtrip","NOT_RUN");result.put("full_mod_gate","UNCHANGED_HELD");
        var origins=new LinkedHashMap<String,String>();for(Class<?> type:new Class<?>[]{mio_icif_future_elc.class,FutureElcMenu.class,FutureTradePacket.class,FutureTradeWorldProbe.class})origins.put(type.getName(),type.getProtectionDomain().getCodeSource().getLocation().toString());result.put("origins",origins);
        return result;
    }
    /** Invoked by the normal scenario dispatcher every server tick; owns no forced chunks. */
    public Map<String,Object> inspect(ServerLevel level,int tick) throws Exception {
        if(finished||tick<20)return null;lastTick=tick;
        try {
            if(!initialized){setup(level);return null;}
            check(world==level&&world.getServer().isSameThread(),"one server/world/main thread");
            if(caseIndex<cases.size()) {
                Case test=cases.get(caseIndex);
                if(phase==0){beginRow(test,tick);return null;}
                if(offThread!=null){check(tick-phaseTick<30,"bounded asynchronous rejection result");if(!offThread.isDone())return null;check(offThread.join().equals(List.of(false,false)),"both off-thread public calls rejected");offThread=null;rejected();finishRow();return null;}
                if(tick==phaseTick)return null;
                check(player.naturalTicks>0,"normal ServerPlayer tick occurred before action");
                before=snapshot();beforeItems=inventoryCopy();beforeEu=eu();beforeFraction=fraction();beforeVolume=volume();active.put("before",before);
                test.run().run();if(offThread==null)finishRow();return null;
            }
            if(persistence==null){check(tick<180,"warm cases completed before unload fixture window");savePersistenceBaseline();return null;}
            if(tick<221)return null;
            var chunk=world.getChunkSource().getChunkNow(UNLOAD.getX()>>4,UNLOAD.getZ()>>4);
            check(!world.getForcedChunks().contains(ChunkPos.asLong(UNLOAD)),"ordinary fixture command removed unload ticket");
            check(tick<900,"actual unload observed before bounded timeout");if(chunk!=null)return null;
            unloadObservedTick=tick;
            var ghost=new ProbePlayer(world,new UUID(134,3));ghost.setPos(UNLOAD.getX()+0.5,80,102.5);
            check(ghost.isAlive()&&ghost.openMenu(stale).isPresent(),"real alive ServerPlayer opens stale actual provider without joining world tickets");
            var oldEnergy=stale.getEnergyStorageInternal().scexExactAmount();
            check(!stale.executeBuy(ghost)&&!stale.executeSell(ghost),"unloaded actual owner cannot trade through stale reference");
            check(stale.getEnergyStorageInternal().scexExactAmount().equals(oldEnergy),"unloaded balance unchanged");
            check(world.getChunkSource().getChunkNow(UNLOAD.getX()>>4,UNLOAD.getZ()>>4)==null,"public stale calls never reload owner chunk");
            ghost.closeContainer();ghost.getTextFilter().leave();releaseConnection(ghost);
            rows.add(Map.of("id","extra_actual_chunk_unload","passed",true,"actual_unload_tick",unloadObservedTick,"stale_removed",stale.isRemoved(),"counterparty","real ServerPlayer, alive, deliberately not world-indexed to avoid ticket renewal"));
            cleanup();finished=true;var result=receipt(true,"PASS_SCOPED_WARM_53_PLUS_2_COLD_PENDING");
            Files.writeString(OUTPUT,new GsonBuilder().setPrettyPrinting().create().toJson(result));return result;
        } catch(Exception|AssertionError failure) {
            itemHook=null;if(active!=null){active.put("passed",false);active.put("failure",failure.toString());if(!rows.contains(active))rows.add(active);}
            cleanup();finished=true;var result=receipt(false,"FAIL");result.put("failure",failure.toString());
            Files.writeString(OUTPUT,new GsonBuilder().setPrettyPrinting().create().toJson(result));throw failure;
        }
    }
}
