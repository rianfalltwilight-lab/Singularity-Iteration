// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.singularity_iteration.mio_icif.Items.Armor.mio_icif_advanced_solar_helmet;
import com.singularity_iteration.mio_icif.Items.Armor.mio_icif_hybrid_solar_helmet;
import com.singularity_iteration.mio_icif.Items.Armor.mio_icif_ultimate_solar_helmet;
import com.singularity_iteration.mio_icif.api.item.IBatteryItem;
import com.singularity_iteration.mio_icif.api.item.IElectricArmorItem;
import com.singularity_iteration.mio_icif.api.MioIcifAPI;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Product smoke for the three registered Advanced Solar Panels headgear tiers. */
public final class SolarHeadgearWorldProbe {
    private static final BlockPos BASE = new BlockPos(2432, 200, 0);
    private static final String[] IDS = {
        "mio_icif:armor/item_armor_advanced_solar_helmet",
        "mio_icif:armor/item_armor_hybrid_solar_helmet",
        "mio_icif:armor/item_armor_ultimate_solar_helmet"
    };
    private static final long[] CAPACITY = {1_000_000L, 10_000_000L, 10_000_000L};
    private static final int[] DAY = {8, 64, 512};
    private static final int[] NIGHT = {1, 8, 64};
    private static final int[] LIMIT = {3000, 10000, 10000};
    private static final int[] TIER = {3, 4, 4};
    private static final float[] ABSORPTION = {0.9F, 1.0F, 1.0F};
    private static final java.lang.reflect.Field SPAWN_INVULNERABLE_TIME;

