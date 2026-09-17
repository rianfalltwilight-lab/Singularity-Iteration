// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Container;
import com.singularity_iteration.mio_icif.Blocks.entity.pipe.mio_icif_pipe_item;
import dev.scex.si.energy.FeLedger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** Test mod only. Exercises actual registered SI entities in a ticking dedicated world. */
public final class InteropWorldProbe {
    private static final BlockPos FURNACE=new BlockPos(400,80,4), BOX=new BlockPos(400,80,10);
    private static final BlockPos SOURCE=new BlockPos(409,80,24), TARGET=new BlockPos(419,80,24), BRANCH=new BlockPos(414,80,28);
    private static final BlockPos LONG_START=new BlockPos(384,80,48), LONG_END=new BlockPos(984,80,48);
    private final List<BlockPos> pipes=new ArrayList<>(), pressure=new ArrayList<>();
    private final List<Long> baseline=new ArrayList<>(), load=new ArrayList<>();
    private final List<String> completed=new ArrayList<>();
    private int assertions, machineInput, recoveredReceiver, pauseReceiver;
    private Direction originalFront;
    private BlockPos receiver;
    private IEnergyStorage originalPort;
    private boolean built, pressureBuilt;

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK,BlockEntityType.CHEST,(chest,side)->new IEnergyStorage() {
            public int receiveEnergy(int max,boolean simulate) {
                if(chest.isRemoved() || max<=0) return 0;
                int accepted=Math.min(3,Math.min(max,20000-getEnergyStored()));
                if(!simulate && accepted>0){chest.getPersistentData().putInt("r54_fe",getEnergyStored()+accepted);chest.setChanged();}
                return accepted;
            }
            public int extractEnergy(int max,boolean simulate){return 0;}
            public int getEnergyStored(){return chest.getPersistentData().getInt("r54_fe");}
            public int getMaxEnergyStored(){return 20000;}
            public boolean canExtract(){return false;}
            public boolean canReceive(){return !chest.isRemoved();}
        });
        event.registerItem(Capabilities.EnergyStorage.ITEM,(stack,context)->new IEnergyStorage() {
            public int receiveEnergy(int max,boolean simulate) {
                int accepted=Math.max(0,Math.min(max,256-getEnergyStored()));
                if(!simulate && accepted>0){var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
                    tag.putInt("r54_fe",getEnergyStored()+accepted);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));}
                return accepted;
            }
            public int extractEnergy(int max,boolean simulate){return 0;}
            public int getEnergyStored(){return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getInt("r54_fe");}
            public int getMaxEnergyStored(){return 256;}
            public boolean canExtract(){return false;}
            public boolean canReceive(){return true;}
        },Items.ECHO_SHARD);
    }
    private void check(boolean pass,String name){assertions++;if(!pass)throw new AssertionError("SI interop "+name);}
    private void done(String name){completed.add(name);System.out.println("SCEX_INTEROP_CASE_PASS "+name);}
    private Block block(String id){Block b=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:"+id));check(b!=Blocks.AIR,"registered "+id);return b;}
    private void place(ServerLevel world,BlockPos pos,Block block){world.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());world.setBlockAndUpdate(pos,block.defaultBlockState());}
    private ChestBlockEntity chest(ServerLevel world,BlockPos pos){return (ChestBlockEntity)world.getBlockEntity(pos);}
    private mio_icif_Energy_Container box(ServerLevel world){return (mio_icif_Energy_Container)world.getBlockEntity(BOX);}
    private int fe(ServerLevel world,BlockPos pos){return chest(world,pos).getPersistentData().getInt("r54_fe");}
    private int itemFe(ItemStack stack){var cap=stack.getCapability(Capabilities.EnergyStorage.ITEM);return cap==null?0:cap.getEnergyStored();}
    private int count(Container inventory,Item item){int n=0;for(int i=0;i<inventory.getContainerSize();i++)if(inventory.getItem(i).is(item))n+=inventory.getItem(i).getCount();return n;}
    private int inPipes(ServerLevel world,Item item){int n=0;for(var pos:pipes)if(world.getBlockEntity(pos) instanceof mio_icif_pipe_item p && p.getBufferItem().is(item))n+=p.getBufferItem().getCount();return n;}
    private void seed(ServerLevel world,BlockPos pos,ItemStack stack,Direction side){var handler=world.getCapability(Capabilities.ItemHandler.BLOCK,pos,side);check(handler!=null,"pipe item capability");check(handler.insertItem(0,stack,false).isEmpty(),"pipe seed accepted "+pos);}
    private void pipe(ServerLevel world,BlockPos pos,boolean input){place(world,pos,block(input?"pipe/block_pipe_item_input":"pipe/block_pipe_item"));pipes.add(pos);}
    private void conservation(ServerLevel world){int n=FeLedger.fe(box(world).getEnergyStorageInternal().scexExactAmount())+itemFe(box(world).getItem(0))+fe(world,receiver)+recoveredReceiver;check(n==4000,"storage conservation expected 4000 actual "+n);}
    private void setup(ServerLevel world) {
        place(world,FURNACE,block("producer/block_furnace_elc"));
        ((Container)world.getBlockEntity(FURNACE)).setItem(0,new ItemStack(Items.COBBLESTONE));
        place(world,BOX,block("wiring/block_bat_box"));
        for(Direction side:Direction.values())if(box(world).canProvidePowerFromSide(side))originalFront=side;
        check(originalFront!=null,"storage front");receiver=BOX.relative(originalFront);
        place(world,receiver,Blocks.CHEST);
        box(world).getEnergyStorageInternal().generateEnergyInternal(1000,false);
        box(world).setItem(0,new ItemStack(Items.ECHO_SHARD));
        place(world,SOURCE,Blocks.CHEST);chest(world,SOURCE).setItem(0,new ItemStack(Items.DIAMOND,4));
        place(world,TARGET,Blocks.CHEST);for(int i=0;i<27;i++)chest(world,TARGET).setItem(i,new ItemStack(Items.COBBLESTONE,64));
        for(int x=410;x<=418;x++)pipe(world,new BlockPos(x,80,24),x==410);
        for(int z=25;z<=28;z++)pipe(world,new BlockPos(414,80,z),false);
        pipe(world,new BlockPos(415,80,25),false);
        built=true;
    }
    private void setupLoad(ServerLevel world) {
        for(int x=384;x<984;x++)pipe(world,new BlockPos(x,80,48),false);
        place(world,LONG_END,Blocks.CHEST);
        for(int i=0;i<128;i++){BlockPos pos=new BlockPos(384+(i%16)*3,80,1+(i/16)*3);if(pos.distManhattan(FURNACE)<3||pos.distManhattan(BOX)<3)pos=pos.above(5);
            pipe(world,pos,false);pressure.add(pos);}
        pressureBuilt=true;
    }
    public Map<String,Object> inspect(ServerLevel world,int tick) {
        if(tick==30)setup(world);
        if(!built)return null;
        if(tick>=31 && tick<=180) {
            var cap=world.getCapability(Capabilities.EnergyStorage.BLOCK,FURNACE,Direction.WEST);
            check(cap!=null && cap.canReceive() && !cap.canExtract(),"machine real FE input capability");
            int offered=tick==31?7:32;
            int before=cap.getEnergyStored();int quote=cap.receiveEnergy(offered,true);check(cap.getEnergyStored()==before,"machine FE simulation unchanged");
            int got=cap.receiveEnergy(offered,false);check(got==quote,"machine FE quote matches execution");machineInput+=got;
        }
        if(tick==35) {
            for(Direction side:Direction.values()) {
                var cap=world.getCapability(Capabilities.EnergyStorage.BLOCK,BOX,side);check(cap!=null,"storage face capability");
                check(cap.canReceive()==(side!=originalFront),"storage input face "+side);
                check(cap.canExtract()==(side==originalFront),"storage output face "+side);
                if(side==originalFront){check(cap.receiveEnergy(1,false)==0,"front rejects input");originalPort=cap;}
            }
            var nil=world.getCapability(Capabilities.EnergyStorage.BLOCK,BOX,(Direction)null);check(nil==null||(!nil.canReceive()&&!nil.canExtract()),"unsided no bypass");
            seed(world,BRANCH,new ItemStack(Items.EMERALD,3),Direction.NORTH);
            done("actual FE registration and storage face restrictions");
        }
        if(tick>=36&&tick<600)conservation(world);
        if(tick==80) {
            check(count(chest(world,TARGET),Items.DIAMOND)==0,"full chest unchanged");
            check(count(chest(world,SOURCE),Items.DIAMOND)+inPipes(world,Items.DIAMOND)==4,"full chest retains all diamonds");
            check(inPipes(world,Items.EMERALD)==3,"full chest retains branch buffer");
            check(itemFe(box(world).getItem(0))==256,"FE item actually charged");check(fe(world,receiver)>0,"storage active FE send");
            done("item FE charging, odd FE conservation and full chest retention");
        }
        if(tick==100){chest(world,TARGET).clearContent();chest(world,TARGET).setChanged();}
        if(tick==190) {
            var inv=(Container)world.getBlockEntity(FURNACE);check(count(inv,Items.STONE)==1,"real electric furnace recipe completes from FE only");
            check(machineInput>=1200,"furnace received cooking energy");done("FE-only electric furnace cooking");
            box(world).setRedstoneMode((byte)5);world.setBlockAndUpdate(BOX.above(),Blocks.REDSTONE_BLOCK.defaultBlockState());
        }
        if(tick==192)pauseReceiver=fe(world,receiver);
        if(tick==215) {
            check(fe(world,receiver)==pauseReceiver,"redstone blocks FE push");check(originalPort.extractEnergy(100,false)==0,"redstone blocks FE pull");
            world.setBlockAndUpdate(BOX.above(),Blocks.AIR.defaultBlockState());box(world).setRedstoneMode((byte)0);done("real redstone blocks both FE output paths");
        }
        if(tick==250) {
            check(fe(world,receiver)>pauseReceiver,"FE output resumes");
            recoveredReceiver+=fe(world,receiver);world.setBlockAndUpdate(receiver,Blocks.AIR.defaultBlockState());
            place(world,receiver,Blocks.CHEST);
        }
        if(tick==275){check(fe(world,receiver)>0,"replacement capability cache refreshed");done("neighbor FE capability invalidation");}
        if(tick==295) {
            check(count(chest(world,TARGET),Items.DIAMOND)==4 && count(chest(world,TARGET),Items.EMERALD)==3,"full-to-free and branch route recovery");
            check(inPipes(world,Items.DIAMOND)==0 && inPipes(world,Items.EMERALD)==0,"delivered buffers empty");
            done("full chest recovery and dead-end branch return");
        }
        if(tick==300) {
            recoveredReceiver+=fe(world,receiver);world.setBlockAndUpdate(receiver,Blocks.AIR.defaultBlockState());
            var state=world.getBlockState(BOX);
            var facing=state.hasProperty(BlockStateProperties.FACING)?BlockStateProperties.FACING:BlockStateProperties.HORIZONTAL_FACING;
            check(state.hasProperty(facing),"storage facing property");
            world.setBlockAndUpdate(BOX,state.setValue(facing,originalFront.getOpposite()));
            Direction front=null;for(Direction side:Direction.values())if(box(world).canProvidePowerFromSide(side))front=side;
            check(front==originalFront.getOpposite(),"storage rotation took effect");receiver=BOX.relative(front);place(world,receiver,Blocks.CHEST);
            check(originalPort.extractEnergy(1,false)==0,"old output capability follows rotation");
            world.setBlockAndUpdate(new BlockPos(416,80,24),Blocks.AIR.defaultBlockState());
            seed(world,BRANCH,new ItemStack(Items.GOLD_INGOT,4),Direction.NORTH);
        }
        if(tick==360) {
            check(fe(world,receiver)>0,"rotated storage pushes new face");done("rotation updates existing FE wrappers and output cache");
            check(inPipes(world,Items.GOLD_INGOT)==4 && count(chest(world,TARGET),Items.GOLD_INGOT)==0,"disconnected network retains items");
            place(world,new BlockPos(416,80,24),block("pipe/block_pipe_item"));
        }
        if(tick==430) {
            check(count(chest(world,TARGET),Items.GOLD_INGOT)==4 && inPipes(world,Items.GOLD_INGOT)==0,"reconnection resumes without duplication");
            done("broken pipe reconnect and exact item custody");
        }
        long nanos=world.getServer().getTickTimesNanos()[world.getServer().getTickCount()%100];
        if(tick>=450 && tick<550)baseline.add(nanos);
        if(tick==600)setupLoad(world);
        if(tick==630) {
            seed(world,LONG_START,new ItemStack(Items.NETHERITE_INGOT),Direction.WEST);
            for(var pos:pressure)seed(world,pos,new ItemStack(Items.LAPIS_LAZULI),null);
        }
        if(tick>=700 && tick<950)load.add(nanos);
        if(tick>=640 && tick<950 && tick%5==0) {
            var unrelated=(mio_icif_pipe_item)world.getBlockEntity(pressure.getLast());
            if(tick%10==0)unrelated.blockDirection(Direction.EAST);else unrelated.unblockDirection(Direction.EAST);
        }
        if(tick==960) {
            check(pressureBuilt,"load fixture built");check(count(chest(world,LONG_END),Items.NETHERITE_INGOT)==1,"600-pipe path completes under queue pressure");
            check(inPipes(world,Items.NETHERITE_INGOT)==0,"long transfer not duplicated");
            check(inPipes(world,Items.LAPIS_LAZULI)==128,"128 pending buffers retained");
            for(var pos:pipes)if(world.getBlockEntity(pos) instanceof mio_icif_pipe_item p)check(!p.hasUncertainTransfer(),"no uncertain normal transfer "+pos);
            done("600-pipe route progress with 128 waiting buffers and unrelated connection churn");
        }
        if(tick==980) {
            check(completed.size()==9,"all planned case groups executed actual "+completed.size());
            var row=new LinkedHashMap<String,Object>();row.put("passed",true);row.put("assertions",assertions);row.put("cases",completed);
            row.put("baseline_tick_ms",stats(baseline));row.put("loaded_tick_ms",stats(load));row.put("machine_input_fe",machineInput);
            row.put("performance_scope","single dedicated-world observation, 600 connected plus 128 waiting pipes; no whole-pack or comparative performance claim");
            return row;
        }
        if(tick%50==0)return Map.of("assertions",assertions,"completed",List.copyOf(completed),"machine_input_fe",machineInput);
        return null;
    }
    private Map<String,Object> stats(List<Long> values){check(!values.isEmpty(),"nonempty actual server tick samples");var sorted=values.stream().sorted().toList();
        return Map.of("samples",values.size(),"mean",values.stream().mapToLong(Long::longValue).average().orElseThrow()/1e6,
            "p95",sorted.get((int)Math.ceil(values.size()*.95)-1)/1e6,"max",sorted.getLast()/1e6);}
}
