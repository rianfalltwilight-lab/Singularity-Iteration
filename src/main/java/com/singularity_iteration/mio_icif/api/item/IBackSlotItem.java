// SPDX-License-Identifier: Apache-2.0
package com.singularity_iteration.mio_icif.api.item;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Optional back-slot tick bridge without an external accessory API dependency. */
public interface IBackSlotItem {
    void tickInBackSlot(Player player, ItemStack stack);
}
