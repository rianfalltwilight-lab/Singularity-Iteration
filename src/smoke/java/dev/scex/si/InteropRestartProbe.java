// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Container;
import com.singularity_iteration.mio_icif.Blocks.entity.pipe.mio_icif_pipe_item;
import dev.scex.si.energy.FeLedger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Reads an actual prior world; never recreates its machines, buffers, or inventories. */
public final class InteropRestartProbe {
    private static final BlockPos FURNACE=new BlockPos(400,80,4), BOX=new BlockPos(400,80,10), RECEIVER=new BlockPos(400,80,11);
    private static final BlockPos TARGET=new BlockPos(419,80,24), LONG_START=new BlockPos(384,80,48), LONG_END=new BlockPos(984,80,48);
    private static final BlockPos PENDING_A=new BlockPos(429,80,22), PENDING_B=new BlockPos(429,80,19);
    private final JsonObject cold,prior;
    private final String phase;
    private final boolean verify;
    private final List<BlockPos> pipes=new ArrayList<>();
    private final List<String> groups=new ArrayList<>();
    private int start=-1,assertions,baselineReceiver,extraStorage,pausedReceiver;
    private boolean finished;
    public InteropRestartProbe() throws Exception {
        phase=JsonParser.parseString(Files.readString(Path.of("interop-restart.json"))).getAsJsonObject().get("phase").getAsString();
        if(!phase.equals("resume")&&!phase.equals("verify"))throw new IllegalArgumentException("Unknown restart phase");
        verify=phase.equals("verify");cold=JsonParser.parseString(Files.readString(Path.of("cold-snapshot.json"))).getAsJsonObject();
        prior=verify?JsonParser.parseString(Files.readString(Path.of("prior-phase-state.json"))).getAsJsonObject():null;
        if(verify && (!prior.get("passed").getAsBoolean() || !prior.get("phase").getAsString().equals("resume")))throw new IllegalArgumentException("Prior resume did not pass");
        for(var p:cold.getAsJsonArray("pipes")){var pos=p.getAsJsonObject().getAsJsonArray("pos");pipes.add(new BlockPos(pos.get(0).getAsInt(),pos.get(1).getAsInt(),pos.get(2).getAsInt()));}
        if(pipes.size()!=742)throw new IllegalArgumentException("Expected saved 742-pipe fixture");
        extraStorage=verify?prior.get("added_storage_fe").getAsInt():0;
    }
    private void check(boolean condition,String name){assertions++;if(!condition)throw new AssertionError("SI restart "+phase+": "+name);}
    private void done(String group){groups.add(group);System.out.println("SCEX_INTEROP_RESTART_PASS "+phase+" "+group);}
    private boolean ready(ServerLevel world,BlockPos pos){return world.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)!=null
        && world.shouldTickBlocksAt(net.minecraft.world.level.ChunkPos.asLong(pos));}
    private Object tile(ServerLevel world,BlockPos pos){var chunk=world.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
        return chunk==null?null:chunk.getBlockEntity(pos,LevelChunk.EntityCreationType.CHECK);}
    private mio_icif_Energy_Container box(ServerLevel world){return (mio_icif_Energy_Container)tile(world,BOX);}
    private int energy(ServerLevel world,BlockPos pos){return FeLedger.fe(((mio_icif_Energy_Block)tile(world,pos)).getEnergyStorageInternal().scexExactAmount());}
    private int receiver(ServerLevel world){return ((ChestBlockEntity)tile(world,RECEIVER)).getPersistentData().getInt("r54_fe");}
    private int charged(ServerLevel world){var cap=box(world).getItem(0).getCapability(Capabilities.EnergyStorage.ITEM);check(cap!=null,"persisted item FE capability");return cap.getEnergyStored();}
    private int count(ServerLevel world,BlockPos pos,Item item){var c=(Container)tile(world,pos);int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))n+=c.getItem(i).getCount();return n;}
    private int lapis(ServerLevel world){int n=0;for(var pos:pipes){var p=(mio_icif_pipe_item)tile(world,pos);var b=p.getBufferItem();check(!p.hasUncertainTransfer(),"no new uncertain transfer");if(b.is(Items.LAPIS_LAZULI))n+=b.getCount();}return n;}
    private void total(ServerLevel world){check(energy(world,BOX)+receiver(world)+charged(world)==cold.get("energy_total_fe").getAsInt()+extraStorage,"exact tracked FE conservation across restart");}
    private void openOutput(ServerLevel world,BlockPos pending){BlockPos at=pending.east();check(world.getBlockState(at).isAir(),"new destination is clear");
        world.setBlockAndUpdate(at.below(),Blocks.STONE.defaultBlockState());world.setBlockAndUpdate(at,Blocks.CHEST.defaultBlockState());}
    private void seedLong(ServerLevel world){var cap=world.getCapability(Capabilities.ItemHandler.BLOCK,LONG_START,Direction.WEST);
        check(cap!=null&&cap.insertItem(0,new ItemStack(Items.NETHERITE_INGOT),false).isEmpty(),"loaded long pipe accepts a new owned item");}
    private void initial(ServerLevel world) {
        check(tile(world,FURNACE) instanceof mio_icif_Energy_Block && tile(world,BOX) instanceof mio_icif_Energy_Container,"saved actual machines loaded");
        check(charged(world)==256,"charged FE item retains exact component value");
        check(box(world).canProvidePowerFromSide(Direction.SOUTH),"saved output orientation");
        check(energy(world,FURNACE)==(verify?1201:2400),"saved furnace amount including fractional FE");
        check(count(world,FURNACE,Items.STONE)==(verify?2:1),"completed recipe output persisted");
        check(count(world,TARGET,Items.DIAMOND)==4&&count(world,TARGET,Items.EMERALD)==3&&count(world,TARGET,Items.GOLD_INGOT)==4,"previous deliveries survive without replay");
        check(count(world,LONG_END,Items.NETHERITE_INGOT)==(verify?2:1),"previous long-route delivery persisted exactly once");
        total(world);baselineReceiver=receiver(world);
        if(verify){
            check(box(world).getRedstoneMode()==7 && energy(world,BOX)==prior.get("box_fe").getAsInt()
                && receiver(world)==prior.get("receiver_fe").getAsInt(),"paused storage and receiving ledger survive exactly");
            check(energy(world,BOX)%4==1,"quarter EU storage balance survives actual JVM restart");
            check(count(world,PENDING_A.east(),Items.LAPIS_LAZULI)==1,"first recovered saved buffer persisted at destination");
        }
        done("saved machines, FE ledgers, orientation and delivered outputs restored");
        for(int i=0;i<pipes.size();i++){
            BlockPos pos=pipes.get(i);check(tile(world,pos) instanceof mio_icif_pipe_item,"saved pipe entity exists "+pos);
            var pipe=(mio_icif_pipe_item)tile(world,pos);var expected=cold.getAsJsonArray("pipes").get(i).getAsJsonObject();
            check(pipe.getMode().name().equals(expected.get("mode").getAsString()),"saved pipe mode");
            boolean occupied=expected.getAsJsonObject("buffer").has("count") && !(verify&&pos.equals(PENDING_A));
            var buffer=pipe.getBufferItem();check(occupied?buffer.is(Items.LAPIS_LAZULI)&&buffer.getCount()==1:buffer.isEmpty(),"saved exact buffer "+pos);
        }
        check(lapis(world)==(verify?127:128),"all pending saved items retained");done("all 742 saved pipe entities and pending buffers restored");
    }
    public Map<String,Object> inspect(ServerLevel world,int tick) throws Exception {
        if(finished)return null;
        if(start<0){
            if(tick<10)return null;
            boolean loaded=ready(world,FURNACE)&&ready(world,BOX)&&ready(world,RECEIVER)&&ready(world,TARGET)&&ready(world,LONG_END)
                && pipes.stream().allMatch(pos->ready(world,pos));
            if(!loaded){check(tick<80,"saved forced chunks became block ticking before deadline");return null;}
            initial(world);start=tick;
        }
        int elapsed=tick-start;
        if(elapsed%10==0)total(world);
        if(elapsed==10){((Container)tile(world,FURNACE)).setItem(0,new ItemStack(Items.COBBLESTONE));if(verify)box(world).setRedstoneMode((byte)0);}
        if(elapsed==15)seedLong(world);
        if(elapsed==20)openOutput(world,verify?PENDING_B:PENDING_A);
        if(elapsed==90){check(receiver(world)>baselineReceiver,"saved storage actively resumes FE output");done("FE output resumes from persisted energy");}
        if(elapsed==150){
            check(count(world,FURNACE,Items.STONE)==(verify?3:2)&&count(world,FURNACE,Items.COBBLESTONE)==0,"new recipe consumes restored energy");
            check(energy(world,FURNACE)==(verify?1:1200),"exact recipe debit preserves fractional remainder");
            if(!verify){var cap=world.getCapability(Capabilities.EnergyStorage.BLOCK,FURNACE,Direction.WEST);check(cap!=null&&cap.receiveEnergy(1,false)==1,"create one FE furnace remainder for second restart");}
            done("restored furnace performs another recipe with exact debit");
        }
        if(elapsed==160){
            check(count(world,LONG_END,Items.NETHERITE_INGOT)==(verify?3:2),"loaded 600-pipe route delivers again exactly once");
            check(((mio_icif_pipe_item)tile(world,LONG_START)).isEmpty(),"new long-route source buffer drained");done("loaded long network accepts and delivers fresh traffic");
            int recovered=count(world,PENDING_A.east(),Items.LAPIS_LAZULI)+(verify?count(world,PENDING_B.east(),Items.LAPIS_LAZULI):0);
            check(recovered==(verify?2:1)&&lapis(world)+recovered==128,"saved pending buffer resumes after adding an outlet, without loss or duplication");
            done("saved pending buffers recover after an outlet is attached");
        }
        if(!verify&&elapsed==180)box(world).setRedstoneMode((byte)7);
        if(!verify&&elapsed==185){
            int now=energy(world,BOX);extraStorage=Math.floorMod(1-now,4);if(extraStorage==0)extraStorage=4;
            var cap=world.getCapability(Capabilities.EnergyStorage.BLOCK,BOX,Direction.NORTH);
            check(cap!=null&&cap.receiveEnergy(extraStorage,false)==extraStorage,"prepare exact quarter EU in paused storage");
            pausedReceiver=receiver(world);
        }
        if(elapsed==(verify?200:230)){
            if(!verify){check(energy(world,BOX)%4==1&&receiver(world)==pausedReceiver&&box(world).getRedstoneMode()==7,"fractional storage remains paused before final save");
                check(energy(world,FURNACE)==1201,"fractional furnace remains unchanged before final save");done("fractional storage and furnace state ready for actual second restart");}
            else {check(energy(world,FURNACE)==1,"second recipe leaves the restored single FE instead of truncating it");done("fractional balances survived and remained usable after second restart");}
            total(world);
            var row=new LinkedHashMap<String,Object>();row.put("passed",true);row.put("phase",phase);row.put("assertions",assertions);row.put("groups",List.copyOf(groups));
            row.put("added_storage_fe",extraStorage);row.put("box_fe",energy(world,BOX));row.put("receiver_fe",receiver(world));row.put("item_fe",charged(world));row.put("furnace_fe",energy(world,FURNACE));
            row.put("tracked_fe_total",energy(world,BOX)+receiver(world)+charged(world));row.put("pending_lapis",lapis(world));row.put("source_world_seal",cold.get("source_world_seal_sha256").getAsString());
            check(groups.size()==7,"all planned restart groups executed");
            Files.writeString(Path.of("interop-phase-state.json"),new Gson().toJson(row));finished=true;return row;
        }
        return elapsed%50==0?Map.of("phase",phase,"elapsed",elapsed,"groups",List.copyOf(groups),"assertions",assertions):null;
    }
}
