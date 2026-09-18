// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.mojang.authlib.GameProfile;
import com.singularity_iteration.mio_icif.api.item.IBatteryItem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Actual registered-item coverage for the R145 independent solar helmet and energy-pack base. */
public final class EquipmentSourceReplacementWorldProbe {
    private static final BlockPos SOLAR_POS = new BlockPos(1500, 90, 0);
    private static final BlockPos SOLAR_ROOF = SOLAR_POS.above(2);
    private final List<String> groups = new ArrayList<>();
    private final List<FakePlayer> packPlayers = new ArrayList<>();
    private final List<IBatteryItem> packItems = new ArrayList<>();
    private final int[] rates = {32, 128, 512, 2048};
    private int assertions;
    private FakePlayer solarPlayer;
    private IBatteryItem solarItem;
    private FakePlayer unwornPlayer;
    private IBatteryItem unwornPack;

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError("R145 " + label);
    }

    private void done(String name) {
        check(!groups.contains(name), "unique group " + name);
        groups.add(name);
        System.out.println("SCEX_EQUIPMENT_R145_CASE_PASS " + name);
    }

    private Item item(String id) {
        Item result = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        check(result != Items.AIR, "registered " + id);
        return result;
    }

    private FakePlayer player(ServerLevel world, int index, BlockPos pos) {
        FakePlayer player = new FakePlayer(world, new GameProfile(new UUID(14500, index + 1), "SI_R145_" + index));
        player.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        return player;
    }

    private long energy(ItemStack stack) {
        return stack.getItem() instanceof IBatteryItem battery ? battery.getEnergy(stack) : 0;
    }

    private long solarTotal() {
        return energy(solarPlayer.getItemBySlot(EquipmentSlot.HEAD))
            + energy(solarPlayer.getItemBySlot(EquipmentSlot.CHEST));
    }

    private void setup(ServerLevel world) {
        world.setDayTime(6000);
        world.setWeatherParameters(0, 0, false, false);
        world.setBlockAndUpdate(SOLAR_ROOF, Blocks.AIR.defaultBlockState());
        Item solar = item("mio_icif:normal/item_solar_helmet");
        solarItem = (IBatteryItem) solar;
        solarPlayer = player(world, 0, SOLAR_POS);
        solarPlayer.setItemSlot(EquipmentSlot.HEAD, new ItemStack(solar));
        solarPlayer.setItemSlot(EquipmentSlot.CHEST, new ItemStack(item("mio_icif:armor/item_armor_batpack")));
        check(solarItem.getMaxEnergy() == 8000 && solarItem.getEnergy(solarPlayer.getItemBySlot(EquipmentSlot.HEAD)) == 0,
            "base solar public ABI and empty default");

        String[] ids = {"armor/item_armor_batpack", "armor/item_armor_advbatpack",
            "armor/item_armor_energypack", "armor/item_armor_lappack"};
        for (int i = 0; i < ids.length; i++) {
            Item pack = item("mio_icif:" + ids[i]);
            IBatteryItem battery = (IBatteryItem) pack;
            FakePlayer player = player(world, i + 1, SOLAR_POS.offset((i + 1) * 3, 0, 0));
            ItemStack source = new ItemStack(pack);
            battery.setEnergy(source, 30000);
            player.setItemSlot(EquipmentSlot.CHEST, source);
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item("scex_si_smoke:tool63")));
            player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(item("scex_si_smoke:tool63")));
            packPlayers.add(player);
            packItems.add(battery);
        }
        unwornPlayer = player(world, 10, SOLAR_POS.offset(24, 0, 0));
        ItemStack carried = new ItemStack(item("mio_icif:armor/item_armor_energypack"));
        unwornPack = (IBatteryItem) carried.getItem();
        unwornPack.setEnergy(carried, 5000);
        unwornPlayer.getInventory().setItem(1, carried);
        unwornPlayer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item("scex_si_smoke:tool63")));
        done("registered-independent-sources");
    }

    private void tickPackPlayers(int elapsed) {
        for (int i = 0; i < packPlayers.size(); i++) {
            FakePlayer player = packPlayers.get(i);
            IBatteryItem pack = packItems.get(i);
            player.getInventory().tick();
            long expected = Math.min(20000L, (long) rates[i] * elapsed);
            long main = energy(player.getMainHandItem());
            long off = energy(player.getOffhandItem());
            long source = pack.getEnergy(player.getItemBySlot(EquipmentSlot.CHEST));
            check(main + off == expected, "bounded hand transfer " + i + " tick=" + elapsed);
            check(source + main + off == 30000, "pack conservation " + i + " tick=" + elapsed);
            long before = source + main * 100000L + off * 10000000000L;
            player.getInventory().tick();
            long after = pack.getEnergy(player.getItemBySlot(EquipmentSlot.CHEST))
                + energy(player.getMainHandItem()) * 100000L + energy(player.getOffhandItem()) * 10000000000L;
            check(before == after, "same game tick deduplicated " + i + " tick=" + elapsed);
            if (elapsed == 10) done("pack-rate-and-priority-" + i);
        }
        unwornPlayer.getInventory().tick();
        check(unwornPack.getEnergy(unwornPlayer.getInventory().getItem(1)) == 5000
            && energy(unwornPlayer.getMainHandItem()) == 0, "unworn pack inactive " + elapsed);
        if (elapsed == 10) done("unworn-pack-inactive");
    }

    private void tickSolar(long expected, String label) {
        solarPlayer.getInventory().tick();
        check(solarTotal() == expected, label + " total=" + solarTotal() + " expected=" + expected);
        long once = solarTotal();
        solarPlayer.getInventory().tick();
        check(solarTotal() == once, label + " duplicate tick");
    }

    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        if (tick == 30) setup(world);
        if (tick >= 31 && tick <= 40) {
            int elapsed = tick - 30;
            tickSolar(elapsed, "open-day");
            tickPackPlayers(elapsed);
            if (tick == 40) {
                done("solar-open-day-one-per-tick");
                world.setBlockAndUpdate(SOLAR_ROOF, Blocks.STONE.defaultBlockState());
            }
        }
        if (tick >= 41 && tick <= 45) tickSolar(10, "blocked-day");
        if (tick == 45) {
            done("solar-blocked-zero");
            world.setBlockAndUpdate(SOLAR_ROOF, Blocks.AIR.defaultBlockState());
            world.setDayTime(18000);
        }
        if (tick >= 46 && tick <= 50) tickSolar(10, "open-night");
        if (tick == 50) {
            done("solar-night-zero");
            world.setDayTime(6000);
        }
        if (tick >= 51 && tick <= 55) tickSolar(10 + tick - 50, "reopened-day");
        if (tick == 55) done("solar-resumes-after-environment");
        if (tick == 60) {
            List<FakePlayer> all = new ArrayList<>(packPlayers);
            all.add(solarPlayer);
            all.add(unwornPlayer);
            for (int i = 0; i < all.size(); i++) {
                var saved = all.get(i).getInventory().save(new net.minecraft.nbt.ListTag());
                FakePlayer restored = player(world, 30 + i, SOLAR_POS.offset(40 + i * 2, 0, 0));
                restored.getInventory().load(saved);
                long before = all.get(i).getInventory().items.stream().mapToLong(this::energy).sum()
                    + all.get(i).getInventory().armor.stream().mapToLong(this::energy).sum()
                    + all.get(i).getInventory().offhand.stream().mapToLong(this::energy).sum();
                long after = restored.getInventory().items.stream().mapToLong(this::energy).sum()
                    + restored.getInventory().armor.stream().mapToLong(this::energy).sum()
                    + restored.getInventory().offhand.stream().mapToLong(this::energy).sum();
                check(before == after, "inventory serialization " + i);
            }
            done("inventory-serialization");
        }
        if (tick == 65) {
            check(groups.size() == 11, "all groups complete");
            Map<String, Object> result = Map.of("passed", true, "groups", groups, "assertions", assertions,
                "reference_scope", "solar 1 EU/t day/open and zero blocked/night; equipment-pack transfer is independent SI policy",
                "player_scope", "server FakePlayer Inventory.tick; connected client and multiplayer NOT_RUN");
            Files.writeString(Path.of("equipment-source-r145-result.json"), new com.google.gson.Gson().toJson(result));
            return result;
        }
        return Map.of("groups", groups, "assertions", assertions, "solar_total", solarPlayer == null ? 0 : solarTotal());
    }
}
