// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_miner_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_pump_elc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Actual registered pump behavior and bounded real-world miner handoff. */
public final class PumpProductWorldProbe {
    private static final BlockPos WATER = new BlockPos(1880, 90, 0);
    private static final BlockPos LAVA = new BlockPos(1890, 90, 0);
    private static final BlockPos UNDERFUNDED = new BlockPos(1900, 90, 0);
    private static final BlockPos CANCELED = new BlockPos(1910, 90, 0);
    private static final BlockPos MINER = new BlockPos(1920, 90, 0);
    private static final BlockPos CONTAINER = new BlockPos(1930, 90, 0);
    private static final BlockPos PORT = new BlockPos(1940, 90, 0);
    private int assertions;
    private int canceledEvents;

    public PumpProductWorldProbe() {
        NeoForge.EVENT_BUS.addListener(this::cancelProtectedSource);
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError("R158 pump product " + label);
    }

    private void cancelProtectedSource(BlockEvent.BreakEvent event) {
        if (event.getPos().equals(CANCELED.east())) {
            canceledEvents++;
            event.setCanceled(true);
        }
    }

    private static Block registered(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:" + id));
    }

    private void placePump(ServerLevel world, BlockPos at, long energy) {
        Block block = registered("producer/block_pump_elc");
        var state = block.defaultBlockState();
        var property = block.getStateDefinition().getProperty("facing");
        check(property instanceof DirectionProperty, "registered pump facing property");
        var facing = (DirectionProperty) property;
        check(facing.getPossibleValues().contains(Direction.EAST), "pump accepts east facing");
        world.setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
        check(world.setBlockAndUpdate(at, state.setValue(facing, Direction.EAST)), "registered pump placement " + at);
        var pump = pump(world, at);
        pump.getEnergyStorageInternal().setEnergy(energy);
        check(pump.getEnergyStorageInternal().scexNetworkControlled(), "pump uses independent ledger");
    }

    private void placeMiner(ServerLevel world, BlockPos at) {
        world.setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
        check(world.setBlockAndUpdate(at, registered("producer/block_miner_elc").defaultBlockState()), "registered miner placement");
        check(world.getBlockEntity(at) instanceof mio_icif_miner_elc, "registered miner identity");
    }

    private static mio_icif_pump_elc pump(ServerLevel world, BlockPos at) {
        return (mio_icif_pump_elc) world.getBlockEntity(at);
    }

    private static mio_icif_miner_elc miner(ServerLevel world, BlockPos at) {
        return (mio_icif_miner_elc) world.getBlockEntity(at);
    }

    private void setSource(ServerLevel world, BlockPos at, boolean lava) {
        check(world.setBlockAndUpdate(at.east(), (lava ? Blocks.LAVA : Blocks.WATER).defaultBlockState()),
            "source placement " + at);
    }

