// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.singularity_iteration.mio_icif.Items.Armor.mio_icif_items_armors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;

public final class CuriosProbe {
    public static int run(MinecraftServer server) {
        var player=FakePlayerFactory.getMinecraft(server.overworld());
        var back=new SlotContext("back",player,0,false,true);
        var belt=new SlotContext("belt",player,0,false,true);
        int assertions=0;
        for(var item:new net.minecraft.world.item.Item[]{mio_icif_items_armors.ARMOR_JETPACK_ELECTRIC.get(),
                mio_icif_items_armors.ARMOR_ADVANCED_JETPACK.get()}) {
            var curio=CuriosApi.getCurio(new ItemStack(item));
            assertions++; if(curio.isEmpty()) throw new AssertionError("Jetpack Curios capability missing");
            assertions++; if(!curio.get().canEquip(back)) throw new AssertionError("Back slot rejected");
            assertions++; if(curio.get().canEquip(belt)) throw new AssertionError("Wrong slot accepted");
            curio.get().curioTick(back);
        }
        return assertions;
    }
}
