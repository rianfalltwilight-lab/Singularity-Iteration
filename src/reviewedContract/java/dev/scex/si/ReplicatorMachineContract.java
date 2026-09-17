// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_replicator_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.MachineItemHandler;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.SlotLayout;
import dev.scex.si.processing.UuQuoteBook;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/** Actual compiled block entity with explicit test-only fluid/quote/platform fixtures; no world acceptance. */
public final class ReplicatorMachineContract {
    private static int assertions;
    private static HolderLookup.Provider registries;
    private static void require(boolean pass,String why){assertions++;if(!pass)throw new AssertionError(why);}
    private static final class Machine extends mio_icif_replicator_elc {
        ItemStack offered; double cost; long generation=1; boolean quotes=true,authority=true;
        Machine(ItemStack item,double buckets){
            super(BlockPos.ZERO,Blocks.FURNACE.defaultBlockState(),BlockEntityType.FURNACE);
            offered=item.copyWithCount(1);cost=buckets;
            getUuMatterTank().setFluid(new FluidStack(Fluids.WATER,1000));getEnergyStorageInternal().setEnergy(2000000);
        }
        @Override protected boolean serverThread(){return authority;}
        @Override protected Fluid uuFluid(){return Fluids.WATER;}
        @Override protected ItemStack selectPatternItem(){return offered.copy();}
        @Override protected long priceGeneration(){return quotes?generation:-1;}
        @Override protected UuQuoteBook.Quote trustedQuote(ItemStack item){return quotes?new UuQuoteBook.Quote(cost,generation):null;}
        void step(){onTick();tickProduction();}
        void put(int slot,ItemStack item){itemHandler.setStackInSlot(slot,item.copy());}
        CompoundTag saved(){var tag=new CompoundTag();saveAdditional(tag,registries);return tag;}
    }
    private static void replay(Path fixture)throws Exception{
        var saves=new HashMap<String,CompoundTag>();int steps=0;
        try(var reader=Files.newBufferedReader(fixture)){
            for(var element:JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("cases")){
                var c=element.getAsJsonObject();String item=c.get("item").getAsString();
                var stack=new ItemStack(item.equals("minecraft:stone")?Items.STONE:Items.IRON_INGOT);
                var machine=new Machine(stack,c.get("unitBuckets").getAsDouble());
                if(c.get("part").getAsString().equals("04")){
                    machine.loadAdditional(saves.get(item),registries);
                    require(!machine.hasHeldReplicationData(),"Owned machine saved state is readable");
                    require(machine.getItemHandler().extractItem(OUTPUT_SLOT,64,false).getCount()==64,"Remove first completed stack before reference restart continuation");
                }else machine.loopGeneration();
                require(machine.getUuMatterAmount()==c.get("initialTank").getAsInt(),"Reference initial tank");
                require(Math.abs(machine.getUuCreditBuckets()-c.get("initialCredit").getAsDouble())<1e-15,"Reference initial fractional credit");
                for(var step:c.getAsJsonArray("steps")){
                    var row=step.getAsJsonObject();String at=item+" "+c.get("part")+" tick "+row.get("tick");machine.step();steps++;
                    require(machine.getUuMatterAmount()==row.get("tank").getAsInt(),"Reference integer debit: "+at);
                    require(Math.abs(machine.getUuCreditBuckets()-row.get("credit").getAsDouble())<1e-15,"Reference credit: "+at);
                    require(Math.abs(machine.getProcessedUuBuckets()-row.get("progress").getAsDouble())<1e-15,"Reference paid progress: "+at);
                    require(machine.getItemHandler().getStackInSlot(OUTPUT_SLOT).getCount()==row.get("output").getAsInt(),"Reference output timing/count: "+at);
                    require(machine.getEnergyStorageInternal().getAmount()==row.get("energy").getAsLong(),"Reference EU debit: "+at);
                }
                require(machine.getItemHandler().getStackInSlot(OUTPUT_SLOT).getCount()==64,"Full reference stack");
                var before=machine.saved();for(int i=0;i<25;i++)machine.step();
                var after=machine.saved();before.putBoolean("is_working",false);
                require(before.equals(after),"Full output does not charge or erase work");saves.put(item,after);
            }
        }
        require(steps==1280,"All original reference machine work ticks tested");
    }
    private static final int OUTPUT_SLOT=mio_icif_replicator_elc.OUTPUT_SLOT;
    private static void boundaries(){
        var item=new ItemStack(Items.STONE);var machine=new Machine(item,.00015);machine.generateOnce();machine.step();
        require(machine.getProcessedUuBuckets()==.0001&&machine.getItemHandler().getStackInSlot(OUTPUT_SLOT).isEmpty(),"No output before complete UU payment");
        var paid=machine.saved();var resumed=new Machine(item,.00015);resumed.loadAdditional(paid,registries);resumed.step();
        require(resumed.getItemHandler().getStackInSlot(OUTPUT_SLOT).getCount()==1&&resumed.getWorkMode()==mio_icif_replicator_elc.WorkMode.STOPPED,"Single copy resumes from paid work and stops after one item");
        require(resumed.getEnergyStorageInternal().getAmount()==1998976,"In-progress save does not replay first EU debit");
        resumed.step();require(resumed.getItemHandler().getStackInSlot(OUTPUT_SLOT).getCount()==1,"Single mode remains stopped");

        var missingQuote=new Machine(item,.00015);missingQuote.loadAdditional(paid,registries);missingQuote.quotes=false;
        long eu=missingQuote.getEnergyStorageInternal().getAmount();int tank=missingQuote.getUuMatterAmount();
        missingQuote.step();require(missingQuote.getEnergyStorageInternal().getAmount()==eu&&missingQuote.getUuMatterAmount()==tank&&missingQuote.getItemHandler().getStackInSlot(OUTPUT_SLOT).isEmpty(),"Saved prices cannot authorize work without current server quotes");
        machine.cost=.0002;machine.generation++;machine.step();require(machine.hasHeldReplicationData(),"Changed price preserves paid state for reconciliation");
        machine.put(0,new ItemStack(Items.DIAMOND));require(machine.getItemHandler().extractItem(0,1,false).isEmpty(),"Already-open menu cannot remove held contents");
        require(machine.removeItemNoUpdate(0).isEmpty(),"Container route also respects held ownership");
        var held=machine.saved();require(held.getCompound("scex_replication_v1").getDouble("processed")==.0001,"Held work retains actual consumed UU");

        var blockedOutput=new Machine(item,.00015);blockedOutput.put(OUTPUT_SLOT,new ItemStack(Items.DIRT));blockedOutput.loopGeneration();blockedOutput.step();
        require(blockedOutput.getEnergyStorageInternal().getAmount()==2000000&&blockedOutput.getUuMatterAmount()==1000,"Different output prevents prepayment");
        var noPower=new Machine(item,.00015);noPower.getEnergyStorageInternal().setEnergy(511);noPower.loopGeneration();noPower.step();
        require(noPower.getUuMatterAmount()==1000&&noPower.getUuCreditBuckets()==0&&noPower.getEnergyStorageInternal().getAmount()==511,"Insufficient EU changes neither account");
        var noFluid=new Machine(item,.00015);noFluid.getUuMatterTank().setFluid(FluidStack.EMPTY);noFluid.loopGeneration();noFluid.step();
        require(noFluid.getEnergyStorageInternal().getAmount()==2000000,"Insufficient UU does not consume EU");
        var client=new Machine(item,.00015);client.authority=false;client.loopGeneration();client.step();
        require(client.getWorkMode()==mio_icif_replicator_elc.WorkMode.STOPPED&&client.getEnergyStorageInternal().getAmount()==2000000,"Client cannot start or pay work");

        var unknown=paid.copy();unknown.getCompound("scex_replication_v1").putString("future_field","keep");
        var future=new Machine(item,.00015);future.loadAdditional(unknown,registries);
        require(future.hasHeldReplicationData()&&future.saved().getCompound("scex_replication_v1").getCompound("held").equals(unknown),"Unknown schema preserved, not silently truncated");
        for(var key:new String[]{"price","processed","pending_uu"}){
            var malformed=paid.copy();malformed.getCompound("scex_replication_v1").putDouble(key,Double.NaN);
            var m=new Machine(item,.00015);m.loadAdditional(malformed,registries);require(m.hasHeldReplicationData(),"Nonfinite work held: "+key);
        }
        var legacy=new CompoundTag();legacy.putDouble("old_wrong_cost",0.000001);
        var old=new Machine(item,.00015);old.loadAdditional(legacy,registries);
        require(old.hasHeldReplicationData()&&old.saved().getCompound("scex_replication_v1").getCompound("held").equals(legacy),"Unmapped legacy work remains intact");

        int[] published={0},notifications={0};
        var slots=new MachineItemHandler(SlotLayout.builder().output(1).build()){
            @Override protected void onContentsChanged(int slot){notifications[0]++;require(published[0]==1&&getStackInSlot(0).getCount()==1,"Inventory observers see owner state and output together");}
        };
        require(slots.scexCommitSlots(new int[]{0},new ItemStack[]{ItemStack.EMPTY},new ItemStack[]{item},()->published[0]=1)&&notifications[0]==1,"Owner publication occurs before callbacks");
        require(!slots.scexCommitSlots(new int[]{0},new ItemStack[]{ItemStack.EMPTY},new ItemStack[]{item},()->published[0]=2)&&published[0]==1,"Rejected output does not publish state");
    }
    public static void main(String[] args)throws Exception{
        net.neoforged.fml.loading.LoadingModList.of(java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        require(Path.of(mio_icif_replicator_elc.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[0]).toRealPath()),"Newly compiled actual replicator loaded");
        replay(Path.of(args[1]));boundaries();
        System.out.println("SCEX_REPLICATOR_MACHINE assertions="+assertions+" reference_steps=1280 PASS scope=actual_entity_with_test_platform_no_world");
    }
}
