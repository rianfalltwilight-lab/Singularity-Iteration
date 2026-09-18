// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.entity.generator.mio_icif_nuclear_reactor_generator;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Container;
import com.singularity_iteration.mio_icif.Blocks.entity.reactor.mio_icif_reactor_chamber;
import com.singularity_iteration.mio_icif.Blocks.generator.mio_icif_Block_Nuclear_Reactor_Generator;
import com.singularity_iteration.mio_icif.Blocks.mio_icif_blocks;
import com.singularity_iteration.mio_icif.Items.DataComponent.FuelRodDurability;
import com.singularity_iteration.mio_icif.Items.Normal.mio_icif_data_components;
import com.singularity_iteration.mio_icif.Singularity_Iteration_Config;
import com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage;
import com.singularity_iteration.mio_icif.energy.EnergyUnit.IEUEnergyStorage;
import dev.scex.energy.EnergyAmount;
import dev.scex.si.reactor.GuardedReactorHeat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.neoforged.neoforge.items.IItemHandler;

/** Evidence-only candidate: actual natural block ticks, balances/capabilities, and unmodified cold world. */
public final class ReactorAccidentWorldProbe {
    private static final String MARKER="reactor-accident-world-r137.json";
    private static final String CHECKPOINT="world/scex-reactor-accident-r137.json";
    private static final String RESULT="reactor-accident-world-r137-result.json";
    private static final String TRACE="reactor-accident-world-r137-observations.jsonl";
    private static final int CASES=10;
    private final int baseX;
    private final boolean restart,originalNuclear;
    private boolean configChanged,finished;
    private int assertions;
    private final List<String> groups=new ArrayList<>();
    private final Map<Integer,CompoundTag> frozen=new HashMap<>();
    private final CustomEUEnergyStorage[] cachedEu=new CustomEUEnergyStorage[CASES];
    private final GuardedReactorHeat[] cachedHeat=new GuardedReactorHeat[CASES];
    private final IItemHandler[] cachedItems=new IItemHandler[CASES];
    private final List<IEUEnergyStorage> cachedChambers=new ArrayList<>();
    private RemovalReactor removedOwner;
    private final List<Map<String,Object>> samples=new ArrayList<>();
    public ReactorAccidentWorldProbe(MinecraftServer server)throws Exception {
        var cfg=JsonParser.parseString(Files.readString(Path.of(MARKER))).getAsJsonObject();
        baseX=cfg.has("base_x")?cfg.get("base_x").getAsInt():32000;
        restart=cfg.get("phase").getAsString().equals("restart");
        originalNuclear=Singularity_Iteration_Config.ENABLE_NUCLEAR_EXPLOSION.get();
        if(restart){
            var w=server.overworld();
            var expected=JsonParser.parseString(Files.readString(Path.of(CHECKPOINT))).getAsJsonObject();
            check(expected.get("base_x").getAsInt()==baseX,"cold fixture coordinates unchanged");
            var rows=expected.getAsJsonArray("rows");check(rows.size()==CASES,"ten actual saved positions");
            for(int i=0;i<CASES;i++){
                var old=rows.get(i).getAsJsonObject();
                if(i==9){check(w.getBlockState(at(i)).is(Blocks.STONE),"cold replacement owner remains stone");continue;}
                var m=core(w,i);check(m!=null,"actual registered core loaded before first server tick");
                var wanted=TagParser.parseTag(old.get("core").getAsString());
                check(project(m.saveWithoutMetadata(w.registryAccess())).equals(wanted),"cold exact inventory/tanks/thermal/fraction/accident "+i);
                check(sinks(w,i).toString().equals(old.get("sinks").getAsString()),"cold exact native receiver balances "+i);
                cache(w,i);
                if(i!=0&&i!=2)frozen.put(i,wanted);
            }
            groups.add("new-jvm-before-first-natural-tick-exact-saved-state");
            sample(w,0,"cold-before-first-tick");
        }
    }
    private void check(boolean value,String why){assertions++;if(!value)throw new AssertionError("R137 accident lifecycle: "+why);}
    private BlockPos at(int i){return new BlockPos(baseX+i*16,80,100);}
    private mio_icif_nuclear_reactor_generator core(ServerLevel w,int i){return (mio_icif_nuclear_reactor_generator)w.getBlockEntity(at(i));}
    private ItemStack item(String suffix){return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:reactor/item_reactor_"+suffix)));}
    private ItemStack fuel(){var item=item("uranium_simple");var data=item.get(mio_icif_data_components.FUEL_ROD_DURABILITY.get());check(data!=null,"real fuel default components");item.set(mio_icif_data_components.FUEL_ROD_DURABILITY.get(),new FuelRodDurability(10,data.maxUses(),0));return item;}
    private void setConfig(boolean value){Singularity_Iteration_Config.ENABLE_NUCLEAR_EXPLOSION.set(value);configChanged=true;}
    private void restoreConfig(){if(configChanged){Singularity_Iteration_Config.ENABLE_NUCLEAR_EXPLOSION.set(originalNuclear);configChanged=false;}}
    private void place(ServerLevel w,BlockPos p,String id,Direction side){
        var b=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:"+id));
        check(b!=Blocks.AIR,"registered block "+id);
        var prop=(DirectionProperty)b.getStateDefinition().getProperty("facing");
        var state=b.defaultBlockState();if(prop!=null){check(prop.getPossibleValues().contains(side),"supported placement face");state=state.setValue(prop,side);}
        check(w.setBlock(p,state,18),"actual placed block "+id);
    }
    private void shell(ServerLevel w,int i){
        var p=at(i);
        for(int x=-2;x<=2;x++)for(int y=-2;y<=2;y++)for(int z=-2;z<=2;z++)
            w.setBlock(p.offset(x,y,z),Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.abs(z))==2
                ?mio_icif_blocks.REACTOR_VESSEL.get().defaultBlockState():Blocks.AIR.defaultBlockState(),18);
        place(w,p,"generator/block_nuclear_reactor_generator",Direction.NORTH);
        for(var side:Direction.values())w.setBlock(p.relative(side),mio_icif_blocks.REACTOR_CHAMBER.get().defaultBlockState(),18);
        w.setBlock(p.east(2),mio_icif_blocks.REACTOR_REDSTONE_PORT.get().defaultBlockState(),18);
        w.setBlock(p.south(2),mio_icif_blocks.REACTOR_FLUID_PORT.get().defaultBlockState(),18);
        w.setBlock(p.west(2),mio_icif_blocks.REACTOR_ACCESS_HATCH.get().defaultBlockState(),18);
    }
    private List<BlockPos> sinkPositions(int i){
        if(i==2||i==3||i==9)return List.of();
        if(i==4){var out=new ArrayList<BlockPos>();for(var side:Direction.values())out.add(at(i).relative(side,2));return out;}
        return List.of(at(i).below());
    }
    private List<List<Long>> sinks(ServerLevel w,int i){
        var result=new ArrayList<List<Long>>();
        for(var p:sinkPositions(i)){var m=(mio_icif_Energy_Container)w.getBlockEntity(p);var n=m.getEnergyStorageInternal().scexExactAmount();result.add(List.of(n.whole(),n.fraction()));}
        return result;
    }
    private void cache(ServerLevel w,int i){
        var m=core(w,i);cachedEu[i]=m.getEnergyStorageCapability(Direction.UP);cachedHeat[i]=m.getHeatStorageCapability(Direction.UP);cachedItems[i]=m.getItemHandlerCapability(Direction.UP);
        if(i==4)for(var side:Direction.values()){
            var chamber=(mio_icif_reactor_chamber)w.getBlockEntity(at(i).relative(side));
            check(chamber.getConnectedReactor()==m,"actual chamber owner before admission");
            var cap=chamber.getEnergyStorageCapability(Direction.UP);check(cap!=null,"actual cached chamber EU capability");cachedChambers.add(cap);
        }
    }
    private CompoundTag project(CompoundTag tag){
        var out=new CompoundTag();
        for(String key:List.of("Items","ReactorItems","HeatStored","MaxHeatStored","FluidHandler","energy","scex_energy_fraction","scex_reactor","ReactorMode")){
            check(tag.contains(key),"ordinary save field "+key);out.put(key,Objects.requireNonNull(tag.get(key)).copy());
        }
        return out;
    }
    private List<Map<String,Object>> rows(ServerLevel w){
        var result=new ArrayList<Map<String,Object>>();
        for(int i=0;i<CASES;i++){
            var row=new LinkedHashMap<String,Object>();row.put("case",i);row.put("position",at(i).toShortString());row.put("block",w.getBlockState(at(i)).toString());
            if(i!=9){row.put("core",project(core(w,i).saveWithoutMetadata(w.registryAccess())).toString());row.put("sinks",sinks(w,i).toString());}
            result.add(row);
        }
        return result;
    }
    private void sample(ServerLevel w,int tick,String reason)throws Exception {
        var sample=Map.<String,Object>of("tick",tick,"game_time",w.getGameTime(),"reason",reason,"rows",rows(w));samples.add(sample);
        Files.writeString(Path.of(TRACE),new Gson().toJson(sample)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private void seed(ServerLevel w,int i,long heat,int counter,boolean legacy){
        var m=core(w,i);m.getHeatStorage().setCapacity(i==1?11000:10000);m.getHeatStorage().setHeat(heat);
        var tag=m.saveWithoutMetadata(w.registryAccess());tag.putInt("ReactorCycleTicks",counter);
        tag.putLong("energy",i==5?0:137);tag.putLong("scex_energy_fraction",i==5?0:EnergyAmount.UNITS/4);
        var own=tag.getCompound("scex_reactor");own.putLong("rate",0);own.putLong("rate_fraction",0);
        if(legacy){own.putInt("version",1);own.remove("accident");}
        if(i==6)own.putString("failure","Reactor heat reached capacity; accident integration pending");
        if(i==7||i==8){
            var accident=new CompoundTag();accident.putInt("version",1);accident.putString("state",i==7?"UNKNOWN_R137":"DISPATCHING");
            accident.putLong("final_heat",10000);accident.putLong("final_capacity",10000);accident.putLong("game_time",w.getGameTime());accident.putString("effect","NUCLEAR_TERRAIN");own.put("accident",accident);
        }
        m.loadAdditional(tag,w.registryAccess());
    }
    private void setup(ServerLevel w){
        setConfig(true);
        for(int i=0;i<CASES;i++){
            if(i==2||i==3)shell(w,i);else place(w,at(i),"generator/block_nuclear_reactor_generator",Direction.NORTH);
            if(i==4)for(var side:Direction.values()){
                w.setBlock(at(i).relative(side),mio_icif_blocks.REACTOR_CHAMBER.get().defaultBlockState(),18);
                place(w,at(i).relative(side,2),"wiring/block_mfsu",side);
            }else for(var p:sinkPositions(i))place(w,p,"wiring/block_mfsu",Direction.DOWN);
            var m=core(w,i);
            if(i==0||i==2||i==3)m.setItem(0,item("vent_core"));
            if(i==1)m.setItem(0,item("plate"));
            if(i==4||i==6||i==7||i==8)m.setItem(0,fuel());
            if(i==2||i==3)m.getFluidHandler().commitCycle(10000,i==3?10000:0,0);
            long heat=switch(i){case 0,2,3,6,7,8->10000;case 1->10500;case 4->9996;default->0;};
            seed(w,i,heat,19,i==0||i==6);
            check(m.ownedEnergy().scexNetworkControlled(),"actual independent owner "+i);
            if(i==4)w.setBlockAndUpdate(at(i).offset(1,1,0),Blocks.REDSTONE_BLOCK.defaultBlockState());
            if(i!=9)cache(w,i);
        }
        // Only this adversarial fixture subclasses the real owner. The registered block/type and natural ticker are unchanged.
        var old=core(w,9);removedOwner=new RemovalReactor(at(9),old.getBlockState());
        w.setBlockEntity(removedOwner);check(w.getBlockEntity(at(9))==removedOwner,"real replacement block entity installed");
        removedOwner.arm();groups.add("actual-registry-thermal-fluid-six-contact-fixtures");
    }
    private void freeze(ServerLevel w,int i){
        var m=core(w,i);check(m.hasReactorAccident()||m.hasLegacyHold(),"actual closed result "+i);
        frozen.put(i,project(m.saveWithoutMetadata(w.registryAccess())));
    }
    private void closed(ServerLevel w,int i){
        var m=core(w,i);var before=m.ownedEnergy().scexExactAmount();
        check(m.outputFaces()==0&&m.potentialEnergy().isZero()&&!m.isRunning(),"closed native offer "+i);
        m.prepareEnergy(EnergyAmount.of(1000));check(m.ownedEnergy().scexExactAmount().equals(before),"no new EU credit "+i);
        check(cachedEu[i].extract(1,false)==0&&cachedEu[i].extractPowerForConsumer(1,false)==0,"cached core EU cannot debit "+i);
        check(cachedHeat[i].extractHeat(1,false)==0,"cached heat port cannot alter failed state "+i);
        check(cachedItems[i].extractItem(0,1,false).isEmpty(),"cached item extraction closed "+i);
        var incoming=item("plate");check(ItemStack.matches(cachedItems[i].insertItem(1,incoming,false),incoming),"cached item input closed "+i);
        check(project(m.saveWithoutMetadata(w.registryAccess())).equals(frozen.get(i)),"exact pending fuel/inventory/fluid/heat/EU preservation "+i);
        if(i==4){
            for(var port:cachedChambers)check(port.extract(1,false)==0,"each cached chamber denies shared balance");
            check(m.ownedEnergy().scexExactAmount().equals(EnergyAmount.fromDouble(137.25)),"original fractional owner EU remains evidence");
            for(var entry:sinks(w,i))check(entry.get(0)==0&&entry.get(1)==0,"six real sinks cannot receive post-accident EU");
        }
    }
    public Map<String,Object> inspect(ServerLevel w,int tick)throws Exception {
        if(finished)return null;
        try{return inspectInner(w,tick);}catch(Exception|Error failure){restoreConfig();throw failure;}
    }
    private Map<String,Object> inspectInner(ServerLevel w,int tick)throws Exception {
        if(restart){
            if(tick>0)for(int i:frozen.keySet())closed(w,i);
            if(tick==120){check(w.getBlockState(at(9)).is(Blocks.STONE),"cold old ticker never resurrects core");groups.add("cold-natural-ticks-no-repeated-fuel-or-balance-debits");return finish(w,tick);}
            return null;
        }
        if(tick==20)setup(w);
        if(tick==41){
            check(core(w,0).getCurrentHeat()==9995&&!core(w,0).hasReactorAccident(),"normal legacy high heat cools first");
            check(core(w,1).getCurrentHeat()==10500&&core(w,1).getMaxHeat()==11000&&!core(w,1).hasReactorAccident(),"real plate capacity baseline");
            check(core(w,2).isValidFluidReactorStructure()&&core(w,2).getCurrentHeat()==9995&&core(w,2).getInputFluidAmount()==9990&&core(w,2).getOutputFluidAmount()==10,"real liquid room conversion before decision");
            check(core(w,3).isValidFluidReactorStructure()&&core(w,3).getCurrentHeat()==10000&&core(w,3).getInputFluidAmount()==10000&&core(w,3).getOutputFluidAmount()==10000,"real full hot tank returns heat before failure");
            check(core(w,4).getItem(0).get(mio_icif_data_components.FUEL_ROD_DURABILITY.get()).remainingUses()==9,"exactly one critical fuel debit");
            check(core(w,6).getItem(0).get(mio_icif_data_components.FUEL_ROD_DURABILITY.get()).remainingUses()==10,"legacy pending does not debit another cycle");
            check(core(w,7).hasLegacyHold()&&core(w,7).saveWithoutMetadata(w.registryAccess()).getCompound("scex_reactor").getCompound("hold").getCompound("invalid_accident").getCompound("accident").getString("state").equals("UNKNOWN_R137"),"unknown typed state retained in real NBT");
            check(core(w,8).getAccidentState().equals("UNCERTAIN"),"restored in-flight failure is not replayed");
            check(removedOwner.callbacks==1&&w.getBlockState(at(9)).is(Blocks.STONE),"natural work callback replaced owner without ACTIVE resurrection");
            mio_icif_nuclear_reactor_generator.tick(w,at(9),removedOwner.getBlockState(),removedOwner);
            check(w.getBlockState(at(9)).is(Blocks.STONE)&&removedOwner.outputFaces()==0&&removedOwner.potentialEnergy().isZero(),"explicit stale ticker/reference remains inert");
            for(int i:new int[]{3,4,6,7,8})freeze(w,i);
            groups.add("four-reference-thermal-decisions-and-critical-fuel-single-commit");groups.add("actual-natural-ticker-owner-replacement-guard");sample(w,tick,"first-cycles");
        }
        if(tick==80){
            check(core(w,1).outputFaces()!=0,"plate baseline had a genuinely open native source");
            core(w,1).setItem(0,ItemStack.EMPTY);
        }
        if(tick==101){check(core(w,1).getCurrentHeat()==10500&&core(w,1).getMaxHeat()==10000,"actual plate removal crosses next cycle capacity");freeze(w,1);groups.add("actual-inventory-plate-removal-next-cycle-failure");sample(w,tick,"plate-removed");}
        if(tick==120){
            setConfig(false);var m=core(w,5);m.setItem(0,fuel());
            var tag=m.saveWithoutMetadata(w.registryAccess());tag.putLong("HeatStored",9996);tag.putInt("ReactorCycleTicks",0);tag.putLong("energy",137);tag.putLong("scex_energy_fraction",EnergyAmount.UNITS/4);m.loadAdditional(tag,w.registryAccess());
            w.setBlockAndUpdate(at(5).west(),Blocks.REDSTONE_BLOCK.defaultBlockState());
        }
        if(tick==122){
            var m=core(w,5);check(m.hasReactorAccident()&&m.getItem(0).get(mio_icif_data_components.FUEL_ROD_DURABILITY.get()).remainingUses()==9,"disabled terrain still stops powered critical cycle");
            check(m.saveWithoutMetadata(w.registryAccess()).getCompound("scex_reactor").getCompound("accident").getString("effect").equals("LOCAL_MACHINE"),"false config scope persisted without physical placeholder");
            freeze(w,5);restoreConfig();groups.add("false-configuration-selects-local-policy-and-still-closes");sample(w,tick,"false-config-cycle");
        }
        if(tick>41)for(int i:frozen.keySet())closed(w,i);
        if(tick==180){
            // Stop only the two safe passive cooling fixtures so their exact final saved state stays stable until normal server stop.
            core(w,0).setItem(0,ItemStack.EMPTY);core(w,2).setItem(0,ItemStack.EMPTY);
        }
        if(tick==240){
            restoreConfig();groups.add("one-hundred-plus-natural-closed-ticks-and-real-native-receiver-ledgers");
            Files.writeString(Path.of(CHECKPOINT),new Gson().toJson(Map.of("base_x",baseX,"rows",rows(w),"game_time",w.getGameTime())));
            return finish(w,tick);
        }
        if(tick>300)throw new AssertionError("R137 fixture deadline");
        return null;
    }
    private Map<String,Object> finish(ServerLevel w,int tick)throws Exception {
        restoreConfig();sample(w,tick,"finish");finished=true;
        var result=Map.<String,Object>of("passed",true,"phase",restart?"restart":"initial","assertions",assertions,"cases",CASES,"groups",groups,"rows",rows(w),
            "scope","Accident lifecycle PENDING only. No terrain/entity/drop/thermal-environment blast acceptance; no physical accident effect implemented.");
        Files.writeString(Path.of(RESULT),new Gson().toJson(result));return result;
    }
    private static final class RemovalReactor extends mio_icif_nuclear_reactor_generator {
        private boolean armed;private int callbacks;
        RemovalReactor(BlockPos position,BlockState state){super(position,state);}
        void arm(){armed=true;}
        @Override public int getAvailableColumns(){
            int columns=super.getAvailableColumns();
            if(armed&&level instanceof ServerLevel server){
                armed=false;callbacks++;
                server.setBlock(worldPosition,getBlockState().setValue(mio_icif_Block_Nuclear_Reactor_Generator.ACTIVE,true),18);
                server.setBlock(worldPosition,Blocks.STONE.defaultBlockState(),18);
            }
            return columns;
        }
    }
}
