// SPDX-License-Identifier: Apache-2.0
// Expectations frozen from IC2 2.8.222-ex112 ordinary save observations, run ic2-overclock-03.
package dev.scex.si;

import com.singularity_iteration.mio_icif.Blocks.mio_icif_blocks;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_furnace_elc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class MachineReferenceProbe {
    private int assertions, failures;
    public record Result(int assertions, int failures) {}
    private void check(boolean ok, String label) {
        assertions++;
        if (!ok) { if (failures < 24) System.out.println("SI_MACHINE_FAIL " + label); failures++; }
    }
    private static final int[] COUNTS = {0,1,2,3,6,8,12,13,14,16,20,24};
    private static final int[] LENGTHS = {100,70,49,34,12,6,1,2,1,1,1,1};
    private static final int[] BATCHES = {1,1,1,1,1,1,1,2,2,4,13,53};
    private static final long[] POWER = {3,5,8,12,50,129,844,1351,2162,5534,36268,237684};

    public Result run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        var overclocker = BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:upgrade/overclocker_upgrade"));
        var storage = BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:upgrade/energy_storage_upgrade"));
        check(overclocker != Items.AIR && storage != Items.AIR, "fixture-registered-upgrades");
        for (int index = 0; index < COUNTS.length; index++) {
            int n = COUNTS[index], length = LENGTHS[index], batch = BATCHES[index];
            BlockPos pos = new BlockPos(index * 3, 80, 8);
            level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(pos, mio_icif_blocks.FURNACE_ELC.get().defaultBlockState());
            var machine = (mio_icif_furnace_elc) level.getBlockEntity(pos);
            check(machine != null, "fixture-real-furnace-" + n);
            if (n > 0) machine.setItem(3, new ItemStack(overclocker, n));
            for (int slot=4; slot<7; slot++) machine.setItem(slot, new ItemStack(storage, 64));
            mio_icif_furnace_elc.tick(level, pos, level.getBlockState(pos), machine);
            machine.getEnergyStorageInternal().setEnergy(1_000_000);
            check(machine.getEnergy() == 1_000_000, "fixture-stored-energy-" + n);
            machine.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            int first = 0;
            int paidTicks = ((64 + batch - 1) / batch) * length;
            for (int tick=1; tick<=200; tick++) {
                // Invoke the registered machine's complete ticker on the real server thread.
                mio_icif_furnace_elc.tick(level, pos, level.getBlockState(pos), machine);
                int expected = Math.min(64, (tick / length) * batch);
                int actual = machine.getOutputItem().getCount();
                if (first == 0 && actual > 0) first = tick;
                String at = "n=" + n + " tick=" + tick;
                check(actual == expected, at + " output=" + actual + " expected=" + expected);
                check(machine.getInputItem().getCount() + actual == 64, at + " item-conservation");
                long energy = 1_000_000 - Math.min(tick, paidTicks) * POWER[index];
                check(machine.getEnergy() == energy, at + " energy=" + machine.getEnergy() + " expected=" + energy);
                check(actual == 0 || machine.getOutputItem().is(Items.STONE), at + " output-item");
            }
            System.out.println("SI_MACHINE_CASE n=" + n + " first=" + first + " expected=" + length
                + " batch=" + batch + " energy=" + machine.getEnergy() + " output=" + machine.getOutputItem().getCount());
            level.removeBlock(pos, false);
        }
        System.out.println("SI_MACHINE assertions=" + assertions + " failed=" + failures + " cases=" + COUNTS.length);
        return new Result(assertions, failures);
    }
}
