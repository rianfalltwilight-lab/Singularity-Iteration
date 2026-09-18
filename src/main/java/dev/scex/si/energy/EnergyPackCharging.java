// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.energy;

import com.singularity_iteration.mio_icif.api.item.IBatteryItem;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Bounded SI policy for a worn chest source: selected hand first, offhand second. */
public final class EnergyPackCharging {
    private static final WeakHashMap<Player, Long> LAST_TICK = new WeakHashMap<>();

    private EnergyPackCharging() { }

    public static long tick(ItemStack source, IBatteryItem battery, Level level, Player player, long limit) {
        if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()
                || player.level() != level || player.getItemBySlot(EquipmentSlot.CHEST) != source
                || source.isEmpty() || source.getCount() != 1 || limit <= 0) return 0;
        long tick = level.getGameTime();
        Long previous = LAST_TICK.put(player, tick);
        if (previous != null && previous == tick) return 0;
        long budget = Math.min(limit, battery.getEnergy(source));
        if (budget <= 0) return 0;
        long moved = charge(source, battery, player.getMainHandItem(), budget);
        budget -= moved;
        if (budget > 0) moved += charge(source, battery, player.getOffhandItem(), budget);
        return moved;
    }

    private static long charge(ItemStack source, IBatteryItem from, ItemStack target, long amount) {
        if (!BatteryTransfer.isEquipment(target) || !(target.getItem() instanceof IBatteryItem to)) return 0;
        return BatteryTransfer.move(source, from, target, to, amount);
    }
}