    static {
        try {
            SPAWN_INVULNERABLE_TIME = net.minecraft.server.level.ServerPlayer.class
                .getDeclaredField("spawnInvulnerableTime");
            SPAWN_INVULNERABLE_TIME.setAccessible(true);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static final class DamageableFakePlayer extends FakePlayer {
        private DamageableFakePlayer(ServerLevel world, GameProfile profile) {
            super(world, profile);
        }

        @Override
        public boolean isInvulnerableTo(DamageSource source) {
            return false;
        }
    }

    private final List<DamageableFakePlayer> players = new ArrayList<>();
    private final List<IBatteryItem> helmets = new ArrayList<>();
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private int assertions;

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError("R178 solar headgear " + label);
    }

    private Item item(String id) {
        Item result = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        check(result != Items.AIR, "registered " + id);
        return result;
    }

    private long energy(ItemStack stack) {
        return stack.getItem() instanceof IBatteryItem battery ? battery.getEnergy(stack) : 0;
    }

    private DamageableFakePlayer player(ServerLevel world, int index) {
        var result = new DamageableFakePlayer(world,
            new GameProfile(new UUID(17800, index + 1), "SI_R178_" + index));
        BlockPos pos = BASE.offset(index * 8, 0, 0);
        world.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
        result.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        result.getAbilities().invulnerable = false;
        try {
            SPAWN_INVULNERABLE_TIME.setInt(result, 0);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to clear test player spawn protection", exception);
        }
        return result;
    }

    private void setup(ServerLevel world) {
        world.setDayTime(6000);
        world.setWeatherParameters(0, 0, false, false);
        Class<?>[] types = {
            mio_icif_advanced_solar_helmet.class,
            mio_icif_hybrid_solar_helmet.class,
            mio_icif_ultimate_solar_helmet.class
        };
        for (int index = 0; index < IDS.length; index++) {
            Item item = item(IDS[index]);
            check(item.getClass() == types[index], "exact registered class " + IDS[index]);
            check(item instanceof IElectricArmorItem, "electric armor ABI " + IDS[index]);
            var armor = (IElectricArmorItem) item;
            var battery = (IBatteryItem) item;
            ItemStack empty = new ItemStack(item);
            check(battery.getMaxEnergy(empty) == CAPACITY[index], "capacity " + IDS[index]);
            check(battery.getChargeRate(empty) == LIMIT[index], "transfer declaration " + IDS[index]);
            check(armor.getArmorTier() == TIER[index], "tier " + IDS[index]);
            check(armor.getDamageAbsorptionRatio(EquipmentSlot.HEAD) == ABSORPTION[index]
                    && armor.getDamageAbsorptionRatio(EquipmentSlot.CHEST) == 0.0F,
                "head-only damage ratio " + IDS[index]);
            check(armor.getEnergyPerDamage() == (long) (2000 * ABSORPTION[index]),
                "declared energy per absorbed damage " + IDS[index]);
            ItemStack full = item.getDefaultInstance();
            check(battery.getEnergy(full) == CAPACITY[index], "full default instance " + IDS[index]);

            DamageableFakePlayer player = player(world, index);
            player.setItemSlot(EquipmentSlot.HEAD, empty);
            ItemStack carried = new ItemStack(item("mio_icif:normal/item_bat_lev0"));
            ((IBatteryItem) carried.getItem()).setEnergy(carried, 0);
            player.getInventory().setItem(0, carried);
            players.add(player);
            helmets.add(battery);

            var row = new LinkedHashMap<String, Object>();
            row.put("id", IDS[index]);
            row.put("capacity_eu", CAPACITY[index]);
            row.put("day_eu_per_tick", DAY[index]);
            row.put("night_or_rain_eu_per_tick", NIGHT[index]);
            row.put("transfer_limit_eu_per_tick", LIMIT[index]);
            row.put("damage_absorption", ABSORPTION[index]);
            rows.add(row);
        }
    }

    private void generationPhase(ServerLevel world, int[] expected, String phase) {
        for (int index = 0; index < players.size(); index++) {
            DamageableFakePlayer player = players.get(index);
            IBatteryItem helmet = helmets.get(index);
            ItemStack source = player.getItemBySlot(EquipmentSlot.HEAD);
            helmet.setEnergy(source, 0);
            player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            player.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            player.getInventory().tick();
            var headPos = BlockPos.containing(player.position().x, player.position().y + 1, player.position().z);
            check(helmet.getEnergy(source) == expected[index], phase + " generation " + IDS[index]
                + " actual=" + helmet.getEnergy(source)
                + " raining=" + world.isRaining()
                + " rainingAt=" + world.isRainingAt(headPos)
                + " canSeeSky=" + world.canSeeSky(headPos)
                + " precipitation=" + world.getBiome(headPos).value().hasPrecipitation());
            check(energy(player.getInventory().getItem(0)) == 0, phase + " does not scan carried inventory " + IDS[index]);
            long once = helmet.getEnergy(source);
            player.getInventory().tick();
            check(helmet.getEnergy(source) == once, phase + " same-tick deduplication " + IDS[index]);
            rows.get(index).put(phase + "_stored_eu", once);
        }
    }

    private ItemStack armor(String id, long stored) {
        ItemStack stack = new ItemStack(item(id));
        IBatteryItem battery = (IBatteryItem) stack.getItem();
        battery.setEnergy(stack, stored);
        return stack;
    }

    private void transferPhase(ServerLevel world) {
        world.setDayTime(6000);
        world.setWeatherParameters(0, 0, false, false);
        world.setRainLevel(0.0F);
        world.setThunderLevel(0.0F);
        for (int index = 0; index < players.size(); index++) {
            DamageableFakePlayer player = players.get(index);
            IBatteryItem helmet = helmets.get(index);
            ItemStack source = player.getItemBySlot(EquipmentSlot.HEAD);
            helmet.setEnergy(source, LIMIT[index]);

            ItemStack chest = armor("mio_icif:armor/item_armor_nano_chestplate", 999_999);
            ItemStack legs = armor("mio_icif:armor/item_armor_nano_leggings", 999_998);
            ItemStack feet = armor("mio_icif:armor/item_armor_nano_boots", 0);
            player.setItemSlot(EquipmentSlot.CHEST, chest);
            player.setItemSlot(EquipmentSlot.LEGS, legs);
            player.setItemSlot(EquipmentSlot.FEET, feet);
            player.getInventory().tick();

            check(energy(chest) == 1_000_000, "chest first in armor-only order " + IDS[index]);
            check(energy(legs) == 1_000_000, "legs second in armor-only order " + IDS[index]);
            check(energy(feet) == LIMIT[index] - 3L, "boots receive remaining transfer budget " + IDS[index]);
            check(energy(player.getInventory().getItem(0)) == 0, "carried battery excluded " + IDS[index]);
            check(helmet.getEnergy(source) == DAY[index], "generation remains after exact transfer cap " + IDS[index]);
            check(helmet.getEnergy(source) + 1 + 2 + energy(feet) == LIMIT[index] + DAY[index],
                "transfer conservation " + IDS[index]);

            long fingerprint = helmet.getEnergy(source) + energy(chest) * 11 + energy(legs) * 101
                + energy(feet) * 1009 + energy(player.getInventory().getItem(0)) * 10007;
            player.getInventory().tick();
            long duplicate = helmet.getEnergy(source) + energy(chest) * 11 + energy(legs) * 101
                + energy(feet) * 1009 + energy(player.getInventory().getItem(0)) * 10007;
            check(fingerprint == duplicate, "transfer same-tick deduplication " + IDS[index]);
            rows.get(index).put("armor_transfer_eu", LIMIT[index]);
            rows.get(index).put("post_transfer_source_eu", helmet.getEnergy(source));
        }
    }

    private void damagePhase(ServerLevel world) {
        for (int index = 0; index < players.size(); index++) {
            DamageableFakePlayer player = players.get(index);
            IBatteryItem helmet = helmets.get(index);
            ItemStack source = player.getItemBySlot(EquipmentSlot.HEAD);
            player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            player.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            helmet.setEnergy(source, CAPACITY[index]);
            player.setHealth(player.getMaxHealth());
            player.invulnerableTime = 0;
            float beforeHealth = player.getHealth();
            long beforeEnergy = helmet.getEnergy(source);
            var damageSource = world.damageSources().generic();
            boolean invulnerable = player.isInvulnerableTo(damageSource);
            boolean hurt = player.hurt(damageSource, 10.0F);
            float healthLoss = beforeHealth - player.getHealth();
            long energySpent = beforeEnergy - helmet.getEnergy(source);
            boolean electric = MioIcifAPI.instance().getItemAPI().isElectricArmor(source);
            long apiEnergy = MioIcifAPI.instance().getItemAPI().getElectricArmorStored(source);
            long energyPerDamage = ((IElectricArmorItem) source.getItem()).getEnergyPerDamage();
            float ratio = ((IElectricArmorItem) source.getItem()).getDamageAbsorptionRatio(EquipmentSlot.HEAD);
            String diagnostic = " " + IDS[index] + " invulnerable=" + invulnerable
                + " hurt=" + hurt + " electric=" + electric + " apiEnergy=" + apiEnergy
                + " ratio=" + ratio + " energyPerDamage=" + energyPerDamage
                + " healthLoss=" + healthLoss;
            check(energySpent > 0, "damage event debits solar helmet" + diagnostic);
            if (index == 0) {
                check(healthLoss > 0.0F && healthLoss < 10.0F, "advanced helmet applies partial damage absorption");
            } else {
                check(Math.abs(healthLoss) < 0.0001F, "full damage absorption " + IDS[index]);
            }
            rows.get(index).put("damage_test_health_loss", healthLoss);
            rows.get(index).put("damage_test_energy_spent", energySpent);
        }
    }

    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        if (tick == 5) {
            setup(world);
            generationPhase(world, DAY, "day_clear");
        }
        if (tick == 6) {
            world.setDayTime(18000);
            world.setWeatherParameters(0, 0, false, false);
            generationPhase(world, NIGHT, "night_clear");
        }
        if (tick == 7) {
            world.setDayTime(6000);
            world.setWeatherParameters(0, 6000, true, false);
            world.setRainLevel(1.0F);
            world.setThunderLevel(0.0F);
            // Server weather levels interpolate during the tick; sample after one
            // server tick so day-rain observes the actual local precipitation state.
            return null;
        }
        if (tick == 8) {
            generationPhase(world, NIGHT, "day_rain");
        }
        if (tick == 9) transferPhase(world);
        if (tick != 10) return null;

        damagePhase(world);
        var result = new LinkedHashMap<String, Object>();
        result.put("passed", true);
        result.put("assertions", assertions);
        result.put("rows", rows);
        result.put("scope", "three registered ASP solar helmets: EU constants, clear-day/night/rain generation, armor-only transfer cap, duplicate-tick guard and electric-armor damage event");
        result.put("not_claimed", List.of("underwater behavior", "dimension-specific generation", "connected client", "multiplayer", "performance", "production"));
        Files.writeString(Path.of("solar-headgear-r178-result.json"), new Gson().toJson(result));
        System.out.println("SCEX_SOLAR_HEADGEAR_R178_PASS assertions=" + assertions);
        return result;
    }
}
