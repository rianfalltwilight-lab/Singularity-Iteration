// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_advanced_miner_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_miner_elc;
import com.singularity_iteration.mio_icif.api.MioIcifAPI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Natural registered-world acceptance for ordinary and advanced miners. */
public final class MiningProductWorldProbe {
    private static final BlockPos NORMAL = new BlockPos(2200, 90, 0);
    private static final BlockPos UNDERFUNDED = new BlockPos(2210, 90, 0);
    private static final BlockPos BREAK_CANCELED = new BlockPos(2220, 90, 0);
    private static final BlockPos PLACE_CANCELED = new BlockPos(2230, 90, 0);
    private static final BlockPos ADVANCED = new BlockPos(2250, 90, 0);
    private static final BlockPos ADVANCED_SILK = new BlockPos(2260, 90, 0);
    private static final BlockPos ADVANCED_WHITELIST = new BlockPos(2270, 90, 0);
    private static final BlockPos ADVANCED_BLACKLIST = new BlockPos(2280, 90, 0);
    private static final BlockPos ADVANCED_CANCELED = new BlockPos(2290, 90, 0);
    private boolean cancelPlacement = true;
    private int assertions;
    private int canceledBreaks;
    private int canceledPlacements;
    private long underfundedCost;

    public MiningProductWorldProbe() {
        NeoForge.EVENT_BUS.addListener(this::cancelProtectedBreak);
        NeoForge.EVENT_BUS.addListener(this::cancelProtectedPlacement);
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError("R168 mining product " + label);
    }

    private void cancelProtectedBreak(BlockEvent.BreakEvent event) {
        if (event.getPos().equals(BREAK_CANCELED.below()) || event.getPos().equals(ADVANCED_CANCELED.below())) {
            check(event.getPlayer().getGameProfile().getName().equals("[SI Miner]"), "protected break uses SI miner identity");
            canceledBreaks++;
            event.setCanceled(true);
        }
    }

    private void cancelProtectedPlacement(BlockEvent.EntityPlaceEvent event) {
        if (cancelPlacement && event.getPos().equals(PLACE_CANCELED.below())) {
            check(event.getEntity() != null && event.getEntity().getName().getString().equals("[SI Miner]"),
                "protected placement uses SI miner identity");
            canceledPlacements++;
            event.setCanceled(true);
        }
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:" + id));
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:" + id));
    }

    private static mio_icif_miner_elc normal(ServerLevel world, BlockPos pos) {
        return (mio_icif_miner_elc) world.getBlockEntity(pos);
    }

    private static mio_icif_advanced_miner_elc advanced(ServerLevel world, BlockPos pos) {
        return (mio_icif_advanced_miner_elc) world.getBlockEntity(pos);
    }

    private ItemStack fullTool(String id) {
        var stack = new ItemStack(item(id));
        var api = MioIcifAPI.instance().getItemAPI();
        check(api.isElectricTool(stack), "registered electric tool " + id);
        api.setElectricToolEnergy(stack, api.getElectricToolMaxEnergy(stack));
        check(api.getElectricToolStored(stack) == api.getElectricToolMaxEnergy(stack), "full tool " + id);
        return stack;
    }

    private void insert(mio_icif_miner_elc machine, int slot, ItemStack stack, String label) {
        check(machine.getItemHandler().insertItem(slot, stack, false).isEmpty(), label);
    }

    private void insert(mio_icif_advanced_miner_elc machine, int slot, ItemStack stack, String label) {
        check(machine.getItemHandler().insertItem(slot, stack, false).isEmpty(), label);
    }

    private void placeNormal(ServerLevel world, BlockPos pos, long energy, BlockStateSetup below) {
        check(block("producer/block_miner_elc") != Blocks.AIR, "registered normal miner block");
        below.apply(world, pos.below());
        check(world.setBlockAndUpdate(pos, block("producer/block_miner_elc").defaultBlockState()), "place normal miner " + pos);
        check(world.getBlockEntity(pos) instanceof mio_icif_miner_elc, "normal miner identity " + pos);
        var machine = normal(world, pos);
        machine.getEnergyStorageInternal().setEnergy(energy);
        insert(machine, mio_icif_miner_elc.SLOT_DRILL, fullTool("item_tool_iron_driller"), "normal drill accepted " + pos);
        insert(machine, mio_icif_miner_elc.SLOT_SCANNER, fullTool("item_tool_od_scanner"), "normal scanner accepted " + pos);
        insert(machine, mio_icif_miner_elc.SLOT_PIPE,
            new ItemStack(block("produce/block_mining_pipe").asItem(), 8), "normal pipes accepted " + pos);
    }

