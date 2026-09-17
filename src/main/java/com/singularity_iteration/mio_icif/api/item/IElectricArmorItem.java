// SPDX-License-Identifier: Apache-2.0
package com.singularity_iteration.mio_icif.api.item;

import java.util.List;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * SI electric-armor contract, independently declared from the retained SI public ABI.
 * Defaults follow the allowed AbstractElectricArmor implementation and introduce no
 * armor rating, energy unit, transfer tier, or additional item-state representation.
 */
public interface IElectricArmorItem extends IBatteryItem {
    long getEnergyPerTick();

    int getArmorTier();

    default boolean hasEnoughEnergy(ItemStack stack) {
        return getEnergy(stack) >= getEnergyPerTick();
    }

    default boolean consumeEnergy(ItemStack stack, long amount) {
        return dev.scex.si.energy.BatteryTransfer.consume(stack, this, amount);
    }

    default List<ArmorFeatureInfo> getFeatures(ItemStack stack) {
        return List.of();
    }

    default long getEnergyPerDamage() {
        return 0L;
    }

    default float getDamageAbsorptionRatio(EquipmentSlot slot) {
        return 0.0F;
    }
}
