// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.energy;

import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_producer;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.MachineItemHandler;
import com.singularity_iteration.mio_icif.api.item.IBatteryItem;
import com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** Discharge only a prepared copy, then commit the matching input credit and owned slot. */
public final class MachineItemDischarging {
    public enum InputKind { MACHINE, STORAGE }
    private MachineItemDischarging() { }

    public static boolean dischargeSi(FeLedger ledger, CustomEUEnergyStorage storage, MachineItemHandler inventory, int slot, InputKind kind) {
        ItemStack current = inventory.getStackInSlot(slot);
        if (current.isEmpty()) return false;
        boolean fuel = current.is(Items.REDSTONE);
        if (!fuel && !(current.getItem() instanceof IBatteryItem)) return false;
        if (!fuel && (current.getCount() != 1 || kind == InputKind.STORAGE && BatteryTransfer.isEquipment(current))) return true;
        long offered = ledger.receiveWholeEu(Long.MAX_VALUE, true);
        if (offered <= 0) return true;
        if (fuel) offered = Math.min(offered, mio_icif_producer.REDSTONE_ENERGY_VALUE);
        else {
            IBatteryItem battery = (IBatteryItem)current.getItem();
            if (kind == InputKind.MACHINE) offered = Math.min(offered, Math.max(0, battery.getChargeRate(current)));
            offered = BatteryTransfer.transfer(current, battery, offered, false, true);
        }
        if (offered <= 0) return true;
        ItemStack before = current.copy(), after = before.copy();
        var quote = storage.scexNetworkQuote(); var exact = storage.scexExactAmount();
        long removed;
        if (fuel) { after.shrink(1); removed = offered; }
        else removed = BatteryTransfer.transfer(after, (IBatteryItem)after.getItem(), offered, false, false);
        if (removed <= 0 || !fuel && (after.getCount() != 1 || after.getItem() != before.getItem())
                || !ItemStack.matches(before, inventory.getStackInSlot(slot))
                || !quote.equals(storage.scexNetworkQuote()) || !exact.equals(storage.scexExactAmount())
                || ledger.receiveWholeEu(removed, true) != removed) return true;
        if (ledger.receiveWholeEu(removed, false) != removed)
            throw new IllegalStateException("Owned SI input allowance changed during credit");
        commit(inventory, slot, before, after);
        return true;
    }

    /** The capability must belong to after, an unpublished copy of the owned before snapshot. */
    public static boolean dischargeFe(FeLedger ledger, CustomEUEnergyStorage storage, MachineItemHandler inventory, int slot,
                                      ItemStack before, ItemStack after, IEnergyStorage capability) {
        if (before.isEmpty() || before.getCount() != 1 || after.getCount() != 1 || after.getItem() != before.getItem()
                || capability == null || !capability.canExtract()) return false;
        int offered = ledger.receive(Integer.MAX_VALUE, true);
        if (offered <= 0) return true;
        var quote = storage.scexNetworkQuote(); var exact = storage.scexExactAmount();
        int removed = capability.extractEnergy(offered, false);
        if (removed <= 0 || removed > offered || ItemStack.matches(before, after) || after.getCount() != 1 || after.getItem() != before.getItem()
                || !ItemStack.matches(before, inventory.getStackInSlot(slot))
                || !quote.equals(storage.scexNetworkQuote()) || !exact.equals(storage.scexExactAmount())
                || ledger.receive(removed, true) != removed) return true;
        if (ledger.receive(removed, false) != removed)
            throw new IllegalStateException("Owned FE input allowance changed during credit");
        commit(inventory, slot, before, after);
        return true;
    }

    private static void commit(MachineItemHandler inventory, int slot, ItemStack before, ItemStack after) {
        if (!inventory.scexCommitSlots(new int[]{slot}, new ItemStack[]{before}, new ItemStack[]{after}))
            throw new IllegalStateException("Owned discharge slot changed during credit");
    }
}
