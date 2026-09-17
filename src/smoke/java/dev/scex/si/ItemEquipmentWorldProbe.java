// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.mojang.authlib.GameProfile;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Container;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.MachineItemHandler;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.SlotType;
import com.singularity_iteration.mio_icif.api.item.AbstractBattery;
import com.singularity_iteration.mio_icif.api.item.AbstractElectricArmor;
import com.singularity_iteration.mio_icif.api.item.AbstractElectricTool;
import com.singularity_iteration.mio_icif.api.item.IBatteryItem;
import dev.scex.si.energy.FeLedger;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Test-only public server entrypoints; the fake players do not claim connected-client coverage. */
public final class ItemEquipmentWorldProbe {
    private static final BlockPos SI_MACHINE=new BlockPos(1200,80,4), FE_MACHINE=new BlockPos(1206,80,4),
        BUDGET_BOX=new BlockPos(1212,80,4), FE_BOX=new BlockPos(1218,80,4), MIXED_BOX=new BlockPos(1224,80,4);
    private final List<String> groups=new ArrayList<>();
    private final List<FakePlayer> players=new ArrayList<>();
    private final List<IBatteryItem> helmets=new ArrayList<>();
    private final int[] generation={32,256,2048};
    private final long[] totals={8000,8000,8000};
    private int assertions, siSlot, feSlot;
    private BlockPos budgetReceiver;
    private ItemStack heldTool=ItemStack.EMPTY;

