// SPDX-License-Identifier: Apache-2.0
package com.singularity_iteration.mio_icif.Items.Armor;

import com.singularity_iteration.mio_icif.api.item.IEnergyPackItem;
import dev.scex.si.energy.EnergyPackCharging;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Shared independent policy for SI's four worn charging packs. */
@SuppressWarnings({"null", "deprecation"})
public abstract class mio_icif_chestplate_energy_pack extends mio_icif_armor_elc implements IEnergyPackItem {
    private final int transferLimit;

    public mio_icif_chestplate_energy_pack(Holder<ArmorMaterial> material, Properties properties,
            int maxEnergy, String texture, int transferLimit, int tier) {
        super(material, Type.CHESTPLATE, properties, maxEnergy, 0, texture, transferLimit, 0, tier);
        this.transferLimit = Math.max(0, transferLimit);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean selected) {
        super.inventoryTick(stack, level, entity, slotId, selected);
        if (level.isClientSide || !(entity instanceof Player player)
                || player.getItemBySlot(EquipmentSlot.CHEST) != stack) return;
        EnergyPackCharging.tick(stack, this, level, player, transferLimit);
    }

    @Override
    public long getEnergyPerDamage() {
        return 0;
    }
}
