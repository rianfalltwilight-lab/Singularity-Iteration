// SPDX-License-Identifier: Apache-2.0
// R133 independent SI public declaration; no predecessor body was read.
package com.singularity_iteration.mio_icif.api.item.electric;

import net.minecraft.world.item.ItemStack;

public interface IElectricItem {
    boolean canProvideEnergy(ItemStack stack);
    long getMaxCharge(ItemStack stack);
    int getTier(ItemStack stack);
    long getTransferLimit(ItemStack stack);
}
