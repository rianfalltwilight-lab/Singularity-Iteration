// SPDX-License-Identifier: Apache-2.0
// R133 independent SI public declaration; intentionally no added parent interface.
package com.singularity_iteration.mio_icif.api.item.electric;

import net.minecraft.world.item.ItemStack;

public interface ISpecialElectricItem {
    IElectricItemManager getManager(ItemStack stack);
}