    private void placeAdvanced(ServerLevel world, BlockPos pos, Block below) {
        check(world.setBlockAndUpdate(pos.below(), below.defaultBlockState()), "advanced target " + pos);
        check(world.setBlockAndUpdate(pos, block("producer/block_advanced_miner_elc").defaultBlockState()),
            "place advanced miner " + pos);
        check(world.getBlockEntity(pos) instanceof mio_icif_advanced_miner_elc, "advanced miner identity " + pos);
        check(world.setBlockAndUpdate(pos.east(), Blocks.CHEST.defaultBlockState()), "place advanced output chest " + pos);
        var machine = advanced(world, pos);
        machine.getEnergyStorageInternal().setEnergy(2000);
        insert(machine, mio_icif_advanced_miner_elc.SLOT_SCANNER, fullTool("item_tool_od_scanner"),
            "advanced scanner accepted " + pos);
    }

    private int stored(mio_icif_miner_elc machine, Item item) {
        int count = 0;
        for (int slot = mio_icif_miner_elc.SLOT_STORAGE_START; slot < mio_icif_miner_elc.SLOT_STORAGE_END; slot++) {
            var stack = machine.getItemHandler().getStackInSlot(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private int chest(ServerLevel world, BlockPos machine, Item item) {
        var chest = (ChestBlockEntity) world.getBlockEntity(machine.east());
        int count = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) if (chest.getItem(slot).is(item)) count += chest.getItem(slot).getCount();
        return count;
    }

    private long toolEnergy(ItemStack stack) {
        return MioIcifAPI.instance().getItemAPI().getElectricToolStored(stack);
    }

    private void setup(ServerLevel world) {
        placeNormal(world, NORMAL, 2000, (level, at) -> {
            check(level.setBlockAndUpdate(at, Blocks.STONE.defaultBlockState()), "normal first stone");
            check(level.setBlockAndUpdate(at.east(2), Blocks.COAL_ORE.defaultBlockState()), "normal layer coal");
        });
        placeNormal(world, UNDERFUNDED, 0, (level, at) ->
            check(level.setBlockAndUpdate(at, Blocks.STONE.defaultBlockState()), "underfunded stone"));
        placeNormal(world, BREAK_CANCELED, 2000, (level, at) ->
            check(level.setBlockAndUpdate(at, Blocks.STONE.defaultBlockState()), "protected stone"));
        placeNormal(world, PLACE_CANCELED, 2000, (level, at) -> {
            level.removeBlock(at, false);
            check(level.getBlockState(at).isAir(), "placement target air");
        });

        placeAdvanced(world, ADVANCED, Blocks.COAL_ORE);
        placeAdvanced(world, ADVANCED_SILK, Blocks.STONE);
        advanced(world, ADVANCED_SILK).setSilkTouchMode(true);
        placeAdvanced(world, ADVANCED_WHITELIST, Blocks.IRON_ORE);
        advanced(world, ADVANCED_WHITELIST).setFilterStack(0, new ItemStack(Blocks.IRON_ORE));
        advanced(world, ADVANCED_WHITELIST).setWhitelistMode(true);
        placeAdvanced(world, ADVANCED_BLACKLIST, Blocks.IRON_ORE);
        advanced(world, ADVANCED_BLACKLIST).setFilterStack(0, new ItemStack(Blocks.IRON_ORE));
        placeAdvanced(world, ADVANCED_CANCELED, Blocks.DIAMOND_ORE);
    }

    private void releaseHeldCases(ServerLevel world) {
        var machine = normal(world, UNDERFUNDED);
        var saved = machine.saveWithoutMetadata(world.registryAccess());
        underfundedCost = saved.getCompound("scex_mining_route").getLong("cost");
        check(underfundedCost >= mio_icif_miner_elc.ENERGY_IRON_DRILL_MIN + mio_icif_miner_elc.ENERGY_OD_SCANNER_MIN
                && underfundedCost <= mio_icif_miner_elc.ENERGY_IRON_DRILL_MAX + mio_icif_miner_elc.ENERGY_OD_SCANNER_MAX,
            "underfunded route freezes bounded random cost");
        check(saved.getLong("scex_mining_cost") == underfundedCost, "route and machine retain one exact quote");
        check(world.getBlockState(UNDERFUNDED.below()).is(Blocks.STONE)
                && machine.getEnergyStorageInternal().getAmount() == 0
                && machine.getItemHandler().getStackInSlot(mio_icif_miner_elc.SLOT_PIPE).getCount() == 8,
            "underfunded route changes neither world, energy nor pipe");

        var protectedMachine = normal(world, BREAK_CANCELED);
        check(world.getBlockState(BREAK_CANCELED.below()).is(Blocks.STONE)
                && protectedMachine.getEnergyStorageInternal().getAmount() == 2000
                && protectedMachine.getItemHandler().getStackInSlot(mio_icif_miner_elc.SLOT_PIPE).getCount() == 8,
            "canceled break changes neither world, payment nor pipe");
        check(canceledBreaks > 0, "NeoForge break cancellation reached real miner");

        var placementMachine = normal(world, PLACE_CANCELED);
        var placementSave = placementMachine.saveWithoutMetadata(world.registryAccess()).getCompound("scex_pipe_advance");
        check(world.getBlockState(PLACE_CANCELED.below()).isAir() && placementSave.getInt("phase") == 1
                && placementSave.contains("reserved")
                && placementMachine.getItemHandler().getStackInSlot(mio_icif_miner_elc.SLOT_PIPE).getCount() == 7,
            "canceled placement retains one reserved pipe without world mutation");
        check(canceledPlacements > 0, "NeoForge placement cancellation reached real miner");
        machine.getEnergyStorageInternal().setEnergy(underfundedCost);
        cancelPlacement = false;
    }

    private void verify(ServerLevel world) {
        var normal = normal(world, NORMAL);
        long normalSpent = 2000 - normal.getEnergyStorageInternal().getAmount();
        check(world.getBlockState(NORMAL.below()).is(block("produce/block_mining_tip")), "normal miner places real tip");
        check(world.getBlockState(NORMAL.below().east(2)).isAir(), "normal miner follows layer route to ore");
        check(normal.getItemHandler().getStackInSlot(mio_icif_miner_elc.SLOT_PIPE).getCount() == 7,
            "normal miner consumes exactly one pipe for first descent");
        check(stored(normal, Items.COBBLESTONE) == 1 && stored(normal, Items.COAL) == 1,
            "normal miner captures exact first stone and coal drops");
        check(normalSpent >= 992 && normalSpent <= 1092, "two route quotes plus two tool recharges are bounded");
        check(toolEnergy(normal.getItemHandler().getStackInSlot(mio_icif_miner_elc.SLOT_DRILL))
                == MioIcifAPI.instance().getItemAPI().getElectricToolMaxEnergy(normal.getItemHandler().getStackInSlot(mio_icif_miner_elc.SLOT_DRILL)),
            "funded normal miner recharges drill from owned machine energy");

        var resumed = normal(world, UNDERFUNDED);
        check(world.getBlockState(UNDERFUNDED.below()).is(block("produce/block_mining_tip"))
                && resumed.getEnergyStorageInternal().getAmount() == 0
                && resumed.getItemHandler().getStackInSlot(mio_icif_miner_elc.SLOT_PIPE).getCount() == 7,
            "exact later funding resumes held route once");
        check(stored(resumed, Items.COBBLESTONE) == 1, "resumed route publishes one captured drop");
        check(toolEnergy(resumed.getItemHandler().getStackInSlot(mio_icif_miner_elc.SLOT_DRILL))
                + 1 == MioIcifAPI.instance().getItemAPI().getElectricToolMaxEnergy(resumed.getItemHandler().getStackInSlot(mio_icif_miner_elc.SLOT_DRILL)),
            "no extra machine energy means exact one-EU drill debit remains visible");

        var placed = normal(world, PLACE_CANCELED);
        check(world.getBlockState(PLACE_CANCELED.below()).is(block("produce/block_mining_tip"))
                && placed.getItemHandler().getStackInSlot(mio_icif_miner_elc.SLOT_PIPE).getCount() == 7
                && placed.saveWithoutMetadata(world.registryAccess()).getCompound("scex_pipe_advance").getInt("phase") == 0,
            "released placement resumes retained pipe exactly once");

        check(chest(world, ADVANCED, Items.COAL) == 1
                && advanced(world, ADVANCED).getEnergyStorageInternal().getAmount() == 1424,
            "advanced miner emits coal after exact 512 plus 64 recharge payment");
        check(chest(world, ADVANCED_SILK, Items.STONE) == 1
                && advanced(world, ADVANCED_SILK).getEnergyStorageInternal().getAmount() == 1424,
            "advanced silk mode emits the block itself with exact payment");
        check(chest(world, ADVANCED_WHITELIST, Items.RAW_IRON) == 1
                && advanced(world, ADVANCED_WHITELIST).getEnergyStorageInternal().getAmount() == 1424,
            "advanced whitelist admits matching ore");
        check(world.getBlockState(ADVANCED_BLACKLIST.below()).is(Blocks.IRON_ORE)
                && chest(world, ADVANCED_BLACKLIST, Items.RAW_IRON) == 0
                && advanced(world, ADVANCED_BLACKLIST).getEnergyStorageInternal().getAmount() == 2000,
            "advanced blacklist skips matching ore without payment");
        check(world.getBlockState(ADVANCED_CANCELED.below()).is(Blocks.DIAMOND_ORE)
                && chest(world, ADVANCED_CANCELED, Items.DIAMOND) == 0
                && advanced(world, ADVANCED_CANCELED).getEnergyStorageInternal().getAmount() == 2000,
            "advanced protected ore stays untouched and unpaid");
        check(canceledBreaks >= 2, "both normal and advanced paths emit protected break events");
        for (BlockPos pos : List.of(NORMAL, UNDERFUNDED, BREAK_CANCELED, PLACE_CANCELED,
                ADVANCED, ADVANCED_SILK, ADVANCED_WHITELIST, ADVANCED_BLACKLIST, ADVANCED_CANCELED)) {
            check(world.setBlockAndUpdate(pos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState()), "stop fixture machine " + pos);
        }
    }

    private Map<String, Object> row(ServerLevel world, String label, BlockPos pos) {
        var row = new LinkedHashMap<String, Object>();
        row.put("case", label);
        row.put("block", BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos).getBlock()).toString());
        row.put("below", BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos.below()).getBlock()).toString());
        if (world.getBlockEntity(pos) instanceof mio_icif_miner_elc machine) {
            row.put("energy", machine.getEnergyStorageInternal().getAmount());
            row.put("pipes", machine.getItemHandler().getStackInSlot(mio_icif_miner_elc.SLOT_PIPE).getCount());
        } else if (world.getBlockEntity(pos) instanceof mio_icif_advanced_miner_elc machine) {
            row.put("energy", machine.getEnergyStorageInternal().getAmount());
            row.put("depth", machine.getCurrentDepth());
            row.put("whitelist", machine.isWhitelistMode());
            row.put("silk", machine.isSilkTouchMode());
        }
        return row;
    }

    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        if (tick == 5) setup(world);
        if (tick == 30) releaseHeldCases(world);
        if (tick == 50) verify(world);
        if (tick != 55) return null;
        var rows = new ArrayList<Map<String, Object>>();
        rows.add(row(world, "normal-route", NORMAL));
        rows.add(row(world, "underfunded-resume", UNDERFUNDED));
        rows.add(row(world, "break-canceled", BREAK_CANCELED));
        rows.add(row(world, "place-canceled-resume", PLACE_CANCELED));
        rows.add(row(world, "advanced-default", ADVANCED));
        rows.add(row(world, "advanced-silk", ADVANCED_SILK));
        rows.add(row(world, "advanced-whitelist", ADVANCED_WHITELIST));
        rows.add(row(world, "advanced-blacklist", ADVANCED_BLACKLIST));
        rows.add(row(world, "advanced-break-canceled", ADVANCED_CANCELED));
        var result = new LinkedHashMap<String, Object>();
        result.put("passed", true);
        result.put("assertions", assertions);
        result.put("rows", rows);
        result.put("underfunded_cost", underfundedCost);
        result.put("canceled_breaks", canceledBreaks);
        result.put("canceled_placements", canceledPlacements);
        result.put("scope", "registered ordinary and advanced miners: natural route, tools, pipe custody, exact payment, filters, silk and NeoForge break/place cancellation");
        result.put("not_claimed", List.of("player-owner permission inheritance", "fluid layer handoff beyond R163", "missing chunk resume", "cold restart", "client", "multiplayer", "performance", "production"));
        Files.writeString(Path.of("mining-product-r168-result.json"), new Gson().toJson(result));
        System.out.println("SCEX_MINING_PRODUCT_R168_PASS assertions=" + assertions);
        return result;
    }

    @FunctionalInterface
    private interface BlockStateSetup {
        void apply(ServerLevel world, BlockPos pos);
    }
}