    private Map<String, Object> row(ServerLevel world, String label, BlockPos at) {
        var machine = pump(world, at);
        var result = new LinkedHashMap<String, Object>();
        result.put("case", label);
        result.put("energy", machine.getEnergyStorageInternal().getAmount());
        result.put("paid_work", machine.getContainerData().get(0));
        result.put("fluid", BuiltInRegistries.FLUID.getKey(machine.getFluid().getFluid()).toString());
        result.put("fluid_amount", machine.getFluidAmount());
        result.put("source", world.getBlockState(at.east()).getBlock().builtInRegistryHolder().key().location().toString());
        result.put("held_legacy", machine.hasUnmappedLegacy());
        result.put("uncertain_removal", machine.hasUncertainRemoval());
        return result;
    }

    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        if (tick == 5) {
            placePump(world, WATER, 20); setSource(world, WATER, false);
            placePump(world, LAVA, 20); setSource(world, LAVA, true);
            placePump(world, UNDERFUNDED, 19); setSource(world, UNDERFUNDED, false);
            placePump(world, CANCELED, 20); setSource(world, CANCELED, false);

            placeMiner(world, MINER);
            placePump(world, MINER.east(), 20);
            world.setBlockAndUpdate(MINER.below(), Blocks.WATER.defaultBlockState());

            placePump(world, CONTAINER, 20);
            check(pump(world, CONTAINER).getItemHandler().insertItem(mio_icif_pump_elc.SLOT_EMPTY_CONTAINER,
                new ItemStack(Items.BUCKET), false).isEmpty(), "empty bucket inserted");
            check(pump(world, CONTAINER).injectFluid(Fluids.WATER, 1000), "container pump accepts paid compatibility input");

            placePump(world, PORT, 20);
            check(pump(world, PORT).injectFluid(Fluids.WATER, 1000), "port pump accepts paid compatibility input");
            IFluidHandler port = pump(world, PORT).getFluidHandler();
            check(port.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE) == 0,
                "output port rejects fill");
            check(port.drain(250, IFluidHandler.FluidAction.SIMULATE).getAmount() == 250
                && pump(world, PORT).getFluidAmount() == 1000, "simulated drain is non-mutating");
            check(port.drain(250, IFluidHandler.FluidAction.EXECUTE).getAmount() == 250
                && pump(world, PORT).getFluidAmount() == 750, "actual output drain commits exact amount");
        }
        if (tick == 6) {
            check(pump(world, MINER.east()).tryCollectForMiner(miner(world, MINER), MINER.below()),
                "real registered miner requests one atomic pump collection");
            check(world.getBlockState(MINER.below()).isAir(), "miner handoff removes real source");
            check(pump(world, MINER.east()).getFluidAmount() == 1000
                && pump(world, MINER.east()).getEnergyStorageInternal().getAmount() == 0,
                "miner handoff publishes one bucket after exact payment");
        }
        if (tick == 8) {
            var container = pump(world, CONTAINER);
            check(container.getFluidAmount() == 0, "container output drains one bucket from owned tank");
            check(container.getItemHandler().getStackInSlot(mio_icif_pump_elc.SLOT_EMPTY_CONTAINER).isEmpty(),
                "container input consumed");
            check(container.getItemHandler().getStackInSlot(mio_icif_pump_elc.SLOT_OUTPUT).is(Items.WATER_BUCKET),
                "registered pump emits vanilla water bucket");
        }
        if (tick == 15) {
            for (BlockPos at : List.of(WATER, LAVA, UNDERFUNDED, CANCELED)) {
                int expected = at.equals(UNDERFUNDED) ? 10 : 10;
                check(pump(world, at).getContainerData().get(0) == expected, "ten paid natural work steps " + at);
            }
            var saved = pump(world, UNDERFUNDED).saveWithoutMetadata(world.registryAccess());
            check(saved.getCompound("scex_pump_v1").getInt("PaidWork") == 10,
                "mid-cycle paid work is serialized");
        }
        if (tick == 30) {
            check(world.getBlockState(WATER.east()).isAir() && pump(world, WATER).getFluidAmount() == 1000,
                "water source becomes one stored bucket");
            check(pump(world, WATER).getEnergyStorageInternal().getAmount() == 0
                && pump(world, WATER).getContainerData().get(0) == 0, "water cycle exact twenty EU and reset");
            check(world.getBlockState(LAVA.east()).isAir() && pump(world, LAVA).getFluid().getFluid() == Fluids.LAVA
                && pump(world, LAVA).getFluidAmount() == 1000, "lava candidate collects one real source");
            check(world.getBlockState(UNDERFUNDED.east()).is(Blocks.WATER)
                && pump(world, UNDERFUNDED).getContainerData().get(0) == 19
                && pump(world, UNDERFUNDED).getEnergyStorageInternal().getAmount() == 0,
                "nineteen EU cannot remove or publish source");
            check(world.getBlockState(CANCELED.east()).is(Blocks.WATER)
                && pump(world, CANCELED).getFluidAmount() == 0
                && pump(world, CANCELED).getContainerData().get(0) == 20,
                "canceled real break retains paid state and source");
            check(canceledEvents > 0, "actual NeoForge break cancellation observed");
            pump(world, UNDERFUNDED).getEnergyStorageInternal().setEnergy(1);
        }
        if (tick != 34) return null;

        check(world.getBlockState(UNDERFUNDED.east()).isAir()
            && pump(world, UNDERFUNDED).getFluidAmount() == 1000
            && pump(world, UNDERFUNDED).getEnergyStorageInternal().getAmount() == 0,
            "one later EU resumes and completes without replaying nineteen paid steps");
        check(!pump(world, WATER).hasUnmappedLegacy() && !pump(world, WATER).hasUncertainRemoval(),
            "normal completed source has no held state");
        check(pump(world, PORT).getFluidAmount() == 750 && pump(world, PORT).getFluid().getFluid() == Fluids.WATER,
            "capability drain terminal state remains exact");

        var rows = new ArrayList<Map<String, Object>>();
        rows.add(row(world, "water", WATER));
        rows.add(row(world, "lava", LAVA));
        rows.add(row(world, "underfunded-resumed", UNDERFUNDED));
        rows.add(row(world, "protected-canceled", CANCELED));
        rows.add(row(world, "miner-handoff", MINER.east()));
        rows.add(row(world, "container", CONTAINER));
        rows.add(row(world, "output-port", PORT));
        var result = new LinkedHashMap<String, Object>();
        result.put("passed", true);
        result.put("assertions", assertions);
        result.put("rows", rows);
        result.put("canceled_events", canceledEvents);
        result.put("scope", "actual registered pump, vanilla sources, NeoForge break cancellation, container/output capability and bounded real miner handoff");
        result.put("not_claimed", List.of("full natural mining route", "player permission inheritance", "cold restart", "client", "multiplayer", "performance", "production"));
        Files.writeString(Path.of("pump-product-r158-result.json"), new Gson().toJson(result));
        System.out.println("SCEX_PUMP_PRODUCT_R158_PASS assertions=" + assertions);
        return result;
    }
}
