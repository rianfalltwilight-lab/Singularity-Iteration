// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_matron_elc;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Focused F06 product smoke for Matron water reservation and cell conversion. */
public final class MatronHydrationWorldProbe {
    private static final BlockPos MATRON = new BlockPos(2700, 90, 0);
    private static final List<BlockPos> FARMLAND = List.of(
        MATRON.west(), MATRON.east(), MATRON.north(), MATRON.south());
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private int assertions;

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError("F06 matron hydration " + label);
    }

    private static int moisture(ServerLevel world, BlockPos pos) {
        return world.getBlockState(pos).getValue(FarmBlock.MOISTURE);
    }

    private static mio_icif_matron_elc matron(ServerLevel world) {
        return (mio_icif_matron_elc) world.getBlockEntity(MATRON);
    }

    private void setup(ServerLevel world) {
        world.setBlockAndUpdate(MATRON.below(), Blocks.STONE.defaultBlockState());
        check(world.setBlockAndUpdate(MATRON,
            net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .get(net.minecraft.resources.ResourceLocation.parse("mio_icif:producer/block_matron_elc"))
                .defaultBlockState()), "registered matron placement");
        check(matron(world) != null, "registered matron block entity");
        for (BlockPos pos : FARMLAND) {
            world.setBlockAndUpdate(pos.below(), Blocks.DIRT.defaultBlockState());
            world.setBlockAndUpdate(pos, Blocks.FARMLAND.defaultBlockState()
                .setValue(FarmBlock.MOISTURE, 0));
        }
    }

    private void setWater(ServerLevel world, int amount) {
        var tank = matron(world).getFluidHandlerCapability(null);
        tank.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
        check(tank.fill(new FluidStack(Fluids.WATER, amount), IFluidHandler.FluidAction.EXECUTE) == amount,
            "water fill exact " + amount);
    }

    private int invokeHydrate(ServerLevel world) throws Exception {
        Method method = mio_icif_matron_elc.class.getDeclaredMethod(
            "tryHydrateFarmland", net.minecraft.world.level.Level.class, BlockPos.class);
        method.setAccessible(true);
        check((boolean) method.invoke(matron(world), world, MATRON), "hydration reports changed farmland");
        return FARMLAND.stream().mapToInt(pos -> moisture(world, pos) == 7 ? 1 : 0).sum();
    }

    private void resetFarmland(ServerLevel world) {
        for (BlockPos pos : FARMLAND) {
            world.setBlockAndUpdate(pos, Blocks.FARMLAND.defaultBlockState()
                .setValue(FarmBlock.MOISTURE, 0));
        }
    }

    private void caseReservation(ServerLevel world, int water, int expectedWet, int expectedLeft,
        String label) throws Exception {
        resetFarmland(world);
        setWater(world, water);
        int wet = invokeHydrate(world);
        int left = matron(world).getFluidHandlerCapability(null).getFluidInTank(0).getAmount();
        check(wet == expectedWet, label + " wets exactly floor(water/10) bounded by fields");
        check(left == expectedLeft, label + " preserves exact remainder");
        rows.add(Map.of("case", label, "input", water, "wet", wet, "remaining", left));
    }

    private void caseNoWater(ServerLevel world) throws Exception {
        resetFarmland(world);
        setWater(world, 9);
        int before = FARMLAND.stream().mapToInt(pos -> moisture(world, pos)).sum();
        Method method = mio_icif_matron_elc.class.getDeclaredMethod(
            "tryHydrateFarmland", net.minecraft.world.level.Level.class, BlockPos.class);
        method.setAccessible(true);
        check(!(boolean) method.invoke(matron(world), world, MATRON), "sub-unit water does no work");
        check(FARMLAND.stream().mapToInt(pos -> moisture(world, pos)).sum() == before,
            "sub-unit water leaves farmland unchanged");
        check(matron(world).getFluidHandlerCapability(null).getFluidInTank(0).getAmount() == 9,
            "sub-unit water is not consumed");
        rows.add(Map.of("case", "nine-mb-no-op", "input", 9, "wet", 0, "remaining", 9));
    }

    private void caseWaterCell(ServerLevel world) throws Exception {
        resetFarmland(world);
        setWater(world, 0);
        var machine = matron(world);
        check(machine.getItemHandler().insertItem(mio_icif_matron_elc.SLOT_WATER_CELL_INPUT,
            new ItemStack(Items.WATER_BUCKET), false).isEmpty(), "water bucket input accepted");
        Method method = mio_icif_matron_elc.class.getDeclaredMethod("processWaterCell");
        method.setAccessible(true);
        method.invoke(machine);
        check(machine.getFluidHandlerCapability(null).getFluidInTank(0).getAmount() == 1000,
            "water bucket converts to one thousand mB");
        check(machine.getItemHandler().getStackInSlot(mio_icif_matron_elc.SLOT_WATER_CELL_INPUT).isEmpty(),
            "water bucket input consumed");
        check(machine.getItemHandler().getStackInSlot(mio_icif_matron_elc.SLOT_EMPTY_CELL_OUTPUT).is(Items.BUCKET),
            "empty bucket output emitted");
        rows.add(Map.of("case", "water-bucket-cell", "tank", 1000, "output", "minecraft:bucket"));
    }

    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        if (tick != 5) return null;
        setup(world);
        caseReservation(world, 10, 1, 0, "ten-mb-one-field");
        caseReservation(world, 25, 2, 5, "twenty-five-mb-four-fields");
        caseNoWater(world);
        caseWaterCell(world);
        var result = new LinkedHashMap<String, Object>();
        result.put("passed", true);
        result.put("assertions", assertions);
        result.put("rows", rows);
        result.put("scope", "registered Matron farmland hydration reservation, exact remainder, no-op threshold and water-cell conversion");
        result.put("not_claimed", List.of("long growth or hybridization", "client", "multiplayer", "performance", "cold restart", "production"));
        Files.writeString(Path.of("matron-hydration-f06-result.json"), new Gson().toJson(result));
        System.out.println("SCEX_MATRON_HYDRATION_F06_PASS assertions=" + assertions);
        return result;
    }
}
