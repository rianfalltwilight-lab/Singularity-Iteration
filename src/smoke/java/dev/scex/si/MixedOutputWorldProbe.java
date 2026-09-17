// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Container;
import com.singularity_iteration.mio_icif.api.item.IBatteryItem;
import dev.scex.si.energy.FeLedger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Test-only actual electrical scheduler and standard capability entrypoints. */
public final class MixedOutputWorldProbe {
    private static final BlockPos SOURCE=new BlockPos(1400,80,4), SINK=SOURCE.east(2);
    private final MinecraftServer server;
    private final int[] counts=new int[7];
    private int frame=-1,assertions,phase=-1,startSource,startSink,beforeFe;
    private String failure="";
    private boolean completed;
    private long extractedFe;
    public MixedOutputWorldProbe(MinecraftServer server) {
        this.server=server;
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,this::beforeLevel);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL,this::beforeNetwork);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,this::afterNetwork);
    }
    private void check(boolean condition,String label) {assertions++;if(!condition)throw new AssertionError("R67 frame="+frame+" phase="+phase+" "+label);}
    private mio_icif_Energy_Container box(ServerLevel world,BlockPos pos){return (mio_icif_Energy_Container)world.getBlockEntity(pos);}
    private int balance(ServerLevel world,BlockPos pos){return FeLedger.fe(box(world,pos).getEnergyStorageInternal().scexExactAmount());}
    private boolean relevant(LevelTickEvent event){return event.getLevel()==server.overworld()&&!completed&&failure.isEmpty()&&frame>=40&&frame<180;}
    private void place(ServerLevel world,BlockPos pos,String name) {
        var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:"+name));check(block!=Blocks.AIR,"block exists "+name);
        var state=block.defaultBlockState();
        if(state.hasProperty(BlockStateProperties.FACING))state=state.setValue(BlockStateProperties.FACING,Direction.EAST);
        else if(state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))state=state.setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST);
        world.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());world.setBlockAndUpdate(pos,state);
    }
    private void beforeLevel(LevelTickEvent.Pre event) {
        if(!relevant(event))return;
        try {
            var world=server.overworld();phase=(frame-40)/20;beforeFe=0;
            var source=box(world,SOURCE);var sink=box(world,SINK);
            sink.getEnergyStorageInternal().setEnergy(phase==2?sink.getEnergyStorageInternal().getCapacity()-10:0);
            ItemStack item=ItemStack.EMPTY;
            if(phase==3)item=new ItemStack(Items.ECHO_SHARD);
            if(phase==4) {
                var tool=BuiltInRegistries.ITEM.get(ResourceLocation.parse("scex_si_smoke:tool63"));
                check(tool instanceof IBatteryItem,"native tool fixture exists");item=new ItemStack(tool);
            }
            source.setItem(0,item);
            startSource=balance(world,SOURCE);startSink=balance(world,SINK);
        }catch(Throwable error){failure=error.toString();error.printStackTrace();}
    }
    private void beforeNetwork(LevelTickEvent.Post event) {
        if(!relevant(event))return;
        try {
            var world=server.overworld();var source=box(world,SOURCE);
            if(phase==1) {
                beforeFe=world.getCapability(Capabilities.EnergyStorage.BLOCK,SOURCE,Direction.EAST).extractEnergy(1,false);
                check(beforeFe==1,"FE-first accepted one FE");extractedFe+=beforeFe;
            }
            if(phase==5)source.getEnergyStorageInternal().setOutputEnabled(false);
            if(phase==6) {
                source.getEnergyStorageInternal().setOutputEnabled(true);
                source.scexFeBridge().loadUncertainOutput(1);
                check(source.getEnergyStorageInternal().isOutputEnabled(),"uncertain case is not blocked by the prior disabled flag");
            }
        }catch(Throwable error){failure=error.toString();error.printStackTrace();}
    }
    private void afterNetwork(LevelTickEvent.Post event) {
        if(!relevant(event))return;
        try {
            var world=server.overworld();var source=box(world,SOURCE);
            int nativeCredit=balance(world,SINK)-startSink;
            int itemFe=0;var item=source.getItem(0);
            if(phase==3)itemFe=item.getCapability(Capabilities.EnergyStorage.ITEM).getEnergyStored();
            if(phase==4)itemFe=Math.toIntExact(((IBatteryItem)item.getItem()).getEnergy(item)*4);
            var cap=world.getCapability(Capabilities.EnergyStorage.BLOCK,SOURCE,Direction.EAST);
            check(cap!=null,"source output capability exists");
            int afterFe=cap.extractEnergy(Integer.MAX_VALUE,false);extractedFe+=afterFe;
            int expectedNative=phase==0?128:phase==2?40:0;
            int expectedItem=phase==3?3:phase==4?128:0;
            int expectedAfter=switch(phase){case 1->127;case 2->88;case 3->125;default->0;};
            check(nativeCredit==expectedNative,"native credit expected="+expectedNative+" actual="+nativeCredit);
            check(itemFe==expectedItem,"item credit expected="+expectedItem+" actual="+itemFe);
            check(afterFe==expectedAfter,"remaining FE expected="+expectedAfter+" actual="+afterFe);
            int total=beforeFe+afterFe+nativeCredit+itemFe;
            check(total==(phase<5?128:0),"one shared allowance or stopped output");
            check(startSource-balance(world,SOURCE)==total,"source debit equals native, item and FE credits");
            counts[phase]++;
        }catch(Throwable error){failure=error.toString();error.printStackTrace();}
    }
    public Map<String,Object> inspect(ServerLevel world,int tick) throws Exception {
        frame=tick;
        if(!failure.isEmpty())throw new IllegalStateException(failure);
        if(tick==5) {
            place(world,SOURCE,"wiring/block_bat_box");place(world,SOURCE.east(),"wiring/cable/block_glass_cable");place(world,SINK,"wiring/block_mfsu");
            box(world,SOURCE).getEnergyStorageInternal().setEnergy(30000);
            check(box(world,SOURCE).getEnergyStorageInternal().getMaxExtract()==32,"actual BatBox rate");
        }
        if(tick==180) {
            for(int i=0;i<counts.length;i++)check(counts[i]==20,"phase "+i+" actually executed 20 world ticks");
            completed=true;
            Files.writeString(Path.of("mixed-output-result.json"),new com.google.gson.Gson().toJson(Map.of("passed",true,"revision","R67","groups",7,"ticks_per_group",counts,"assertions",assertions,"extracted_fe",extractedFe)));
            System.out.println("SCEX_MIXED_OUTPUT_WORLD groups=7 assertions="+assertions+" PASS");
        }
        if(tick<40)return null;
        return Map.of("assertions",assertions,"counts",counts,"source_fe",balance(world,SOURCE),"sink_fe",balance(world,SINK),"extracted_fe",extractedFe,"completed",completed);
    }
}
