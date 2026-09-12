// SPDX-License-Identifier: Apache-2.0
// SCEX 2026-09-12: real registry and inventory integration, never a production mod.
package dev.scex.si;

import com.singularity_iteration.mio_icif.Items.Upgrade.MachineUpgradeStats;
import com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.items.ItemStackHandler;

@Mod("scex_si_smoke")
public final class SmokeProbe {
    private int assertions;
    public SmokeProbe() { NeoForge.EVENT_BUS.addListener(this::started); }
    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }
    private void checkOptionalCurios(net.minecraft.server.MinecraftServer server) {
        var normal=BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:armor/item_armor_jetpack_electric"));
        var advanced=BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:armor/item_armor_advanced_jetpack"));
        check(normal!=Items.AIR,"normal-jetpack-always-registered");
        check(advanced!=Items.AIR,"advanced-jetpack-always-registered");
        check(normal instanceof com.singularity_iteration.mio_icif.api.item.IBackSlotItem
            && advanced instanceof com.singularity_iteration.mio_icif.api.item.IBackSlotItem,"independent-back-slot-api");
        if(net.neoforged.fml.ModList.get().isLoaded("curios")) assertions+=CuriosProbe.run(server);
    }
    private void started(ServerStartedEvent event) {
        boolean passed = false;
        try {
            if ("compat".equals(System.getProperty("scex.smoke.mode"))) {
                checkOptionalCurios(event.getServer());
                passed=true;
                return;
            }
            if ("boundaries".equals(System.getProperty("scex.smoke.mode"))) {
                var result = new MachineBoundaryProbe().run(event.getServer());
                assertions += result.assertions();
                if (result.failures() != 0) throw new AssertionError("Machine boundary differences: " + result.failures());
                checkOptionalCurios(event.getServer());
                passed = true;
                return;
            }
            if ("machine".equals(System.getProperty("scex.smoke.mode"))) {
                var result = new MachineReferenceProbe().run(event.getServer());
                assertions += result.assertions();
                if (result.failures() != 0) throw new AssertionError("Machine reference differences: " + result.failures());
                passed = true;
                return;
            }
            var overclocker = BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:upgrade/overclocker_upgrade"));
            var transformer = BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:upgrade/transformer_upgrade"));
            check(overclocker != Items.AIR && transformer != Items.AIR, "registered-upgrade-items");
            var inventory = new ItemStackHandler(4);
            inventory.setStackInSlot(0, new ItemStack(overclocker, 2));
            inventory.setStackInSlot(1, new ItemStack(transformer));
            var stats = MachineUpgradeStats.fromInventory(inventory, 0, 4);
            check(stats.getOverclockerCount() == 2, "real-item-overclocker-detection");
            check(stats.getTransformerCount() == 1, "real-item-transformer-detection");
            check(stats.getEffectiveCableTier(CableTier.LV) == CableTier.MV, "real-item-transformer-result");
            check(stats.getProcessTicks(200) == 98, "si-current-processing-result");
            check(stats.getEnergyPerTick(32) == Math.round(32 * Math.pow(1.6, 2)), "experimental-energy-result");
            var copy = new ItemStackHandler(4);
            copy.deserializeNBT(event.getServer().registryAccess(), inventory.serializeNBT(event.getServer().registryAccess()));
            var restored = MachineUpgradeStats.fromInventory(copy, 0, 4);
            check(restored.getOverclockerCount() == 2 && restored.getTransformerCount() == 1, "inventory-nbt-roundtrip");
            inventory.setStackInSlot(0, ItemStack.EMPTY);
            inventory.setStackInSlot(1, ItemStack.EMPTY);
            var removed = MachineUpgradeStats.fromInventory(inventory, 0, 4);
            check(removed.getProcessTicks(200) == 200 && removed.getEffectiveCableTier(CableTier.LV) == CableTier.LV,
                    "upgrade-removal-restores-values");
            var storageItem = BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:upgrade/energy_storage_upgrade"));
            var ejectorItem = BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:upgrade/ejector_upgrade"));
            var pullingItem = BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:upgrade/pulling_upgrade"));
            var fluidEjectorItem = BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:upgrade/fluid_ejector_upgrade"));
            var fluidPullingItem = BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:upgrade/fluid_pulling_upgrade"));
            check(storageItem != Items.AIR && ejectorItem != Items.AIR && pullingItem != Items.AIR
                && fluidEjectorItem != Items.AIR && fluidPullingItem != Items.AIR, "registered-automation-items");
            var mixed = new ItemStackHandler(6);
            mixed.setStackInSlot(0, new ItemStack(storageItem, 3));
            mixed.setStackInSlot(1, new ItemStack(ejectorItem, 2));
            mixed.setStackInSlot(2, new ItemStack(pullingItem));
            mixed.setStackInSlot(3, new ItemStack(fluidEjectorItem));
            mixed.setStackInSlot(4, new ItemStack(fluidPullingItem));
            var automation = MachineUpgradeStats.fromInventory(mixed, 0, Integer.MAX_VALUE);
            check(automation.getEnergyCapacityBonus() == 30000, "storage-counts");
            check(automation.getEjectorCount() == 2 && automation.getPullingCount() == 1, "item-automation-counts");
            check(automation.getFluidEjectorCount() == 1 && automation.getFluidPullingCount() == 1, "fluid-automation-counts");
            check(automation.getEjectorDirections().size() == 6 && automation.getPullingDirections().size() == 6,
                "default-item-directions");
            check(automation.getFluidEjectorDirections().size() == 6 && automation.getFluidPullingDirections().size() == 6,
                "default-fluid-directions");
            check(MachineUpgradeStats.fromInventory(mixed, 1, Integer.MAX_VALUE).getEjectorCount() == 2, "slot-range-overflow");
            passed = true;
        } catch (Throwable failure) {
            failure.printStackTrace();
        } finally {
            try {
                Files.writeString(Path.of(System.getProperty("scex.smoke.result")),
                        "{\"passed\":" + passed + ",\"assertions\":" + assertions + "}\n");
            } catch (Exception failure) { failure.printStackTrace(); }
            System.out.println("SI_SMOKE passed=" + passed + " assertions=" + assertions);
            event.getServer().halt(false);
        }
    }
}
