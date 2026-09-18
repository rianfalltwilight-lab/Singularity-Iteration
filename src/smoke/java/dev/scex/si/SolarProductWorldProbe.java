// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.singularity_iteration.mio_icif.Blocks.entity.generator.mio_icif_AdvancedSolarGenerator;
import com.singularity_iteration.mio_icif.Blocks.entity.generator.mio_icif_MetsSolarGeneratorBase;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Generator;
import com.singularity_iteration.mio_icif.api.item.IBatteryItem;
import dev.scex.energy.SolarGeneratorProfile;
import dev.scex.si.energy.IndependentSiEnergy;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Natural registered-world acceptance for the seven opt-in extended-solar machines. */
public final class SolarProductWorldProbe {
    private static final List<SolarGeneratorProfile> PROFILES = SolarGeneratorProfile.all();
    private static final int BASE_X = 2048;
    private final List<mio_icif_Energy_Generator> machines = new ArrayList<>();
    private final List<Long> sourceBeforeCharge = new ArrayList<>();
    private final List<Integer> chargeSlots = new ArrayList<>();
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private int assertions;

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError("R159 solar product " + label);
    }

    private static BlockPos at(int index) { return new BlockPos(BASE_X + index * 12, 90, 0); }

    private static ResourceLocation id(String value) { return ResourceLocation.parse(value); }

    private long expected(SolarGeneratorProfile profile, mio_icif_Energy_Generator machine) {
        if (profile.discrete()) return profile.dayPower();
        var mets = (mio_icif_MetsSolarGeneratorBase) machine;
        return profile.scaledOutput(mets.getSkyLight());
    }

    private long batteryEnergy(ItemStack stack) {
        check(stack.getItem() instanceof IBatteryItem, "actual SI battery remains in slot");
        return ((IBatteryItem) stack.getItem()).getEnergy(stack);
    }

    private ItemStack emptyBattery() {
        var item = BuiltInRegistries.ITEM.get(id("mio_icif:normal/item_bat_lev0"));
        check(item != Items.AIR && item instanceof IBatteryItem, "actual registered SI battery");
        var stack = new ItemStack(item);
        ((IBatteryItem) item).setEnergy(stack, 0);
        check(((IBatteryItem) item).getEnergy(stack) == 0, "explicit empty battery state");
        return stack;
    }

    private void setup(ServerLevel world) {
        check(PROFILES.size() == 7, "seven frozen solar profiles");
        for (int index = 0; index < PROFILES.size(); index++) {
            var profile = PROFILES.get(index);
            var pos = at(index);
            var block = BuiltInRegistries.BLOCK.get(id(profile.registryId()));
            check(block != Blocks.AIR, "registered block " + profile.registryId());
            check(world.getBlockState(pos).isAir() && world.getBlockState(pos.above()).isAir(),
                "empty isolated solar cells " + pos);
            check(world.setBlockAndUpdate(pos, block.defaultBlockState()), "place " + profile.registryId());
            check(world.getBlockEntity(pos) instanceof mio_icif_Energy_Generator,
                "registered energy block entity " + profile.registryId());
            var machine = (mio_icif_Energy_Generator) world.getBlockEntity(pos);
            check(BuiltInRegistries.BLOCK.getKey(machine.getBlockState().getBlock()).equals(id(profile.registryId())),
                "exact registered identity " + profile.registryId());
            check(profile.discrete() ? machine instanceof mio_icif_AdvancedSolarGenerator
                    : machine instanceof mio_icif_MetsSolarGeneratorBase,
                "expected solar family " + profile.registryId());
            check(machine.getEnergyStorageInternal().scexNetworkControlled()
                    && IndependentSiEnergy.controls(machine.getBlockState()),
                "single independent owner " + profile.registryId());
            check(machine.getEnergyStorageInternal().getCapacity() == profile.capacity(),
                "profile capacity " + profile.registryId());
            check(machine.getEnergyStorageInternal().getMaxReceive() == 0
                    && machine.getEnergyStorageInternal().getMaxExtract() == profile.outputPacket(),
                "profile transfer limits " + profile.registryId());
            check(machine.getItemHandler().getSlots() == profile.chargeSlots(),
                "four charging slots " + profile.registryId());
            check(machine.getEnergyStorageInternal().getAmount() == 0,
                "fresh machine begins empty " + profile.registryId());
            machines.add(machine);
        }
    }

    private void firstNaturalTick() {
        for (int index = 0; index < PROFILES.size(); index++) {
            var profile = PROFILES.get(index);
            var machine = machines.get(index);
            long generated = expected(profile, machine);
            check(generated > 0 && machine.getEnergyStorageInternal().getAmount() == generated,
                "first natural daylight generation " + profile.registryId());
            if (profile.discrete()) {
                var asp = (mio_icif_AdvancedSolarGenerator) machine;
                check(asp.isGenerating() && asp.getGenerationState() == mio_icif_AdvancedSolarGenerator.GenerationState.DAY,
                    "ASP daylight state " + profile.registryId());
            } else {
                var mets = (mio_icif_MetsSolarGeneratorBase) machine;
                check(mets.isGenerating() && mets.getSkyLight() > 0 && mets.getSkyLight() <= 1,
                    "MET daylight state " + profile.registryId());
            }
        }
    }

    private void insertBatteries() {
        for (int index = 0; index < PROFILES.size(); index++) {
            var machine = machines.get(index);
            int slot = index % PROFILES.get(index).chargeSlots();
            var remainder = machine.getItemHandler().insertItem(slot, emptyBattery(), false);
            check(remainder.isEmpty(), "battery accepted in slot " + slot + " for " + PROFILES.get(index).registryId());
            sourceBeforeCharge.add(machine.getEnergyStorageInternal().getAmount());
            chargeSlots.add(slot);
        }
    }

    private void verifyChargingAndRoof(ServerLevel world) {
        for (int index = 0; index < PROFILES.size(); index++) {
            var profile = PROFILES.get(index);
            var machine = machines.get(index);
            int slot = chargeSlots.get(index);
            var charged = machine.getItemHandler().getStackInSlot(slot);
            long itemGain = batteryEnergy(charged);
            long sourceAfter = machine.getEnergyStorageInternal().getAmount();
            long generated = expected(profile, machine);
            check(itemGain > 0 && sourceAfter + itemGain - sourceBeforeCharge.get(index) == generated,
                "one-tick source and battery conservation " + profile.registryId());
            var removed = machine.getItemHandler().extractItem(slot, 1, false);
            check(!removed.isEmpty() && machine.getItemHandler().getStackInSlot(slot).isEmpty(),
                "charged battery extracted through public handler " + profile.registryId());
            machine.getEnergyStorageInternal().setEnergy(0);
            check(world.setBlockAndUpdate(at(index).above(), Blocks.STONE.defaultBlockState()),
                "opaque roof placement " + profile.registryId());
            var row = new LinkedHashMap<String, Object>();
            row.put("id", profile.registryId());
            row.put("family", profile.discrete() ? "ASP" : "MET");
            row.put("capacity", profile.capacity());
            row.put("output_packet", profile.outputPacket());
            row.put("charge_slots", profile.chargeSlots());
            row.put("charge_slot_tested", slot);
            row.put("charge_gain", itemGain);
            row.put("day_generation", generated);
            row.put("refresh_ticks", profile.refreshTicks());
            rows.add(row);
        }
    }

    private void verifyBlocked(ServerLevel world) {
        for (int index = 0; index < PROFILES.size(); index++) {
            var profile = PROFILES.get(index);
            var machine = machines.get(index);
            check(machine.getEnergyStorageInternal().getAmount() >= 0, "valid blocked balance " + profile.registryId());
            if (profile.discrete()) {
                var asp = (mio_icif_AdvancedSolarGenerator) machine;
                check(!asp.isGenerating() && asp.getGenerationState() == mio_icif_AdvancedSolarGenerator.GenerationState.NONE,
                    "ASP roof stop " + profile.registryId());
            } else {
                var mets = (mio_icif_MetsSolarGeneratorBase) machine;
                check(!mets.isGenerating() && mets.getSkyLight() == 0,
                    "MET roof stop after natural refresh " + profile.registryId());
            }
            machine.getEnergyStorageInternal().setEnergy(0);
        }
    }

    private void verifyBlockedZeroAndOpen(ServerLevel world) {
        for (int index = 0; index < PROFILES.size(); index++) {
            check(machines.get(index).getEnergyStorageInternal().getAmount() == 0,
                "blocked machine remains zero " + PROFILES.get(index).registryId());
            check(world.removeBlock(at(index).above(), false), "remove roof " + PROFILES.get(index).registryId());
        }
    }

    private void verifyRecoveredAndReset() {
        for (int index = 0; index < PROFILES.size(); index++) {
            var profile = PROFILES.get(index);
            var machine = machines.get(index);
            check(expected(profile, machine) > 0 && machine.getEnergyStorageInternal().getAmount() > 0,
                "natural generation resumes after open sky " + profile.registryId());
            check(profile.discrete() ? ((mio_icif_AdvancedSolarGenerator) machine).isGenerating()
                    : ((mio_icif_MetsSolarGeneratorBase) machine).isGenerating(),
                "generating state resumes " + profile.registryId());
            machine.getEnergyStorageInternal().setEnergy(0);
        }
    }

    private void verifyRecoveredTickAndSave(ServerLevel world) {
        for (int index = 0; index < PROFILES.size(); index++) {
            var profile = PROFILES.get(index);
            var machine = machines.get(index);
            long generated = expected(profile, machine);
            check(generated > 0 && machine.getEnergyStorageInternal().getAmount() == generated,
                "recovered one-tick generation " + profile.registryId());
            var saved = machine.saveWithFullMetadata(world.registryAccess());
            var copy = BlockEntity.loadStatic(machine.getBlockPos(), machine.getBlockState(), saved, world.registryAccess());
            check(copy != null && copy != machine && copy.getClass() == machine.getClass() && copy.getType() == machine.getType(),
                "saved factory identity " + profile.registryId());
            var restored = (mio_icif_Energy_Block) copy;
            check(restored.getEnergyStorageInternal() != machine.getEnergyStorageInternal()
                    && restored.getEnergyStorageInternal().scexNetworkControlled()
                    && restored.getEnergyStorageInternal().scexExactAmount().equals(machine.getEnergyStorageInternal().scexExactAmount()),
                "saved independent balance " + profile.registryId());
            if (profile.discrete()) {
                check(((mio_icif_AdvancedSolarGenerator) restored).getGenerationState()
                        == ((mio_icif_AdvancedSolarGenerator) machine).getGenerationState(),
                    "ASP saved generation state " + profile.registryId());
            } else {
                check(((mio_icif_MetsSolarGeneratorBase) restored).getSkyLight() == 0,
                    "MET reload distrusts saved light " + profile.registryId());
            }
            rows.get(index).put("recovered_generation", generated);
            rows.get(index).put("saved_class", copy.getClass().getName());
        }
    }

    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        if (tick == 5) setup(world);
        if (tick == 6) firstNaturalTick();
        if (tick == 7) insertBatteries();
        if (tick == 8) verifyChargingAndRoof(world);
        if (tick == 150) verifyBlocked(world);
        if (tick == 151) verifyBlockedZeroAndOpen(world);
        if (tick == 280) verifyRecoveredAndReset();
        if (tick != 281) return null;

        verifyRecoveredTickAndSave(world);
        var result = new LinkedHashMap<String, Object>();
        result.put("passed", true);
        result.put("assertions", assertions);
        result.put("rows", rows);
        result.put("scope", "seven registered extended-solar machines: natural daylight, four-slot item charging, roof refresh, recovery and saved identity");
        result.put("not_claimed", List.of("reference parity beyond frozen profiles", "cold JVM restart", "client", "multiplayer", "performance", "production"));
        Files.writeString(Path.of("solar-product-r159-result.json"), new Gson().toJson(result));
        System.out.println("SCEX_SOLAR_PRODUCT_R159_PASS assertions=" + assertions);
        return result;
    }
}
