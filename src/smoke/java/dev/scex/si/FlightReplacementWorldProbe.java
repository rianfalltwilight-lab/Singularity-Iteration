// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.mojang.authlib.GameProfile;
import com.singularity_iteration.mio_icif.Items.Armor.mio_icif_chestplate_advanced_jetpack;
import com.singularity_iteration.mio_icif.Items.Armor.mio_icif_chestplate_jetpack_elc;
import com.singularity_iteration.mio_icif.api.item.IBatteryItem;
import com.singularity_iteration.mio_icif.api.item.IJetpackItem;
import com.singularity_iteration.mio_icif.integration.curios.JetpackCuriosAdapter;
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
import top.theillusivec4.curios.api.SlotContext;

/** Product assertions for the independently implemented R148 flight/input group. */
public final class FlightReplacementWorldProbe {
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private int assertions;

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError("R148 flight replacement " + label);
    }

    private Item item(String id) {
        Item result = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        check(result != Items.AIR, "registered " + id);
        check(result instanceof IJetpackItem, "jetpack API " + id);
        check(result instanceof IBatteryItem, "battery API " + id);
        return result;
    }

    private FakePlayer player(ServerLevel world, int index, double y) {
        FakePlayer player = new FakePlayer(world,
            new GameProfile(new UUID(14800, index + 1), "SI_R148_" + index));
        player.setPos(1700.5 + index * 8, y, 0.5);
        player.setYRot(0.0F);
        player.setXRot(0.0F);
        return player;
    }

    private int internalMode(Item item, ItemStack stack) {
        if (item instanceof mio_icif_chestplate_jetpack_elc normal) return normal.getModeInternal(stack);
        if (item instanceof mio_icif_chestplate_advanced_jetpack advanced) return advanced.getModeInternal(stack);
        throw new AssertionError("unexpected jetpack class");
    }

    private void reset(FakePlayer player, ItemStack stack, IJetpackItem jetpack,
            IBatteryItem battery, IJetpackItem.JetpackMode mode, int keyBits, long energy) {
        player.setItemSlot(EquipmentSlot.CHEST, stack);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 7.0F;
        battery.setEnergy(stack, energy);
        jetpack.setMode(stack, mode);
        JetpackKeyHandler.processKeyUpdate(player, keyBits);
    }

    private Map<String, Object> surface(ServerLevel world, String id) {
        Item item = item(id);
        IJetpackItem jetpack = (IJetpackItem)item;
        IBatteryItem battery = (IBatteryItem)item;
        ItemStack stack = new ItemStack(item);

        var modes = new LinkedHashMap<String, String>();
        for (var mode : IJetpackItem.JetpackMode.values()) {
            jetpack.setMode(stack, mode);
            modes.put(mode.name(), jetpack.getMode(stack).name());
        }
        check("FLIGHT".equals(modes.get("OFF")), id + " OFF normalizes to FLIGHT");
        check("FLIGHT".equals(modes.get("NORMAL")), id + " NORMAL normalizes to FLIGHT");
        check("HOVER".equals(modes.get("HOVER")), id + " HOVER roundtrip");
        check("FLIGHT".equals(modes.get("FLIGHT")), id + " FLIGHT roundtrip");

        jetpack.setMode(stack, IJetpackItem.JetpackMode.FLIGHT);
        battery.setEnergy(stack, Math.max(0L, jetpack.getEnergyPerTickFlying() - 1L));
        long insufficientBefore = battery.getEnergy(stack);
        check(!jetpack.canFlyTick(stack), id + " insufficient cannot fly");
        check(!jetpack.consumeFlyTick(stack), id + " insufficient consume false");
        check(battery.getEnergy(stack) == insufficientBefore, id + " insufficient debit atomic");

        battery.setEnergy(stack, 100L);
        long before = battery.getEnergy(stack);
        check(jetpack.consumeFlyTick(stack), id + " sufficient consume true");
        check(before - battery.getEnergy(stack) == jetpack.getEnergyPerTickFlying(), id + " exact public debit");
        check(jetpack.getMaxHeight() == 310.0F && jetpack.hasHeightLimit(), id + " height contract");
        check(jetpack.getHoverHeightOffset() == 1.0F, id + " hover offset contract");
        check(internalMode(item, stack) == 0, id + " internal flight mode");

        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("max_energy", battery.getMaxEnergy(stack));
        result.put("charge_rate", battery.getChargeRate(stack));
        result.put("thrust", jetpack.getThrust());
        result.put("energy_per_tick", jetpack.getEnergyPerTickFlying());
        result.put("mode_roundtrips", modes);
        result.put("saved_stack", stack.save(world.registryAccess()).toString());
        return result;
    }

    private Map<String, Object> inputs(FakePlayer player) {
        int all = (1 << 5) - 1;
        JetpackKeyHandler.processKeyUpdate(player, all);
        check(JetpackKeyHandler.isJumpKeyDown(player), "jump bit");
        check(JetpackKeyHandler.isBoostKeyDown(player), "boost bit");
        check(JetpackKeyHandler.isForwardKeyDown(player), "forward bit");
        check(JetpackKeyHandler.isSneakKeyDown(player), "sneak bit");
        check(JetpackKeyHandler.isModeSwitchKeyDown(player), "mode bit");
        check(JetpackKeyHandler.isAltKeyDown(player), "alt compatibility bit");
        mio_icif_KeyboardManager.processKeyUpdate(player, all);
        check(mio_icif_KeyboardManager.isJumpKeyDown(player), "manager jump bit");
        check(mio_icif_KeyboardManager.isBoostKeyDown(player), "manager boost bit");
        check(mio_icif_KeyboardManager.isForwardKeyDown(player), "manager forward bit");
        check(mio_icif_KeyboardManager.isSneakKeyDown(player), "manager sneak bit");
        check(mio_icif_KeyboardManager.isSafetyKeyDown(player), "manager safety bit");
        var state = JetpackKeyHandler.KeyState.fromInt(all);
        check(state.toInt() == all, "key-state lossless five-bit roundtrip");
        mio_icif_KeyboardManager.removePlayerReferences(player);
        check(!JetpackKeyHandler.isJumpKeyDown(player), "input cleanup");
        return Map.of("all_bits", all, "roundtrip", state.toInt());
    }

    private Map<String, Object> movement(ServerLevel world, String id, int index) {
        Item item = item(id);
        IJetpackItem jetpack = (IJetpackItem)item;
        IBatteryItem battery = (IBatteryItem)item;
        ItemStack stack = new ItemStack(item);
        FakePlayer player = player(world, index, 90.0D);
        var cases = new LinkedHashMap<String, Object>();
        int jump = 1 << mio_icif_KeyboardManager.KEY_JUMP;
        int forward = 1 << mio_icif_KeyboardManager.KEY_FORWARD;
        int sneak = 1 << mio_icif_KeyboardManager.KEY_SNEAK;
        int boost = 1 << mio_icif_KeyboardManager.KEY_BOOST;

        reset(player, stack, jetpack, battery, IJetpackItem.JetpackMode.FLIGHT, 0, 1000);
        long before = battery.getEnergy(stack);
        player.getInventory().tick();
        check(battery.getEnergy(stack) == before, id + " idle flight no debit");
        check(player.getDeltaMovement().equals(Vec3.ZERO), id + " idle flight no movement");
        check(player.fallDistance == 7.0F, id + " idle flight leaves fall distance");
        cases.put("flight_idle", movementRow(player, before - battery.getEnergy(stack), jetpack, stack));

        reset(player, stack, jetpack, battery, IJetpackItem.JetpackMode.FLIGHT, jump, 1000);
        before = battery.getEnergy(stack);
        player.getInventory().tick();
        check(before - battery.getEnergy(stack) == jetpack.getEnergyPerTickFlying(), id + " flight exact debit");
        check(player.getDeltaMovement().y > 0.0D && player.getDeltaMovement().y <= 0.5D, id + " flight bounded ascent");
        check(player.fallDistance == 0.0F, id + " flight fall reset");
        cases.put("flight_jump", movementRow(player, before - battery.getEnergy(stack), jetpack, stack));

        reset(player, stack, jetpack, battery, IJetpackItem.JetpackMode.FLIGHT, jump | forward | boost, 1000);
        before = battery.getEnergy(stack);
        player.getInventory().tick();
        check(Math.abs(player.getDeltaMovement().x) + Math.abs(player.getDeltaMovement().z) > 0.0D,
            id + " boost horizontal movement");
        cases.put("flight_boost", movementRow(player, before - battery.getEnergy(stack), jetpack, stack));

        reset(player, stack, jetpack, battery, IJetpackItem.JetpackMode.HOVER, 0, 1000);
        before = battery.getEnergy(stack);
        player.getInventory().tick();
        long hoverDebit = before - battery.getEnergy(stack);
        long expectedHover = item instanceof mio_icif_chestplate_advanced_jetpack ? 2L : 1L;
        check(hoverDebit == expectedHover, id + " hover exact debit");
        check(player.getDeltaMovement().y == 0.0D && player.fallDistance == 0.0F, id + " stable hover");
        check(jetpack.getMode(stack) == IJetpackItem.JetpackMode.HOVER, id + " hover mode retained");
        cases.put("hover_idle", movementRow(player, hoverDebit, jetpack, stack));

        reset(player, stack, jetpack, battery, IJetpackItem.JetpackMode.HOVER, jump, 1000);
        before = battery.getEnergy(stack);
        player.getInventory().tick();
        check(player.getDeltaMovement().y > 0.0D, id + " hover ascent");
        cases.put("hover_jump", movementRow(player, before - battery.getEnergy(stack), jetpack, stack));

        reset(player, stack, jetpack, battery, IJetpackItem.JetpackMode.HOVER, sneak, 1000);
        before = battery.getEnergy(stack);
        player.getInventory().tick();
        check(player.getDeltaMovement().y < 0.0D, id + " hover descent");
        check(jetpack.getMode(stack) == IJetpackItem.JetpackMode.HOVER, id + " sneak does not change mode");
        cases.put("hover_sneak", movementRow(player, before - battery.getEnergy(stack), jetpack, stack));

        reset(player, stack, jetpack, battery, IJetpackItem.JetpackMode.FLIGHT, jump, 1);
        before = battery.getEnergy(stack);
        player.getInventory().tick();
        check(battery.getEnergy(stack) == before && player.getDeltaMovement().equals(Vec3.ZERO),
            id + " insufficient energy no partial movement or debit");
        check(player.fallDistance == 7.0F, id + " insufficient energy no fall reset");
        cases.put("insufficient", movementRow(player, before - battery.getEnergy(stack), jetpack, stack));

        player.setPos(player.getX(), jetpack.getMaxHeight() + 1.0D, player.getZ());
        reset(player, stack, jetpack, battery, IJetpackItem.JetpackMode.FLIGHT, jump, 1000);
        before = battery.getEnergy(stack);
        player.getInventory().tick();
        check(player.getDeltaMovement().y <= 0.0D, id + " height ceiling blocks ascent");
        cases.put("height_ceiling", movementRow(player, before - battery.getEnergy(stack), jetpack, stack));

        player.setPos(player.getX(), 90.0D, player.getZ());
        player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        battery.setEnergy(stack, 1000);
        jetpack.setMode(stack, IJetpackItem.JetpackMode.FLIGHT);
        JetpackKeyHandler.processKeyUpdate(player, jump);
        player.setDeltaMovement(Vec3.ZERO);
        SlotContext back = new SlotContext("back", player, 0, false, true);
        SlotContext charm = new SlotContext("charm", player, 0, false, true);
        JetpackCuriosAdapter adapter = new JetpackCuriosAdapter();
        check(adapter.canEquip(back, stack), id + " curios back accepted");
        check(!adapter.canEquip(charm, stack), id + " curios non-back rejected");
        before = battery.getEnergy(stack);
        adapter.curioTick(back, stack);
        check(before - battery.getEnergy(stack) == jetpack.getEnergyPerTickFlying(), id + " curios tick debit");
        check(player.getDeltaMovement().y > 0.0D, id + " curios tick movement");
        cases.put("curios_back", movementRow(player, before - battery.getEnergy(stack), jetpack, stack));

        JetpackKeyHandler.removePlayer(player);
        return Map.of("id", id, "cases", cases);
    }

    private Map<String, Object> movementRow(FakePlayer player, long debit,
            IJetpackItem jetpack, ItemStack stack) {
        Vec3 motion = player.getDeltaMovement();
        return Map.of(
            "debit", debit,
            "dx", motion.x,
            "dy", motion.y,
            "dz", motion.z,
            "fall_distance", player.fallDistance,
            "mode", jetpack.getMode(stack).name());
    }

    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        if (tick != 30) return Map.of("assertions", assertions, "rows", rows.size());
        rows.add(surface(world, "mio_icif:armor/item_armor_jetpack_electric"));
        rows.add(surface(world, "mio_icif:armor/item_armor_advanced_jetpack"));
        rows.add(Map.of("inputs", inputs(player(world, 9, 90.0D))));
        rows.add(movement(world, "mio_icif:armor/item_armor_jetpack_electric", 0));
        rows.add(movement(world, "mio_icif:armor/item_armor_advanced_jetpack", 1));
        Map<String, Object> result = Map.of(
            "passed", true,
            "assertions", assertions,
            "rows", rows,
            "scope", "R148 independent flight source product: public ABI, key state, exact energy, bounded motion, height limit, Curios back slot and ordinary save");
        Files.writeString(Path.of("flight-replacement-r148-result.json"),
            new com.google.gson.Gson().toJson(result));
        System.out.println("SCEX_FLIGHT_REPLACEMENT_R148_PASS rows=" + rows.size()
            + " assertions=" + assertions);
        return result;
    }
}