    public static final class TestBattery extends AbstractBattery {
        TestBattery(){super(new Item.Properties(),1000,0,20,1);}
    }
    public static final class TestTool extends AbstractElectricTool {
        TestTool(){super(new Item.Properties(),10000,0,1000,0,1);}
    }
    public static final class PartialArmor extends AbstractElectricArmor {
        PartialArmor(){super(ArmorMaterials.LEATHER,ArmorItem.Type.CHESTPLATE,new Item.Properties(),1000,0,100,0,1,"scex_si_smoke/test");}
        @Override public long addEnergy(ItemStack stack,long amount){return super.addEnergy(stack,Math.min(3,amount));}
    }
    private static ResourceLocation id(String name){return ResourceLocation.fromNamespaceAndPath("scex_si_smoke",name);}
    public static void registerItems(RegisterEvent event) {
        event.register(Registries.ITEM,id("battery63"),TestBattery::new);
        event.register(Registries.ITEM,id("tool63"),TestTool::new);
        event.register(Registries.ITEM,id("partial_armor63"),PartialArmor::new);
    }
    private static int storedFe(ItemStack stack){return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getInt("r63_fe");}
    private static void storedFe(ItemStack stack,int amount){var data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();data.putInt("r63_fe",amount);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));}
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(Capabilities.EnergyStorage.ITEM,(stack,unused)->new IEnergyStorage(){
            public int receiveEnergy(int amount,boolean simulate){int accepted=canReceive()?Math.max(0,Math.min(3,Math.min(amount,getMaxEnergyStored()-getEnergyStored()))):0;if(!simulate&&accepted>0)storedFe(stack,getEnergyStored()+accepted);return accepted;}
            public int extractEnergy(int amount,boolean simulate){int removed=canExtract()?Math.max(0,Math.min(3,Math.min(amount,getEnergyStored()))):0;if(!simulate&&removed>0)storedFe(stack,getEnergyStored()-removed);return removed;}
            public int getEnergyStored(){return storedFe(stack);}
            public int getMaxEnergyStored(){return stack.is(Items.NAUTILUS_SHELL)?4096:128;}
            public boolean canReceive(){return stack.is(Items.ECHO_SHARD);}
            public boolean canExtract(){return stack.is(Items.NAUTILUS_SHELL);}
        },Items.NAUTILUS_SHELL,Items.ECHO_SHARD);
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK,BlockEntityType.CHEST,(chest,side)->new IEnergyStorage(){
            public int receiveEnergy(int amount,boolean simulate){int accepted=chest.isRemoved()?0:Math.max(0,Math.min(3,Math.min(amount,4096-getEnergyStored())));if(!simulate&&accepted>0){chest.getPersistentData().putInt("r63_fe",getEnergyStored()+accepted);chest.setChanged();}return accepted;}
            public int extractEnergy(int amount,boolean simulate){return 0;}
            public int getEnergyStored(){return chest.getPersistentData().getInt("r63_fe");}
            public int getMaxEnergyStored(){return 4096;}
            public boolean canReceive(){return !chest.isRemoved();}
            public boolean canExtract(){return false;}
        });
    }
    private void check(boolean value,String label){assertions++;if(!value)throw new AssertionError("R63 "+label);}
    private void done(String group){check(!groups.contains(group),"unique group "+group);groups.add(group);System.out.println("SCEX_ITEM_WORLD_CASE_PASS "+group);}
    private Item item(String name){var item=BuiltInRegistries.ITEM.get(id(name));check(item!=Items.AIR,"fixture item registered "+name);return item;}
    private void place(ServerLevel world,BlockPos pos,String name){var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:"+name));check(block!=Blocks.AIR,"registered block "+name);world.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());world.setBlockAndUpdate(pos,block.defaultBlockState());}
    private mio_icif_Energy_Block energy(ServerLevel world,BlockPos pos){return (mio_icif_Energy_Block)world.getBlockEntity(pos);}
    private Container inventory(ServerLevel world,BlockPos pos){return (Container)world.getBlockEntity(pos);}
    private long eu(ItemStack stack){return stack.getItem() instanceof IBatteryItem item?item.getEnergy(stack):0;}
    private int fe(ServerLevel world,BlockPos pos){return FeLedger.fe(energy(world,pos).getEnergyStorageInternal().scexExactAmount());}
    private int receiverFe(ServerLevel world){return ((ChestBlockEntity)world.getBlockEntity(budgetReceiver)).getPersistentData().getInt("r63_fe");}
    private ItemStack sourceFe(){var stack=new ItemStack(Items.NAUTILUS_SHELL);storedFe(stack,257);return stack;}
    private int batterySlot(ServerLevel world,BlockPos pos){var handler=(MachineItemHandler)((com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_producer)world.getBlockEntity(pos)).getItemHandler();int slot=handler.getLayout().getStart(SlotType.BATTERY);check(slot>=0,"actual battery slot");return slot;}
    private FakePlayer player(ServerLevel world,int index){var p=new FakePlayer(world,new GameProfile(new UUID(6300,index+1),"SI_R63_"+index));p.setPos(1200+index*4,90,16);return p;}
    private void setup(ServerLevel world) {
        place(world,SI_MACHINE,"producer/block_furnace_elc");place(world,FE_MACHINE,"producer/block_furnace_elc");
        for(var pos:List.of(BUDGET_BOX,FE_BOX,MIXED_BOX))place(world,pos,"wiring/block_bat_box");
        siSlot=batterySlot(world,SI_MACHINE);feSlot=batterySlot(world,FE_MACHINE);
        var battery=new ItemStack(item("battery63"));((IBatteryItem)battery.getItem()).setEnergy(battery,200);inventory(world,SI_MACHINE).setItem(siSlot,battery);
        inventory(world,FE_MACHINE).setItem(feSlot,sourceFe());
        energy(world,BUDGET_BOX).getEnergyStorageInternal().setEnergy(1000);inventory(world,BUDGET_BOX).setItem(0,new ItemStack(item("tool63")));
        var box=(mio_icif_Energy_Container)energy(world,BUDGET_BOX);for(var side:Direction.values())if(box.canProvidePowerFromSide(side))budgetReceiver=BUDGET_BOX.relative(side);
        check(budgetReceiver!=null,"actual storage front");world.setBlockAndUpdate(budgetReceiver,Blocks.CHEST.defaultBlockState());
        inventory(world,FE_BOX).setItem(1,sourceFe());inventory(world,FE_BOX).setItem(0,new ItemStack(Items.ECHO_SHARD));
        energy(world,MIXED_BOX).getEnergyStorageInternal().setEnergy(100);inventory(world,MIXED_BOX).setItem(1,sourceFe());inventory(world,MIXED_BOX).setItem(0,new ItemStack(item("partial_armor63")));
        var menuPlayer=player(world,10);
        var machineHandler=((com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_producer)energy(world,FE_MACHINE)).getItemHandler();
        var machineMenu=((MenuProvider)energy(world,FE_MACHINE)).createMenu(1,menuPlayer.getInventory(),menuPlayer);
        check(machineMenu!=null&&machineMenu.slots.stream().anyMatch(s->s instanceof SlotItemHandler h&&h.getItemHandler()==machineHandler&&s.getContainerSlot()==feSlot&&s.mayPlace(sourceFe())),"real machine menu accepts a registered FE source");
        var boxMenu=((MenuProvider)energy(world,FE_BOX)).createMenu(2,menuPlayer.getInventory(),menuPlayer);
        check(boxMenu!=null&&boxMenu.slots.stream().anyMatch(s->s.container==inventory(world,FE_BOX)&&s.getContainerSlot()==1&&s.mayPlace(sourceFe())),"real storage menu accepts a registered FE source");
        done("server-menu-admission");
        List<Class<?>> types=List.of(com.singularity_iteration.mio_icif.Items.Armor.mio_icif_advanced_solar_helmet.class,com.singularity_iteration.mio_icif.Items.Armor.mio_icif_hybrid_solar_helmet.class,com.singularity_iteration.mio_icif.Items.Armor.mio_icif_ultimate_solar_helmet.class);
        for(int i=0;i<types.size();i++){
            Class<?> type=types.get(i);var matches=BuiltInRegistries.ITEM.stream().filter(v->v.getClass()==type).toList();check(matches.size()==1,"one actual registered helmet "+type.getSimpleName());
            Item helmet=matches.getFirst();IBatteryItem power=(IBatteryItem)helmet;var p=player(world,i);players.add(p);helmets.add(power);
            var worn=new ItemStack(helmet);power.setEnergy(worn,4000);p.setItemSlot(EquipmentSlot.HEAD,worn);p.getInventory().setItem(0,worn.copy());p.setItemSlot(EquipmentSlot.CHEST,new ItemStack(item("partial_armor63")));
        }
    }
    public Map<String,Object> inspect(ServerLevel world,int tick) throws Exception {
        if(tick==30)setup(world);if(tick<31)return null;
        check(eu(inventory(world,SI_MACHINE).getItem(siSlot))*4+fe(world,SI_MACHINE)==800,"native machine input conservation");
        check(storedFe(inventory(world,FE_MACHINE).getItem(feSlot))+fe(world,FE_MACHINE)==257,"FE machine input conservation");
        check(storedFe(inventory(world,FE_BOX).getItem(1))+storedFe(inventory(world,FE_BOX).getItem(0))+fe(world,FE_BOX)==257,"FE box two-slot conservation");
        check(storedFe(inventory(world,MIXED_BOX).getItem(1))+eu(inventory(world,MIXED_BOX).getItem(0))*4+fe(world,MIXED_BOX)==657,"mixed source and receiver conservation");
        check(fe(world,BUDGET_BOX)+eu(inventory(world,BUDGET_BOX).getItem(0))*4+eu(heldTool)*4+receiverFe(world)==4000,"shared box output conservation");
        if(tick<=50)check(receiverFe(world)==0,"SI charging consumes the shared output budget before FE push");
        if(tick==50){check(eu(inventory(world,BUDGET_BOX).getItem(0))>0,"SI target charged on real machine ticks");heldTool=inventory(world,BUDGET_BOX).getItem(0).copy();inventory(world,BUDGET_BOX).setItem(0,ItemStack.EMPTY);}
        if(tick==75){check(receiverFe(world)>0,"FE push resumes after SI charging slot removed");done("shared-storage-output-budget");}
        if(tick<=40)for(int i=0;i<players.size();i++){
            var p=players.get(i);var power=helmets.get(i);p.getInventory().tick();totals[i]+=generation[i];
            long actual=power.getEnergy(p.getItemBySlot(EquipmentSlot.HEAD))+power.getEnergy(p.getInventory().getItem(0))+eu(p.getItemBySlot(EquipmentSlot.CHEST));
            check(actual==totals[i],"worn helmet generation only once; carried copy inactive "+i+" actual="+actual+" expected="+totals[i]);
            check(eu(p.getItemBySlot(EquipmentSlot.CHEST))==3L*(tick-30),"partial armor paid exactly on actual Inventory.tick "+i);
            p.getInventory().tick();long duplicate=power.getEnergy(p.getItemBySlot(EquipmentSlot.HEAD))+power.getEnergy(p.getInventory().getItem(0))+eu(p.getItemBySlot(EquipmentSlot.CHEST));
            check(duplicate==actual,"duplicate inventory call cannot generate or charge again "+i);
            if(tick==40)done("helmet-inventory-entry-"+i);
        }
        if(tick==45){for(int i=0;i<players.size();i++){var saved=players.get(i).getInventory().save(new net.minecraft.nbt.ListTag());var restored=player(world,20+i);restored.getInventory().load(saved);
            check(eu(restored.getItemBySlot(EquipmentSlot.HEAD))+eu(restored.getInventory().getItem(0))+eu(restored.getItemBySlot(EquipmentSlot.CHEST))==totals[i],"ordinary player inventory save/load retains helmet and armor balances");}done("inventory-serialization");}
        if(tick==150){
            check(eu(inventory(world,SI_MACHINE).getItem(siSlot))==0&&fe(world,SI_MACHINE)==800,"SI battery drained through actual machine ticker");done("native-machine-item-input");
            check(storedFe(inventory(world,FE_MACHINE).getItem(feSlot))==0&&fe(world,FE_MACHINE)==257,"registered FE battery drained through actual machine ticker");done("fe-machine-item-input");
            check(storedFe(inventory(world,FE_BOX).getItem(1))==0&&storedFe(inventory(world,FE_BOX).getItem(0))==128&&fe(world,FE_BOX)==129,"FE storage preserves final odd FE and both slot balances");done("fe-storage-both-directions");
            check(storedFe(inventory(world,MIXED_BOX).getItem(1))==0&&eu(inventory(world,MIXED_BOX).getItem(0))==164&&fe(world,MIXED_BOX)==1,"mixed FE source and SI receiver preserve a final quarter EU");done("mixed-storage-items");
            check(groups.size()==10,"all ten grouped cases complete");
            Files.writeString(Path.of("item-equipment-result.json"),new com.google.gson.Gson().toJson(Map.of("passed",true,"groups",groups,"assertions",assertions,"player_scope","explicit FakePlayer Inventory.tick; connected client and multiplayer NOT_RUN")));
        }
        return Map.of("assertions",assertions,"groups",groups,"si_machine_fe",fe(world,SI_MACHINE),"fe_machine_fe",fe(world,FE_MACHINE),"fe_box_fe",fe(world,FE_BOX),"mixed_box_fe",fe(world,MIXED_BOX));
    }
}
