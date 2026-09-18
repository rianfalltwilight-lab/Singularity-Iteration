// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.mojang.authlib.GameProfile;
import com.singularity_iteration.mio_icif.api.item.IBatteryItem;
import com.singularity_iteration.mio_icif.api.item.IJetpackItem;
import com.singularity_iteration.mio_icif.network.mio_icif_KeyboardManager;
import com.singularity_iteration.mio_icif.util.JetpackKeyHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Public-method and ordinary world-tick baseline for the opaque R147 flight group. */
public final class FlightBaselineWorldProbe {
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private int assertions;

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError("R147 baseline " + label);
    }

    private Item item(String id) {
        Item result = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        check(result != Items.AIR, "registered " + id);
        check(result instanceof IJetpackItem, "jetpack API " + id);
        check(result instanceof IBatteryItem, "battery API " + id);
        return result;
    }

    private FakePlayer player(ServerLevel world, int index, BlockPos pos) {
        var player = new FakePlayer(world, new GameProfile(new UUID(14700, index + 1), "SI_R147_" + index));
        player.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        return player;
    }

    private Map<String, Object> publicSurface(ServerLevel world, String id) {
        Item item = item(id);
        IJetpackItem jetpack = (IJetpackItem)item;
        IBatteryItem battery = (IBatteryItem)item;
        ItemStack stack = new ItemStack(item);
        var modes = new LinkedHashMap<String, String>();
        for (var mode : IJetpackItem.JetpackMode.values()) {
            jetpack.setMode(stack, mode);
            modes.put(mode.name(), jetpack.getMode(stack).name());
        }
        var internalModes = new LinkedHashMap<String, Integer>();
        if (item instanceof com.singularity_iteration.mio_icif.Items.Armor.mio_icif_chestplate_jetpack_elc normal) {
            for (int mode = 0; mode <= 1; mode++) {
                normal.setModeInternal(stack, mode);
                internalModes.put(Integer.toString(mode), normal.getModeInternal(stack));
            }
        } else if (item instanceof com.singularity_iteration.mio_icif.Items.Armor.mio_icif_chestplate_advanced_jetpack advanced) {
            for (int mode = 0; mode <= 1; mode++) {
                advanced.setModeInternal(stack, mode);
                internalModes.put(Integer.toString(mode), advanced.getModeInternal(stack));
            }
        }
        jetpack.setMode(stack, IJetpackItem.JetpackMode.NORMAL);
        battery.setEnergy(stack, 100);
        long before = battery.getEnergy(stack);
        boolean can = jetpack.canFlyTick(stack);
        boolean consumed = jetpack.consumeFlyTick(stack);
        long after = battery.getEnergy(stack);
        check(before == 100 && can && consumed && after >= 0 && after < before, "public fly debit " + id);
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("max_energy", battery.getMaxEnergy(stack));
        result.put("charge_rate", battery.getChargeRate(stack));
        result.put("thrust", jetpack.getThrust());
        result.put("energy_per_tick_flying", jetpack.getEnergyPerTickFlying());
        result.put("max_height", jetpack.getMaxHeight());
        result.put("height_limited", jetpack.hasHeightLimit());
        result.put("hover_height_offset", jetpack.getHoverHeightOffset());
        result.put("mode_roundtrips", modes);
        result.put("internal_mode_roundtrips", internalModes);
        result.put("public_debit", before - after);
        result.put("saved_stack", stack.save(world.registryAccess()).toString());
        return result;
    }

    private void reset(FakePlayer player, ItemStack stack, IJetpackItem jetpack, IBatteryItem battery,
            IJetpackItem.JetpackMode mode, int keyBits) {
        player.setItemSlot(EquipmentSlot.CHEST, stack);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 7.0F;
        battery.setEnergy(stack, 1000);
        jetpack.setMode(stack, mode);
        mio_icif_KeyboardManager.processKeyUpdate(player, keyBits);
        JetpackKeyHandler.processKeyUpdate(player, keyBits);
    }

    private Map<String, Object> movement(ServerLevel world, String id, int index) {
        Item item = item(id);
        IJetpackItem jetpack = (IJetpackItem)item;
        IBatteryItem battery = (IBatteryItem)item;
        ItemStack stack = new ItemStack(item);
        FakePlayer player = player(world, index, new BlockPos(1600 + index * 8, 90, 0));
        var cases = new LinkedHashMap<String, Object>();
        int jump = 1 << mio_icif_KeyboardManager.KEY_JUMP;
        int forward = 1 << mio_icif_KeyboardManager.KEY_FORWARD;
        int sneak = 1 << mio_icif_KeyboardManager.KEY_SNEAK;
        int boost = 1 << mio_icif_KeyboardManager.KEY_BOOST;
        var specs = List.of(
            Map.entry("normal_idle", new int[]{IJetpackItem.JetpackMode.NORMAL.ordinal(), 0}),
            Map.entry("normal_jump", new int[]{IJetpackItem.JetpackMode.NORMAL.ordinal(), jump}),
            Map.entry("normal_jump_forward", new int[]{IJetpackItem.JetpackMode.NORMAL.ordinal(), jump | forward}),
            Map.entry("normal_jump_boost", new int[]{IJetpackItem.JetpackMode.NORMAL.ordinal(), jump | boost}),
            Map.entry("hover_idle", new int[]{IJetpackItem.JetpackMode.HOVER.ordinal(), 0}),
            Map.entry("hover_jump", new int[]{IJetpackItem.JetpackMode.HOVER.ordinal(), jump}),
            Map.entry("hover_sneak", new int[]{IJetpackItem.JetpackMode.HOVER.ordinal(), sneak}),
            Map.entry("off_jump", new int[]{IJetpackItem.JetpackMode.OFF.ordinal(), jump})
        );
        for (var spec : specs) {
            var mode = IJetpackItem.JetpackMode.values()[spec.getValue()[0]];
            reset(player, stack, jetpack, battery, mode, spec.getValue()[1]);
            long before = battery.getEnergy(stack);
            player.getInventory().tick();
            Vec3 motion = player.getDeltaMovement();
            cases.put(spec.getKey(), Map.of(
                "mode_after", jetpack.getMode(stack).name(),
                "energy_debit", before - battery.getEnergy(stack),
                "dx", motion.x,
                "dy", motion.y,
                "dz", motion.z,
                "fall_distance", player.fallDistance
            ));
        }
        mio_icif_KeyboardManager.removePlayerReferences(player);
        JetpackKeyHandler.removePlayer(player);
        return Map.of("id", id, "cases", cases);
    }

    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        if (tick == 30) {
            rows.add(publicSurface(world, "mio_icif:armor/item_armor_jetpack_electric"));
            rows.add(publicSurface(world, "mio_icif:armor/item_armor_advanced_jetpack"));
            rows.add(movement(world, "mio_icif:armor/item_armor_jetpack_electric", 0));
            rows.add(movement(world, "mio_icif:armor/item_armor_advanced_jetpack", 1));
            Map<String, Object> result = Map.of(
                "passed", true,
                "assertions", assertions,
                "rows", rows,
                "scope", "Opaque SI candidate public methods, ordinary ItemStack save, FakePlayer Inventory.tick; no source body or bytecode inspection; connected client NOT_RUN"
            );
            Files.writeString(Path.of("flight-baseline-r147-result.json"), new com.google.gson.Gson().toJson(result));
            System.out.println("SCEX_FLIGHT_BASELINE_R147_PASS rows=" + rows.size() + " assertions=" + assertions);
            return result;
        }
        return Map.of("assertions", assertions, "rows", rows.size());
    }
}
