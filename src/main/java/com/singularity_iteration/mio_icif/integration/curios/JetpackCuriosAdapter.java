// SPDX-License-Identifier: Apache-2.0
// SCEX: loaded only through the existing Curios-present integration gate.
package com.singularity_iteration.mio_icif.integration.curios;

import com.singularity_iteration.mio_icif.Items.Armor.mio_icif_items_armors;
import com.singularity_iteration.mio_icif.api.item.IBackSlotItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

public final class JetpackCuriosAdapter implements ICurioItem {
    public static void register(IEventBus bus) {
        bus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            var adapter = new JetpackCuriosAdapter();
            CuriosApi.registerCurio(mio_icif_items_armors.ARMOR_JETPACK_ELECTRIC.get(), adapter);
            CuriosApi.registerCurio(mio_icif_items_armors.ARMOR_ADVANCED_JETPACK.get(), adapter);
        }));
    }

    @Override
    public boolean canEquip(SlotContext context, ItemStack stack) {
        return context.identifier().equals("back");
    }

    @Override
    public void curioTick(SlotContext context, ItemStack stack) {
        if (context.identifier().equals("back") && context.entity() instanceof Player player
                && stack.getItem() instanceof IBackSlotItem item) item.tickInBackSlot(player, stack);
    }
}
