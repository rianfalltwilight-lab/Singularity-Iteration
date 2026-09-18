// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.singularity_iteration.mio_icif.Blocks.entity.generator.mio_icif_nuclear_reactor_generator;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_block_entities;
import com.singularity_iteration.mio_icif.Blocks.generator.mio_icif_Block_Nuclear_Reactor_Generator;
import com.singularity_iteration.mio_icif.Items.DataComponent.FuelRodDurability;
import com.singularity_iteration.mio_icif.Items.Normal.mio_icif_data_components;
import com.singularity_iteration.mio_icif.Singularity_Iteration_Config;
import dev.scex.si.reactor.ReactorAccidentLatch;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/** R140 real-world checks for the bounded reactor accident executor. */
public final class ReactorAccidentEffectWorldProbe {
    private static final int CASES=6;
    private static final String MARKER="reactor-accident-effect-r140.json";
    private static final String CHECKPOINT="world/scex-reactor-accident-effect-r140.json";
    private static final String RESULT="reactor-accident-effect-r140-result.json";
    private final MinecraftServer server;
    private final boolean restart;
    private final int baseX;
    private int assertions,cancelEvents;
    private boolean finished,configChanged;
    private final boolean originalNuclear;
    private final List<String> groups=new ArrayList<>();
    private final Map<String,Object> metrics=new LinkedHashMap<>();
    private final Map<Integer,Float> observedRadii=new LinkedHashMap<>();
    private boolean deferredArmed;
    private int deferredArmedTick=-1;

