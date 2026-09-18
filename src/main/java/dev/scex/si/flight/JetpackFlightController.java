// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.flight;

import com.singularity_iteration.mio_icif.api.item.IJetpackItem;
import com.singularity_iteration.mio_icif.util.JetpackKeyHandler;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Server-authoritative, bounded flight policy shared by both SI jetpacks. */
public final class JetpackFlightController {
    private static final Map<UUID, Long> ACTIVE_TICKS = new ConcurrentHashMap<>();

    private JetpackFlightController() {}

    public static void tick(Player player, ItemStack stack, IJetpackItem jetpack,
            long hoverCost, double maxAscentSpeed, double hoverAscentSpeed,
            double hoverDescentSpeed) {
        if (player.level().isClientSide || stack.isEmpty() || stack.getCount() != 1) return;

        IJetpackItem.JetpackMode mode = jetpack.getMode(stack);
        boolean hover = mode == IJetpackItem.JetpackMode.HOVER;
        boolean jump = JetpackKeyHandler.isJumpKeyDown(player);
        boolean active = hover || jump;
        long cost = hover ? Math.max(0L, hoverCost) : Math.max(0L, jetpack.getEnergyPerTickFlying());
        if (!active || !consumeExact(stack, jetpack, cost)) {
            ACTIVE_TICKS.remove(player.getUUID());
            return;
        }

        Vec3 motion = player.getDeltaMovement();
        double vertical;
        if (hover) {
            if (jump && canAscend(player, jetpack)) {
                vertical = Math.min(hoverAscentSpeed, Math.max(0.0D, motion.y) + hoverAscentSpeed);
            } else if (JetpackKeyHandler.isSneakKeyDown(player)) {
                vertical = Math.max(hoverDescentSpeed, Math.min(0.0D, motion.y) + hoverDescentSpeed);
            } else {
                vertical = 0.0D;
            }
        } else if (canAscend(player, jetpack)) {
            vertical = Math.min(maxAscentSpeed, motion.y + jetpack.getThrust() / 7.5D);
        } else {
            vertical = Math.min(0.0D, motion.y);
        }

        double horizontalScale = JetpackKeyHandler.isBoostKeyDown(player)
            ? jetpack.getThrust() * 0.08D
            : jetpack.getThrust() * 0.04D;
        double x = motion.x;
        double z = motion.z;
        if (JetpackKeyHandler.isForwardKeyDown(player) || JetpackKeyHandler.isBoostKeyDown(player)) {
            Vec3 look = player.getLookAngle();
            double length = Math.sqrt(look.x * look.x + look.z * look.z);
            if (length > 1.0E-7D) {
                x += look.x / length * horizontalScale;
                z += look.z / length * horizontalScale;
            }
        }

        player.setDeltaMovement(x, vertical, z);
        player.fallDistance = 0.0F;
        ACTIVE_TICKS.put(player.getUUID(), player.level().getGameTime());
    }

    private static boolean canAscend(Player player, IJetpackItem jetpack) {
        return !jetpack.hasHeightLimit() || player.getY() < jetpack.getMaxHeight();
    }

    private static boolean consumeExact(ItemStack stack, IJetpackItem jetpack, long cost) {
        if (cost == 0L) return true;
        if (jetpack.getEnergy(stack) < cost) return false;
        long extracted = jetpack.extractEnergy(stack, cost);
        if (extracted == cost) return true;
        if (extracted > 0L) jetpack.addEnergy(stack, extracted);
        return false;
    }

    public static boolean isFlying(Player player) {
        if (player == null) return false;
        Long tick = ACTIVE_TICKS.get(player.getUUID());
        return tick != null && tick == player.level().getGameTime();
    }
}
