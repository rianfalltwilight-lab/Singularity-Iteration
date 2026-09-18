// SPDX-License-Identifier: Apache-2.0
package com.singularity_iteration.mio_icif.integration.curios;

import com.singularity_iteration.mio_icif.Items.Armor.mio_icif_items_armors;
import com.singularity_iteration.mio_icif.api.item.IBackSlotItem;
import com.singularity_iteration.mio_icif.api.item.IJetpackItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

/** Optional Curios bridge for the two SI jetpacks. */
public final class JetpackCuriosAdapter implements ICurioItem {
    public static void register(IEventBus bus) {
        bus.addListener(JetpackCuriosAdapter::onCommonSetup);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            JetpackCuriosAdapter adapter = new JetpackCuriosAdapter();
            CuriosApi.registerCurio(mio_icif_items_armors.ARMOR_JETPACK_ELECTRIC.get(), adapter);
            CuriosApi.registerCurio(mio_icif_items_armors.ARMOR_ADVANCED_JETPACK.get(), adapter);
        });
    }

    @Override
    public boolean canEquip(SlotContext context, ItemStack stack) {
        return "back".equals(context.identifier()) && stack.getItem() instanceof IJetpackItem;
    }

    @Override
    public void curioTick(SlotContext context, ItemStack stack) {
        if (context.cosmetic() || !(context.entity() instanceof Player player)) return;
        if (stack.getItem() instanceof IBackSlotItem backSlotItem) {
            backSlotItem.tickInBackSlot(player, stack);
        }
    }
}