    public ReactorAccidentEffectWorldProbe(MinecraftServer server)throws Exception {
        this.server=server;
        var marker=new Gson().fromJson(Files.readString(Path.of(MARKER)),Map.class);
        restart="restart".equals(marker.get("phase"));
        baseX=((Number)marker.get("base_x")).intValue();
        originalNuclear=Singularity_Iteration_Config.ENABLE_NUCLEAR_EXPLOSION.get();
        NeoForge.EVENT_BUS.addListener(this::onExplosionStart);
    }
    private BlockPos at(int i){return new BlockPos(baseX+i*128,82,408);}
    private void check(boolean value,String why){assertions++;if(!value)throw new AssertionError("R140 accident effect: "+why);}
    private void setConfig(boolean value){Singularity_Iteration_Config.ENABLE_NUCLEAR_EXPLOSION.set(value);configChanged=true;}
    private void restoreConfig(){if(configChanged){Singularity_Iteration_Config.ENABLE_NUCLEAR_EXPLOSION.set(originalNuclear);configChanged=false;}}
    private void onExplosionStart(ExplosionEvent.Start event){
        var explosion=event.getExplosion();var p=explosion.center();
        for(int i=0;i<CASES;i++){
            var wanted=at(i);
            if(Math.abs(p.x-(wanted.getX()+0.5))<0.01&&Math.abs(p.y-(wanted.getY()+0.5))<0.01
                    &&Math.abs(p.z-(wanted.getZ()+0.5))<0.01){
                observedRadii.put(i,explosion.radius());
                if(i==5){cancelEvents++;event.setCanceled(true);}
                return;
            }
        }
    }
    private void shell(ServerLevel level,BlockPos center,int radius){
        for(int x=-radius;x<=radius;x++)for(int y=-radius;y<=radius;y++)for(int z=-radius;z<=radius;z++)
            level.setBlock(center.offset(x,y,z),Blocks.STONE.defaultBlockState(),18);
    }
    private void place(ServerLevel level,BlockPos pos){
        var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:generator/block_nuclear_reactor_generator"));
        check(block!=Blocks.AIR,"registered reactor block");
        var state=block.defaultBlockState();var property=(DirectionProperty)block.getStateDefinition().getProperty("facing");
        if(property!=null)state=state.setValue(property,Direction.NORTH);
        check(level.setBlock(pos,state,18),"placed reactor");
        check(level.getBlockEntity(pos) instanceof mio_icif_nuclear_reactor_generator,"actual reactor block entity");
    }
    private ItemStack item(String suffix){
        var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:reactor/item_reactor_"+suffix));
        check(item!=net.minecraft.world.item.Items.AIR,"registered reactor item "+suffix);return new ItemStack(item);
    }
    private ItemStack fuel(){
        var out=item("uranium_quad");var data=out.get(mio_icif_data_components.FUEL_ROD_DURABILITY.get());
        check(data!=null,"quad fuel defaults");out.set(mio_icif_data_components.FUEL_ROD_DURABILITY.get(),new FuelRodDurability(10,data.maxUses(),0));return out;
    }
    private mio_icif_nuclear_reactor_generator core(ServerLevel level,int i){return (mio_icif_nuclear_reactor_generator)level.getBlockEntity(at(i));}
    private void seed(ServerLevel level,int i,int fuelStacks,boolean containment,boolean overheated){
        var pos=at(i);shell(level,pos,i==0?2:i==4?5:10);place(level,pos);var machine=core(level,i);
        for(int n=0;n<fuelStacks;n++)machine.setItem((n/3)*9+n%3,fuel());
        if(containment)machine.setItem((fuelStacks/3)*9+fuelStacks%3,item("explosive_plate"));
        long capacity=containment?10500:10000;
        var tag=machine.saveWithoutMetadata(level.registryAccess());tag.putLong("HeatStored",overheated?capacity:0);tag.putLong("MaxHeatStored",capacity);
        tag.putInt("ReactorCycleTicks",0);machine.loadAdditional(tag,level.registryAccess());
    }
    private void overheat(ServerLevel level,mio_icif_nuclear_reactor_generator machine){
        var tag=machine.saveWithoutMetadata(level.registryAccess());long capacity=tag.getLong("MaxHeatStored");
        check(capacity>0,"deferred reactor capacity");tag.putLong("HeatStored",capacity);tag.putInt("ReactorCycleTicks",0);
        machine.loadAdditional(tag,level.registryAccess());
    }
    private boolean missingWindow(ServerLevel level,BlockPos center,int radius){
        int minX=(center.getX()-radius)>>4,maxX=(center.getX()+radius)>>4;
        int minZ=(center.getZ()-radius)>>4,maxZ=(center.getZ()+radius)>>4;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)if(level.getChunkSource().getChunkNow(x,z)==null)return true;
        return false;
    }
    private int stone(ServerLevel level,BlockPos center,int radius){
        int result=0;for(int x=-radius;x<=radius;x++)for(int y=-radius;y<=radius;y++)for(int z=-radius;z<=radius;z++)
            if(level.getBlockState(center.offset(x,y,z)).is(Blocks.STONE))result++;return result;
    }
    private boolean reactor(ServerLevel level,int i){return level.getBlockState(at(i)).getBlock() instanceof mio_icif_Block_Nuclear_Reactor_Generator;}
    private void latchContract(){
        check(ReactorAccidentLatch.decide(10,10,1,true,0,0).terrainPower()==4.0f,"empty strength is four");
        check(ReactorAccidentLatch.decide(10,10,1,true,4,0).terrainPower()==8.0f,"quad strength adds four");
        check(ReactorAccidentLatch.decide(10,10,1,true,4,1).terrainPower()==7.0f,"containment subtracts one");
        check(ReactorAccidentLatch.decide(10,10,1,true,216,0).terrainPower()==32.0f,"strength clamps at thirty two");
        check(ReactorAccidentLatch.decide(10,10,1,false,4,0).effect()==ReactorAccidentLatch.Effect.LOCAL_MACHINE,
            "disabled nuclear policy stays local");
        groups.add("bounded-latch-strength-contract");
    }
    public Map<String,Object> inspect(ServerLevel level,int tick)throws Exception {
        if(finished)return null;
        try{return inner(level,tick);}catch(Exception|Error failure){restoreConfig();throw failure;}
    }
    private Map<String,Object> inner(ServerLevel level,int tick)throws Exception {
        if(restart){
            if(tick==20){
                for(int i=0;i<5;i++)check(!reactor(level,i),"removed reactor stays absent after cold restart "+i);
                check(reactor(level,5)&&core(level,5).getAccidentState().equals("CLOSED"),"canceled protected reactor stays closed");
                groups.add("cold-save-no-replay-or-resurrection");return finish(level,tick);
            }
            return null;
        }
        if(tick==20){latchContract();setConfig(false);seed(level,0,1,false,true);groups.add("local-policy-natural-trigger");}
        if(tick==40){
            check(!reactor(level,0),"local policy removes only owner");
            check(stone(level,at(0),2)==124,"local policy preserves all 124 surrounding stone blocks");
            setConfig(true);seed(level,1,0,false,true);seed(level,2,1,false,true);seed(level,3,1,true,true);
            seed(level,4,8,false,false);seed(level,5,1,false,true);
        }
        if(tick>=60&&tick<99&&!deferredArmed&&reactor(level,4)&&missingWindow(level,at(4),64)){
            overheat(level,core(level,4));deferredArmed=true;deferredArmedTick=tick;
        }
        if(tick==80){
            for(int i:new int[]{1,2,3})check(!reactor(level,i),"loaded nuclear owner removed "+i);
            check(reactor(level,4),"deferred unloaded-window owner remains present");
            check(reactor(level,5)&&core(level,5).getAccidentState().equals("CLOSED"),"canceled protection event closes without replay");
            check(cancelEvents==1,"exactly one protection start event");
            check(observedRadii.getOrDefault(1,-1.0f)==4.0f,"empty event radius is four");
            check(observedRadii.getOrDefault(2,-1.0f)==8.0f,"quad event radius is eight");
            check(observedRadii.getOrDefault(3,-1.0f)==7.0f,"contained event radius is seven");
            int emptyRemoved=9260-stone(level,at(1),10),quadRemoved=9260-stone(level,at(2),10),containedRemoved=9260-stone(level,at(3),10);
            check(emptyRemoved>0&&quadRemoved>0&&containedRemoved>0,"all uncanceled nuclear cases change terrain");
            check(stone(level,at(5),10)==9260,"canceled protection event preserves terrain");
            metrics.put("empty_removed",emptyRemoved);metrics.put("quad_removed",quadRemoved);metrics.put("contained_removed",containedRemoved);
            metrics.put("empty_radius",observedRadii.get(1));metrics.put("quad_radius",observedRadii.get(2));
            metrics.put("contained_radius",observedRadii.get(3));
            groups.add("loaded-strength-order-and-protection-event");
        }
        if(tick==99){
            check(deferredArmed&&deferredArmedTick>=60,"deferred case armed only after an observed missing chunk");
            check(reactor(level,4)&&core(level,4).getAccidentState().equals("PENDING"),"missing blast window remains durable pending");
            groups.add("unloaded-neighbours-do-not-force-load");
        }
        if(tick==130){check(!reactor(level,4),"pending owner executes after explicit neighbour load");groups.add("pending-save-state-dispatches-on-loaded-window");}
        if(tick==160){
            check(cancelEvents==1,"protected closed owner never repeats explosion event");
            restoreConfig();Files.writeString(Path.of(CHECKPOINT),new Gson().toJson(Map.of("base_x",baseX,"metrics",metrics)));
            return finish(level,tick);
        }
        if(tick>200)throw new AssertionError("R140 fixture deadline");return null;
    }
    private Map<String,Object> finish(ServerLevel level,int tick)throws Exception {
        restoreConfig();finished=true;
        var result=Map.<String,Object>of("passed",true,"phase",restart?"restart":"initial","assertions",assertions,
            "cases",CASES,"groups",groups,"metrics",metrics,"cancel_events",cancelEvents,
            "scope","Bounded vanilla-explosion executor, loaded-window gate, local policy and NeoForge Start cancellation; not IC2 internal algorithm parity.");
        Files.writeString(Path.of(RESULT),new Gson().toJson(result));return result;
    }
}
