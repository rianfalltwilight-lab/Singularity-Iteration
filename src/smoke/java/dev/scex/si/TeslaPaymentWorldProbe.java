// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_tesla;
import com.singularity_iteration.mio_icif.event.mio_icif_DamageTypes;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** Real placed Tesla + natural block ticks + normal cancellable damage events. */
public final class TeslaPaymentWorldProbe {
    private enum Scenario { FIRST_PAID, ALL_CANCELLED, CALLBACK_DELTAS, MULTI_TARGET, REMOVE_OWNER }
    private static final int WINDOW = 25;
    private static final int WARMUP = 65;
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private final BlockPos base;
    private BlockPos at;
    private final int firstTick;
    private final List<ProbePlayer> targets = new ArrayList<>();
    private final List<Map<String,Object>> results = new ArrayList<>();
    private ServerLevel world;
    private mio_icif_tesla machine;
    private Scenario scenario;
    private int assertions, stage = -1, stageTick, armedTick, eventCount;
    private long firstObservedBalance = -1;
    private boolean listenerRegistered, finished, armed;

    public TeslaPaymentWorldProbe() { this(new BlockPos(19000, 80, 100), 20); }
    public TeslaPaymentWorldProbe(BlockPos at, int firstTick) {
        if (firstTick < 1) throw new IllegalArgumentException("Positive first tick required");
        this.base = at.immutable(); this.at = base; this.firstTick = firstTick;
    }
    /** Real ServerPlayer tick/hurt paths; the unattached FakePlayer only supplies a dummy connection. */
    private static final class ProbePlayer extends ServerPlayer {
        private int naturalTicks;
        ProbePlayer(ServerLevel world, int stage, int index) {
            super(world.getServer(), world,
                new GameProfile(new UUID(131, stage * 10L + index + 1), "SI_R131_" + stage + "_" + index),
                ClientInformation.createDefault());
            var connectionHolder = new FakePlayer(world,
                new GameProfile(new UUID(132, stage * 10L + index + 1), "SI_R131_Conn_" + stage + "_" + index));
            this.connection = connectionHolder.connection;
            this.connection.player = this;
        }
        @Override public void tick() { super.tick(); naturalTicks++; }
    }
    private void check(boolean value, String label) {
        assertions++; if (!value) throw new AssertionError("R131 Tesla payment: " + label);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void incomingDamage(LivingIncomingDamageEvent event) {
        if (scenario == null || !targets.contains(event.getEntity()) || !event.getSource().is(mio_icif_DamageTypes.TESLA_COIL)) return;
        check(world != null && world.getServer().isSameThread(), "damage callback remains on server thread");
        eventCount++;
        if (firstObservedBalance < 0) {
            firstObservedBalance = machine.getEnergyStorageInternal().getAmount();
            check(firstObservedBalance == 4500, "500 EU reserved before first effect callback");
        }
        if (scenario == Scenario.ALL_CANCELLED || scenario == Scenario.CALLBACK_DELTAS || scenario == Scenario.REMOVE_OWNER) {
            event.setCanceled(true);
        }
        if (scenario == Scenario.CALLBACK_DELTAS) {
            check(eventCount == 1, "one callback for delta case");
            var energy = machine.getEnergyStorageInternal();
            check(energy.generateEnergyInternal(1000, false) == 1000, "callback credits real balance");
            check(energy.consumeEnergyInternal(300, false) == 300, "callback pays independent local work");
            check(energy.getAmount() == 5200, "callback deltas coexist with pending payment");
        }
        if (scenario == Scenario.REMOVE_OWNER) {
            check(eventCount == 1, "owner removal stops further target callbacks");
            check(world.removeBlock(at, false), "callback removes actual owner");
            check(machine.isRemoved(), "original owner removed during callback");
        }
    }

    public Map<String,Object> inspect(ServerLevel level, int tick) throws Exception {
        if (finished || tick < firstTick) return null;
        try {
            if (stage < 0) {
                world = level;
                NeoForge.EVENT_BUS.register(this); listenerRegistered = true;
                begin(0, tick);
                return null;
            }
            check(world == level, "same world for entire probe");
            if (!armed) {
                if (tick - stageTick < WARMUP) return null;
                for (var target : targets) check(target.naturalTicks >= 60, "normal ServerPlayer ticks aged out spawn immunity");
                check(eventCount == 0 && machine.getEnergyStorageInternal().getAmount() == 0, "warmup caused no powered attack");
                machine.getEnergyStorageInternal().setEnergy(5000);
                world.setBlockAndUpdate(at.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                check(world.hasNeighborSignal(at), "natural redstone trigger present");
                armed = true; armedTick = tick;
                return null;
            }
            if (tick - armedTick < WINDOW) return null;
            finishStage();
            if (stage + 1 < Scenario.values().length) {
                begin(stage + 1, tick);
                return null;
            }
            var result = new LinkedHashMap<String,Object>();
            result.put("passed", true); result.put("assertions", assertions); result.put("cases", results);
            result.put("scope", "Natural placed Tesla ticks and normal server damage callbacks; fake targets do not establish connected-player or full-pack acceptance.");
            cleanup(); finished = true;
            Files.writeString(Path.of("tesla-payment-world-result.json"), new Gson().toJson(result));
            return result;
        } catch (Exception | AssertionError failure) {
            cleanup(); finished = true;
            throw failure;
        }
    }

    private void begin(int next, int tick) {
        removeTargets();
        if (stage >= 0) removeFixture();
        stage = next; scenario = Scenario.values()[next]; stageTick = tick; armed = false;
        at = base.offset(next * 32, 0, 0);
        eventCount = 0; firstObservedBalance = -1;
        check(world.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4) != null
                && world.shouldTickBlocksAt(ChunkPos.asLong(at)), "parent provided loaded ticking fixture chunk");
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:producer/block_tesla"));
        check(block != Blocks.AIR, "actual Tesla registry exists");
        // Parent owns the forceload range. Each case is 32 blocks from the previous one.
        world.setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
        check(world.setBlockAndUpdate(at, block.defaultBlockState()), "normal registered Tesla placement");
        check(world.getBlockEntity(at) instanceof mio_icif_tesla, "normal Tesla entity factory");
        machine = (mio_icif_tesla)world.getBlockEntity(at);
        var energy = machine.getEnergyStorageInternal();
        check(energy.getMaxExtract() == 0, "fixture exposes original zero external extraction budget");
        energy.setEnergy(0);
        check(energy.scexSavedFraction() == 0, "new machine has no hidden fractional seed");
        int count = scenario == Scenario.MULTI_TARGET || scenario == Scenario.REMOVE_OWNER ? 3 : scenario == Scenario.ALL_CANCELLED ? 2 : 1;
        for (int i = 0; i < count; i++) {
            var target = new ProbePlayer(world, stage, i);
            target.setGameMode(GameType.SURVIVAL);
            target.setPos(at.getX() + 2.5 + i, at.getY(), at.getZ() + 2.5);
            target.setNoGravity(true);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
            target.setHealth(100);
            target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
            target.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            target.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
            target.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
            world.addNewPlayer(target);
            targets.add(target);
            check(world.getEntitiesOfClass(ProbePlayer.class, new AABB(at).inflate(9)).contains(target), "target joins real world entity index");
        }
        world.removeBlock(at.east(), false);
        check(!world.hasNeighborSignal(at), "warmup is unpowered");
    }

    private void finishStage() {
        long expected = switch (scenario) {
            case FIRST_PAID, MULTI_TARGET -> 4500;
            case CALLBACK_DELTAS -> 5700;
            default -> 5000;
        };
        check(machine.getEnergyStorageInternal().getAmount() == expected, scenario + " exact post-pulse balance");
        check(machine.getEnergyStorageInternal().scexSavedFraction() == 0, "token settled before ordinary save");
        int expectedEvents = scenario == Scenario.REMOVE_OWNER ? 1 : targets.size();
        check(eventCount == expectedEvents, scenario + " exact target callback count");
        check(firstObservedBalance == 4500, "actual effect always saw prepaid balance");
        boolean paid = scenario == Scenario.FIRST_PAID || scenario == Scenario.MULTI_TARGET;
        var observations = new ArrayList<Map<String,Object>>();
        for (var target : targets) {
            int armorDamage = 0;
            for (var slot : ARMOR) armorDamage += target.getItemBySlot(slot).getDamageValue();
            if (paid) {
                check(target.getHealth() < 100, "accepted pulse caused actual damage");
                check(armorDamage > 0, "paid pulse damaged armor");
            } else {
                check(target.getHealth() == 100, "cancelled pulse caused no health loss");
                check(armorDamage == 0, "cancelled pulse caused no armor loss");
            }
            observations.add(Map.of("uuid", target.getUUID().toString(), "health", target.getHealth(), "armor_damage", armorDamage,
                "natural_player_ticks", target.naturalTicks));
        }
        if (scenario == Scenario.REMOVE_OWNER) check(world.getBlockEntity(at) == null, "removed machine was not revived");
        var row = new LinkedHashMap<String,Object>();
        row.put("scenario", scenario.name()); row.put("before_eu", 5000); row.put("during_callback_eu", firstObservedBalance);
        row.put("after_eu", expected); row.put("damage_callbacks", eventCount); row.put("targets", observations);
        row.put("network_controlled", machine.getEnergyStorageInternal().scexNetworkControlled());
        row.put("saved_after", machine.saveWithFullMetadata(world.registryAccess()).toString());
        results.add(row);
        System.out.println("SCEX_TESLA_PAYMENT_CASE_PASS " + scenario);
    }
    private void removeTargets() {
        if (world != null) for (var target : targets) world.removePlayerImmediately(target, Entity.RemovalReason.DISCARDED);
        targets.clear();
    }
    private void cleanup() {
        scenario = null;
        if (listenerRegistered) { NeoForge.EVENT_BUS.unregister(this); listenerRegistered = false; }
        if (world == null) return;
        removeTargets();
        removeFixture();
    }
    private void removeFixture() {
        world.removeBlock(at, false); world.removeBlock(at.east(), false); world.removeBlock(at.below(), false);
    }
}
