// SPDX-License-Identifier: Apache-2.0
// R133 independent SI public declaration; no predecessor body was read.
package com.singularity_iteration.mio_icif.api.item.electric;

import net.minecraft.world.item.ItemStack;

public interface IBackupElectricItemManager extends IElectricItemManager {
    boolean handles(ItemStack stack);
}
