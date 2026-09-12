// SPDX-License-Identifier: Apache-2.0
// Frozen behavior observations: ic2-boundaries-07. Save/load tests are SI invariants.
package dev.scex.si;

import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_producer;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_furnace_elc;
import com.singularity_iteration.mio_icif.Blocks.mio_icif_blocks;
import com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class MachineBoundaryProbe {
    public record Result(int assertions, int failures) {}
    private int assertions, failures, nextX=0;
    private ServerLevel level;
    private Item overclocker, storage;
    private void check(boolean ok, String message) {
        assertions++;
        if (!ok) { failures++; System.out.println("SI_BOUNDARY_FAIL " + message); }
    }
    private static Item item(String id) { return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)); }
    private mio_icif_producer create(Block block, int n, boolean buffer, long energy) {
        BlockPos pos = new BlockPos(nextX,80,8); nextX+=3;
        level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos,block.defaultBlockState());
        var machine = (mio_icif_producer)level.getBlockEntity(pos);
        if (n>0) machine.setItem(3,new ItemStack(overclocker,n));
        if (buffer) for(int slot=4;slot<7;slot++) machine.setItem(slot,new ItemStack(storage,64));
        tick(machine); machine.getEnergyStorageInternal().setEnergy(energy);
        return machine;
    }
    private void tick(mio_icif_producer machine) {
        mio_icif_producer.tick(level,machine.getBlockPos(),level.getBlockState(machine.getBlockPos()),machine);
    }
    private void remove(mio_icif_producer machine) { level.removeBlock(machine.getBlockPos(),false); }
    public Result run(MinecraftServer server) {
        level=server.overworld(); overclocker=item("mio_icif:upgrade/overclocker_upgrade");
        storage=item("mio_icif:upgrade/energy_storage_upgrade");
        int[] firstExpected={78,85,120,145,100,145};
        for(int scenario=0;scenario<6;scenario++) {
            var machine=create(mio_icif_blocks.FURNACE_ELC.get(),scenario==1?1:0,true,1_000_000);
            machine.setItem(0,new ItemStack(Items.COBBLESTONE,64));
            int first=0;
            for(int t=1;t<=150;t++) {
                tick(machine);
                if(first==0 && !machine.getItem(0).isEmpty() && machine.getItem(0).getCount()<64) first=t;
                if(t==25) {
                    switch(scenario) {
                        case 0 -> machine.setItem(3,new ItemStack(overclocker));
                        case 2 -> machine.getEnergyStorageInternal().setEnergy(0);
                        case 3 -> machine.setItem(2,new ItemStack(Items.STONE,64));
                        case 4 -> machine.setItem(0,new ItemStack(Items.SAND,64));
                        case 5 -> machine.setItem(0,ItemStack.EMPTY);
                    }
                }
                if(t==35 && scenario==1) machine.setItem(3,ItemStack.EMPTY);
                if(t==45) {
                    if(scenario==2) machine.getEnergyStorageInternal().setEnergy(1_000_000);
                    if(scenario==3) machine.setItem(2,ItemStack.EMPTY);
                    if(scenario==5) machine.setItem(0,new ItemStack(Items.COBBLESTONE,64));
                }
                if(t==40 && scenario==2) check(machine.getProgress()==25,"outage-retains-progress");
                if(t==40 && (scenario==3 || scenario==5)) check(machine.getProgress()==0,"unavailable-input-output-resets-"+scenario);
            }
            check(first==firstExpected[scenario],"scenario="+scenario+" first="+first+" expected="+firstExpected[scenario]);
            check(machine.getItem(2).is(scenario==4?Items.GLASS:Items.STONE),"scenario-output-"+scenario);
            remove(machine);
        }
        for(boolean full:new boolean[]{true,false}) {
            var machine=create(mio_icif_blocks.FURNACE_ELC.get(),20,true,full?1_000_000:36_268);
            machine.setItem(0,new ItemStack(Items.COBBLESTONE,64));
            if(full) machine.setItem(2,new ItemStack(Items.STONE,63));
            for(int t=0;t<5;t++) tick(machine);
            check(machine.getItem(2).getCount()==(full?64:13),"batch-output-boundary-"+full);
            check(machine.getItem(0).getCount()==(full?63:51),"batch-input-conservation-"+full);
            check(machine.getEnergy()==(full?963_732:0),"batch-paid-once-"+full);
            remove(machine);
        }
        int[] counts={0,1,3,8,13}; long[] capacities={600,650,708,1074,3002};
        for(int i=0;i<counts.length;i++) {
            var machine=create(mio_icif_blocks.FURNACE_ELC.get(),counts[i],false,0);
            check(machine.getEffectiveCapacity()==capacities[i],"measured-capacity-"+counts[i]);
            remove(machine);
        }
        String[] blocks={"mio_icif:producer/block_powder_elc","mio_icif:producer/block_compressor_elc","mio_icif:producer/block_extractor_elc"};
        Item[] input={Items.COBBLESTONE,Items.SAND,item("mio_icif:resource/item_harz")};
        Item[] output={Items.SAND,Items.SANDSTONE,item("mio_icif:resource/item_rubber")};
        int[] used={1,4,1}, made={1,1,3}, outputSlot={2,2,1};
        for(int i=0;i<blocks.length;i++) {
            Block block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blocks[i]));
            check(block!=Blocks.AIR && input[i]!=Items.AIR && output[i]!=Items.AIR,"basic-machine-registered-"+i);
            if(block==Blocks.AIR) continue;
            var machine=create(block,0,true,1_000_000);
            machine.setItem(0,new ItemStack(input[i],64));
            int first=0;
            for(int t=1;t<=650;t++) { tick(machine); if(first==0 && !machine.getItem(outputSlot[i]).isEmpty()) first=t; }
            check(first==300,"basic-duration-"+i+" first="+first);
            check(machine.getItem(0).getCount()==64-2*used[i],"basic-input-"+i);
            check(machine.getItem(outputSlot[i]).is(output[i]) && machine.getItem(outputSlot[i]).getCount()==2*made[i],"basic-output-"+i);
            check(machine.getEnergy()==998_700,"basic-energy-"+i);
            remove(machine);
        }
        var original=create(mio_icif_blocks.FURNACE_ELC.get(),1,true,1_000_000);
        original.setItem(0,new ItemStack(Items.COBBLESTONE,64));
        for(int i=0;i<37;i++) tick(original);
        var saved=original.saveWithoutMetadata(server.registryAccess());
        var packet=original.getUpdateTag(server.registryAccess());
        var restored=create(mio_icif_blocks.FURNACE_ELC.get(),0,false,0);
        restored.loadWithComponents(saved,server.registryAccess());
        check(restored.getProgress()==original.getProgress(),"save-roundtrip-progress");
        check(restored.getEnergy()==original.getEnergy(),"save-roundtrip-energy");
        check(restored.getEffectiveCapacity()==original.getEffectiveCapacity(),"save-roundtrip-capacity");
        check(restored.getItem(0).getCount()==original.getItem(0).getCount(),"save-roundtrip-inventory");
        for(int i=0;i<33;i++) { tick(original);tick(restored); }
        check(restored.getItem(2).getCount()==1 && restored.getEnergy()==original.getEnergy(),"save-roundtrip-finishes-same-tick");
        var legacy=create(mio_icif_blocks.FURNACE_ELC.get(),0,false,0);
        var legacyTag=saved.copy();legacyTag.remove("scex_operation_ticks");legacyTag.putInt("progress",50);
        legacy.loadWithComponents(legacyTag,server.registryAccess());
        check(legacy.getProgress()==35,"legacy-base-progress-migration");
        check(legacy.getEnergy()==saved.getLong("energy"),"legacy-upgraded-energy-migration");
        for(int i=0;i<35;i++) tick(legacy);
        check(legacy.getItem(2).getCount()==1,"legacy-finishes-after-remaining-fraction");
        var synced=create(mio_icif_blocks.FURNACE_ELC.get(),0,false,0);
        synced.handleUpdateTag(packet,server.registryAccess());
        check(synced.getProgress()==37 && synced.getMaxProgress()==70,"update-tag-progress-scale");
        check(synced.getEnergy()==packet.getLong("energy"),"update-tag-energy");
        check(synced.getEffectiveCapacity()==original.getEffectiveCapacity(),"update-tag-capacity");
        remove(legacy);remove(synced);
        remove(original); remove(restored);
        var transformer=item("mio_icif:upgrade/transformer_upgrade");
        var voltage=create(mio_icif_blocks.FURNACE_ELC.get(),0,false,0);
        voltage.setItem(3,new ItemStack(transformer));tick(voltage);
        check(voltage.getEffectiveCableTier()==CableTier.MV && voltage.getEffectiveMaxReceive()==128,"transformer-add");
        voltage.setItem(3,ItemStack.EMPTY);tick(voltage);
        check(voltage.getEffectiveCableTier()==CableTier.LV && voltage.getEffectiveMaxReceive()==32,"transformer-remove");
        remove(voltage);
        var capacity=create(mio_icif_blocks.FURNACE_ELC.get(),0,true,1_000_000);
        for(int slot=4;slot<7;slot++) capacity.setItem(slot,ItemStack.EMPTY);
        tick(capacity);
        check(capacity.getEnergy()==600 && capacity.getEffectiveCapacity()==600,"storage-removal-clamps-once");
        remove(capacity);
        System.out.println("SI_BOUNDARY assertions="+assertions+" failed="+failures);
        return new Result(assertions,failures);
    }
}
