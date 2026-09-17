// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.mojang.authlib.GameProfile;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Container;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_pattern_storage;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_scanner_elc.ScanResult;
import com.singularity_iteration.mio_icif.Items.Resource.mio_icif_memory;
import com.singularity_iteration.mio_icif.Menu.Producer.PatternStorageMenu;
import dev.scex.si.energy.FeLedger;
import dev.scex.si.processing.StoredPattern;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Actual registered world objects; caller-supplied patterns do not invoke the scanner/UU solver. */
public final class PatternStorageWorldProbe {
    private static final BlockPos BANK=new BlockPos(1600,80,4), SOURCE=BANK.east(6), NATIVE=SOURCE.east(2), DROP=BANK.east(14), PLACED=BANK.east(20);
    private final List<String> groups=new ArrayList<>();
    private int assertions,nativeTicks;
    private FakePlayer player;
    private mio_icif_memory memory;
    private ItemStack pattern,exported=ItemStack.EMPTY;
    private PatternStorageMenu menu;
    private long lastNative;
    private void check(boolean ok,String label){assertions++;if(!ok)throw new AssertionError("R69 "+label);}
    private void done(String group){check(!groups.contains(group),"unique group "+group);groups.add(group);System.out.println("SCEX_PATTERN_WORLD_CASE_PASS "+group);}
    private mio_icif_pattern_storage bank(ServerLevel world,BlockPos pos){return (mio_icif_pattern_storage)world.getBlockEntity(pos);}
    private long eu(ServerLevel world,BlockPos pos){return bank(world,pos).getEnergyStorageInternal().getAmount();}
    private int fe(ServerLevel world,BlockPos pos){return FeLedger.fe(bank(world,pos).getEnergyStorageInternal().scexExactAmount());}
    private void place(ServerLevel world,BlockPos pos,String id){
        var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:"+id));check(block!=Blocks.AIR,"registered "+id);
        var state=block.defaultBlockState();
        if(state.hasProperty(BlockStateProperties.FACING))state=state.setValue(BlockStateProperties.FACING,Direction.EAST);
        else if(state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))state=state.setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST);
        world.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());world.setBlockAndUpdate(pos,state);
    }
    private ItemStack slotRoundTrip(ServerLevel world,ItemStack item,int slot){
        var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),world.registryAccess(),net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        try{
            var packet=new ClientboundContainerSetSlotPacket(7,3,slot,item);
            ClientboundContainerSetSlotPacket.STREAM_CODEC.encode(buf,packet);
            var decoded=ClientboundContainerSetSlotPacket.STREAM_CODEC.decode(buf);
            check(decoded.getContainerId()==7&&decoded.getSlot()==slot&&decoded.getStateId()==3&&ItemStack.matches(item,decoded.getItem()),"actual slot codec retains components slot="+slot);
            check(buf.readableBytes()==0,"slot codec consumes complete payload");return decoded.getItem();
        }finally{buf.release();}
    }
    private void setup(ServerLevel world){
        place(world,BANK,"producer/block_pattern_storage");place(world,NATIVE,"producer/block_pattern_storage");
        place(world,SOURCE,"wiring/block_bat_box");place(world,SOURCE.east(),"wiring/cable/block_glass_cable");
        ((mio_icif_Energy_Container)world.getBlockEntity(SOURCE)).getEnergyStorageInternal().setEnergy(30000);
        var matches=BuiltInRegistries.ITEM.stream().filter(i->i instanceof mio_icif_memory).toList();check(matches.size()==1,"actual registered memory item");memory=(mio_icif_memory)matches.getFirst();
        player=new FakePlayer(world,new GameProfile(new UUID(6900,1),"SI_R69"));player.setPos(BANK.getX()+0.5,80,6.5);
        pattern=new ItemStack(Items.DIAMOND_PICKAXE);pattern.set(DataComponents.CUSTOM_NAME,Component.literal("R69 enchanted pattern"));
        pattern.enchant(world.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY),3);
        pattern.setDamageValue(7);
        var tile=bank(world,BANK);var cap=world.getCapability(Capabilities.EnergyStorage.BLOCK,BANK,Direction.NORTH);
        check(cap!=null&&cap.canReceive()&&!cap.canExtract(),"registered pattern FE input only");
        check(cap.receiveEnergy(512,true)==512&&fe(world,BANK)==0,"simulation changes no balance");
        check(cap.receiveEnergy(1,false)==1&&fe(world,BANK)==1,"one FE retains quarter EU");
        var other=world.getCapability(Capabilities.EnergyStorage.BLOCK,BANK,Direction.SOUTH);
        check(other!=null&&other.receiveEnergy(1000,false)==511,"faces share 512 FE tick input budget");
        check(cap.receiveEnergy(1,false)==0&&fe(world,BANK)==512,"shared allowance exhausted exactly");
        check(cap.extractEnergy(512,false)==0,"input machine cannot be drained");done("pattern-fe-input");
        tile.getEnergyStorageInternal().setEnergy(10000);
        check(tile.storePattern(new ScanResult(pattern,0.25,5000000003L)),"paid pattern store on active real entity");
        check(eu(world,BANK)==9900,"store charges exactly 100 EU");
        menu=(PatternStorageMenu)tile.createMenu(7,player.getInventory(),player);player.containerMenu=menu;
        check(menu.stillValid(player)&&menu.slots.size()==38,"registered server menu and inventory layout");
    }
    private void menus(ServerLevel world){
        var tile=bank(world,BANK);tile.setItem(0,new ItemStack(memory));
        check(menu.clickMenuButton(player,2),"server export button works");
        exported=tile.getItem(0).copy();check(ItemStack.matches(pattern,memory.getStoredItemStack(exported)),"export retains name, damage and dynamic enchantment");
        check(memory.getEnergyCost(exported)==5000000003L&&memory.getUuMatterCost(exported)==0.25,"export costs intact");
        check(eu(world,BANK)==9800,"export paid exactly");
        check(!menu.clickMenuButton(player,2)&&eu(world,BANK)==9800,"occupied export cannot repeat charge");
        check(menu.clickMenuButton(player,3)&&eu(world,BANK)==9800,"same-pattern import idempotent");
        var other=new ItemStack(memory);check(memory.tryStoreData(other,new ItemStack(Items.IRON_INGOT),0.75,17),"second pattern prepared");tile.setItem(0,other);
        check(menu.clickMenuButton(player,3)&&tile.getStoredCount()==2&&eu(world,BANK)==9700,"new import costs once");
        check(menu.clickMenuButton(player,1)&&tile.getCurrentIndex()==1,"next button");
        check(menu.clickMenuButton(player,0)&&tile.getCurrentIndex()==0,"previous button");
        check(!menu.clickMenuButton(player,99)&&eu(world,BANK)==9700,"unknown button no effect");
        tile.setItem(0,exported);done("paid-server-menu-transactions");
        var decoded=slotRoundTrip(world,exported,0);check(ItemStack.matches(pattern,memory.getStoredItemStack(decoded)),"nested pattern item codec");
        var saved=ItemStack.parseOptional(world.registryAccess(),(CompoundTag)exported.save(world.registryAccess()));
        check(ItemStack.matches(exported,saved),"registered item NBT round trip");
        menu.broadcastChanges();var preview=slotRoundTrip(world,menu.getCurrentPattern(),1);
        var unbound=new PatternStorageMenu(7,player.getInventory());unbound.setItem(1,3,preview);
        for(int i=0;i<tile.getContainerData().getCount();i++){
            var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),world.registryAccess());
            try{ClientboundContainerSetDataPacket.STREAM_CODEC.encode(buf,new ClientboundContainerSetDataPacket(7,i,tile.getContainerData().get(i)));
                var p=ClientboundContainerSetDataPacket.STREAM_CODEC.decode(buf);unbound.setData(p.getId(),p.getValue());}finally{buf.release();}
        }
        check(unbound.getBlockEntity()==null&&ItemStack.matches(pattern,unbound.getCurrentPattern()),"unbound menu obtains complete preview through slot packet path");
        check(unbound.getCurrentEuCost()==5000000003L&&unbound.getCurrentUuCost()==0.25&&unbound.getEnergy()==9700,"all property bits reconstruct in unbound menu");
        done("real-registry-slot-property-codecs");
        long before=eu(world,BANK);player.containerMenu=player.inventoryMenu;
        check(!menu.clickMenuButton(player,1)&&tile.getCurrentIndex()==0,"stale menu rejected");player.containerMenu=menu;
        player.setPos(BANK.getX()+20,80,4);check(!menu.stillValid(player)&&!menu.clickMenuButton(player,1),"distant menu rejected");
        player.setPos(BANK.getX()+0.5,80,6.5);check(menu.stillValid(player)&&eu(world,BANK)==before,"valid binding restored without charge");
        done("menu-binding-and-range");
        for(var click:List.of(ClickType.PICKUP,ClickType.QUICK_MOVE,ClickType.SWAP,ClickType.THROW)){
            menu.clicked(1,0,click,player);check(menu.getCarried().isEmpty()&&player.getInventory().isEmpty()&&ItemStack.matches(pattern,menu.getCurrentPattern()),"preview denial "+click);
        }
        check(!menu.getSlot(1).mayPlace(new ItemStack(Items.STONE))&&!menu.getSlot(1).mayPickup(player),"preview refuses placement and pickup");done("preview-click-denial");
    }
    private void savesAndDrops(ServerLevel world)throws Exception{
        var original=bank(world,BANK);var tag=original.saveWithFullMetadata(world.registryAccess());
        place(world,DROP,"producer/block_pattern_storage");var copy=bank(world,DROP);copy.loadWithComponents(tag,world.registryAccess());
        check(copy.getStoredCount()==2&&ItemStack.matches(pattern,copy.getCurrentPattern())&&ItemStack.matches(exported,copy.getItem(0))&&eu(world,DROP)==9700,"real BE save/load retains patterns inventory energy");
        var legacy=StoredPattern.load(TagParser.parseTag("{energy_cost:42L,item:{components:{\"minecraft:custom_name\":'\"R68 saved-format input\"'},count:1,id:\"minecraft:stone\"},uu_matter_cost_buckets:0.25d}"),world.registryAccess());
        check(legacy!=null&&legacy.item().is(Items.STONE)&&legacy.buckets()==0.25&&legacy.energy()==42,"frozen public R5 format loads under real registries");done("normal-and-legacy-nbt");
        var wrench=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:item_tool_wrench")));
        check(!wrench.isEmpty()&&wrench.is(Tags.Items.TOOLS_WRENCH),"real wrench tag");
        var state=world.getBlockState(DROP);
        var params=new LootParams.Builder(world).withParameter(LootContextParams.ORIGIN,Vec3.atCenterOf(DROP)).withParameter(LootContextParams.TOOL,wrench).withParameter(LootContextParams.BLOCK_ENTITY,copy).withOptionalParameter(LootContextParams.THIS_ENTITY,player);
        var drops=((com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_pattern_storage)state.getBlock()).getDrops(state,params);
        check(drops.size()==1&&drops.getFirst().is(state.getBlock().asItem()),"wrench loot retains actual machine item");
        var machine=drops.getFirst();var data=machine.get(DataComponents.BLOCK_ENTITY_DATA);
        check(data!=null&&!data.copyTag().contains("inventory")&&data.copyTag().getList("patterns",10).size()==2,"machine loot owns pattern book but not separately dropped memory");
        player.setPos(DROP.getX()+0.5,80,DROP.getZ()+2.5);player.setItemInHand(InteractionHand.MAIN_HAND,wrench);
        check(world.destroyBlock(DROP,true,player),"actual server block destruction succeeds");
        var items=world.getEntitiesOfClass(ItemEntity.class,new AABB(DROP).inflate(1));
        long matchingMemory=items.stream().filter(e->ItemStack.matches(exported,e.getItem())).count();
        check(matchingMemory==1,"actual removal emits memory exactly once");
        check(!menu.clickMenuButton(player,99),"unknown existing menu still refused");
        world.setBlockAndUpdate(PLACED.below(),Blocks.STONE.defaultBlockState());player.setPos(PLACED.getX()+0.5,80,6.5);player.setItemInHand(InteractionHand.MAIN_HAND,machine);
        var context=new BlockPlaceContext(player,InteractionHand.MAIN_HAND,machine,new BlockHitResult(Vec3.atCenterOf(PLACED.below()).add(0,0.5,0),Direction.UP,PLACED.below(),false));
        check(((BlockItem)machine.getItem()).place(context).consumesAction(),"actual BlockItem placement succeeds");
        var placed=bank(world,PLACED);
        check(placed.getStoredCount()==2&&ItemStack.matches(pattern,placed.getCurrentPattern())&&eu(world,PLACED)==9700&&placed.getItem(0).isEmpty(),"placement restores patterns energy without duplicate crystal");
        var memoryEntity=items.stream().filter(e->ItemStack.matches(exported,e.getItem())).findFirst().orElseThrow();
        placed.setItem(0,memoryEntity.getItem().copy());memoryEntity.discard();
        check(ItemStack.matches(exported,placed.getItem(0)),"single recovered memory returned for ordinary disk-save evidence");
        done("loot-removal-blockitem-placement");
    }
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception{
        if(tick==30)setup(world);if(tick<31)return null;
        if(tick==40)menus(world);
        if(tick>=50&&tick<70){long now=eu(world,NATIVE);check(now-lastNative>0,"native pattern receiver advances on real grid");lastNative=now;nativeTicks++;}
        if(tick==70){check(nativeTicks==20&&eu(world,NATIVE)>0,"twenty native input world ticks observed");done("pattern-native-input");}
        if(tick==80)savesAndDrops(world);
        if(tick==130){
            check(groups.size()==8,"eight grouped cases actually complete");
            Files.writeString(Path.of("pattern-storage-result.json"),new com.google.gson.Gson().toJson(Map.of("passed",true,"groups",groups,"assertions",assertions,"native_ticks",nativeTicks,"scope","real registries and server world; FakePlayer menus, loot/removal and BlockItem placement; no connected client")));
            System.out.println("SCEX_PATTERN_WORLD groups=8 assertions="+assertions+" PASS");
        }
        return Map.of("groups",groups,"assertions",assertions,"bank_fe",fe(world,BANK),"native_eu",eu(world,NATIVE),"native_ticks",nativeTicks,"placed",world.getBlockEntity(PLACED) instanceof mio_icif_pattern_storage);
    }
}
