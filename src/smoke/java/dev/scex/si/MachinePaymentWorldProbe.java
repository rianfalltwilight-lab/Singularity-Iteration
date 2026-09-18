// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_blast_furnace_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_megnetizer;
import dev.scex.energy.EnergyAmount;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Placed machines, natural block ticks, complete ordinary saves; no manual machine work calls. */
public final class MachinePaymentWorldProbe {
    private static final Path OUTPUT = Path.of("machine-payment-world-r134-result.json");
    private static final Path TRACE = Path.of("machine-payment-world-r134-observations.jsonl");
    private static final long HALF = EnergyAmount.UNITS / 2;
    private static final BlockPos MAGNET = new BlockPos(23064, 80, 100);
    private static final BlockPos DENSE = new BlockPos(23119, 80, 103);
    private static final BlockPos BOUNDARY = new BlockPos(24008, 80, 100);
    private final List<BlockPos> placed = new ArrayList<>();
    private final List<Map<String,Object>> cases = new ArrayList<>();
    private final mio_icif_blast_furnace_elc[] furnaces = new mio_icif_blast_furnace_elc[6];
    private final String[] furnaceNames = {"paid_40", "insufficient", "full_output", "component_mismatch", "callback_consumes_input", "callback_removes_owner"};
    private ServerLevel world;
    private mio_icif_megnetizer magnet, dense, boundary;
    private ProbePlayer target;
    private ItemStack recipeResult, mismatch;
    private BlockPos missing;
    private int startTick = -1, assertions, paidTicks, inputCallbackCount, removeCallbackCount;
    private int magneticTicks;
    private long lastMagnetBalance;
    private double initialY, quietY;
    private boolean finished, furnaceDone, magnetDone;

    /** Normal ServerPlayer + Player/LivingEntity physics. No move, travel or damage override. */
    private static final class ProbePlayer extends ServerPlayer {
        int naturalTicks;
        ProbePlayer(ServerLevel world) {
            super(world.getServer(), world, new GameProfile(new UUID(134, 1), "SI_R134_Magnet"), ClientInformation.createDefault());
            var connectionHolder = new FakePlayer(world, new GameProfile(new UUID(134, 2), "SI_R134_Conn"));
            connection = connectionHolder.connection;
            connection.player = this;
        }
        @Override public void tick() {
            super.tick();
            // The real connection listener normally invokes this public method.
            // A detached dummy connection is not registered in the server network loop.
            super.doTick();
            naturalTicks++;
        }
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError("R134 machine payment: " + label);
    }

    public Map<String,Object> inspect(ServerLevel level, int tick) throws Exception {
        if (finished || tick < 20) return null;
        try {
            if (startTick < 0) {
                check(!Files.exists(OUTPUT) && !Files.exists(TRACE), "fresh result paths");
                world = level; startTick = tick;
                setup();
                return null;
            }
            check(world == level && world.getServer().isSameThread(), "same server thread and world");
            check(tick - startTick < 130, "bounded natural tick deadline");
            inspectFurnaces();
            inspectMagnet();
            if (tick - startTick == 1) inspectAuxiliaryMagnets();
            check(world.getChunkSource().getChunkNow(missing.getX() >> 4, missing.getZ() >> 4) == null,
                "boundary scanning never made the observed absent chunk FULL");
            if (!furnaceDone || !magnetDone || tick - startTick < 45) return null;
            var result = result(true);
            cleanup(); finished = true;
            Files.writeString(OUTPUT, new Gson().toJson(result), StandardOpenOption.CREATE_NEW);
            return result;
        } catch (Exception | AssertionError failure) {
            var result = result(false); result.put("failure", failure.toString());
            try { if (!Files.exists(OUTPUT)) Files.writeString(OUTPUT, new Gson().toJson(result), StandardOpenOption.CREATE_NEW); }
            finally { cleanup(); finished = true; }
            throw failure;
        }
    }

    private Map<String,Object> result(boolean passed) {
        var result = new LinkedHashMap<String,Object>();
        result.put("passed", passed); result.put("assertions", assertions); result.put("cases", cases);
        result.put("scope", "SI candidate constants; natural machine ticks and real ServerPlayer class running native doTick physics with a dummy connection. No connected client, visual, full-pack, or cold chunk-unload acceptance.");
        result.put("scan_budget_basis", "512 distinct visited positions is a declared SI candidate budget, not an IC2 observation.");
        return result;
    }

    private void setup() {
        int input = mio_icif_blast_furnace_elc.getOrCreateLayout().getInputSlots()[0];
        int output = mio_icif_blast_furnace_elc.getOrCreateLayout().getOutputSlots()[0];
        for (int i = 0; i < furnaces.length; i++) {
            var at = new BlockPos(23000 + i * 8, 80, 100);
            furnaces[i] = placeMachine(at, "mio_icif:producer/block_blast_furnace_elc", mio_icif_blast_furnace_elc.class);
            furnaces[i].setItem(input, new ItemStack(Items.IRON_INGOT, 1));
            furnaces[i].getEnergyStorageInternal().setEnergy(i == 1 ? 7999 : 320000);
            check(furnaces[i].getEnergyStorageInternal().getMaxExtract() == 0, "external extraction zero for " + furnaceNames[i]);
            check(furnaces[i].getEnergyStorageInternal().scexNetworkControlled(), "independent native owner " + furnaceNames[i]);
        }
        recipeResult = furnaces[0].getPrimaryResultItem().copy();
        check(!recipeResult.isEmpty() && recipeResult.getCount() == 1, "normal iron recipe has one primary result");
        furnaces[1].getEnergyStorageInternal().scexLoadFraction(HALF);
        var full = recipeResult.copy(); full.setCount(full.getMaxStackSize());
        furnaces[2].setItem(output, full);
        mismatch = recipeResult.copy(); mismatch.set(DataComponents.CUSTOM_NAME, Component.literal("SCEX R134 component mismatch"));
        check(!ItemStack.isSameItemSameComponents(recipeResult, mismatch), "real output component mismatch fixture");
        furnaces[3].setItem(output, mismatch.copy());
        furnaces[4].setWorkCompleteCallback((level, pos, result) -> {
            inputCallbackCount++;
            check(furnaces[4].getEnergyStorageInternal().getAmount() == 0, "completion callback sees all forty paid ticks");
            furnaces[4].setItem(input, ItemStack.EMPTY);
        });
        furnaces[5].setWorkCompleteCallback((level, pos, result) -> {
            removeCallbackCount++;
            check(world.removeBlock(pos, false), "completion callback removed real owner");
        });

        magnet = placeMachine(MAGNET, "mio_icif:producer/block_magnetizer", mio_icif_megnetizer.class);
        check(magnet.getEnergyStorageInternal().scexNetworkControlled(), "magnet independent native owner");
        magnet.getEnergyStorageInternal().setEnergy(100);
        put(MAGNET.east(), Blocks.IRON_BARS.defaultBlockState());
        for (int dx = 0; dx < 4; dx++) for (int dz = -2; dz < 2; dz++)
            put(MAGNET.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
        target = new ProbePlayer(world);
        target.setGameMode(GameType.SURVIVAL);
        target.setPos(MAGNET.getX() + 1.5, MAGNET.getY(), MAGNET.getZ() - 0.5);
        target.setNoGravity(true);
        target.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        world.addNewPlayer(target);
        check(world.getEntitiesOfClass(ProbePlayer.class, new AABB(MAGNET).inflate(4)).contains(target), "player entered real entity index");
        check(target.isControlledByLocalInstance(), "native server-side physics is effective");
        initialY = target.getY();

        dense = placeMachine(DENSE, "mio_icif:producer/block_magnetizer", mio_icif_megnetizer.class);
        dense.getEnergyStorageInternal().setEnergy(10);
        for (int x = 23120; x <= 23130; x++) for (int y = 80; y <= 86; y++) for (int z = 98; z <= 108; z++)
            put(new BlockPos(x, y, z), Blocks.IRON_BARS.defaultBlockState());

        boundary = placeMachine(BOUNDARY, "mio_icif:producer/block_magnetizer", mio_icif_megnetizer.class);
        boundary.getEnergyStorageInternal().setEnergy(10);
        for (int distance = 1; distance <= 128; distance++) {
            var at = BOUNDARY.east(distance);
            if (world.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4) == null) { missing = at; break; }
        }
        check(missing != null, "fixture contains a real absent chunk within bounded lookup");
        // Only write already FULL chunks. Flag 18 also suppresses neighbor-shape updates crossing the boundary.
        for (int x = BOUNDARY.getX() + 1; x < missing.getX(); x++) put(new BlockPos(x, 80, 100), Blocks.IRON_BARS.defaultBlockState());
        check(world.getChunkSource().getChunkNow(missing.getX() >> 4, missing.getZ() >> 4) == null, "placement kept target chunk absent");
    }

    private void inspectFurnaces() throws Exception {
        if (furnaceDone) return;
        long stored = furnaces[0].getEnergyStorageInternal().getAmount();
        check((320000 - stored) % 8000 == 0, "whole 8000 EU payment per furnace tick");
        int now = (int)((320000 - stored) / 8000);
        check(now == paidTicks + 1, "exactly one paid furnace tick per natural world tick");
        paidTicks = now;
        check(now <= 40, "no extra paid operation");
        check(furnaces[0].getProgress() == (now == 40 ? 0 : now), "single progress increment: " + now);
        if (now < 40) {
            check(furnaces[0].getOutputItem().isEmpty() && furnaces[0].getInputItem().getCount() == 1, "no early output or input loss at " + now);
            check(inputCallbackCount == 0 && removeCallbackCount == 0, "callbacks wait for forty paid ticks");
        }
        check(furnaces[1].getEnergyStorageInternal().getAmount() == 7999 && furnaces[1].getEnergyStorageInternal().scexSavedFraction() == HALF,
            "7999.5 EU cannot buy 8000 EU and remains exact");
        check(furnaces[1].getProgress() == 0 && furnaces[1].getOutputItem().isEmpty(), "insufficient buffer does no work");
        for (int index : new int[]{2, 3}) {
            check(furnaces[index].getEnergyStorageInternal().getAmount() == 320000 && furnaces[index].getProgress() == 0,
                "blocked output never pays: " + furnaceNames[index]);
            check(furnaces[index].getInputItem().getCount() == 1, "blocked output preserves input");
        }
        check(furnaces[2].getOutputItem().getCount() == recipeResult.getMaxStackSize(), "full output preserved");
        check(ItemStack.matches(furnaces[3].getOutputItem(), mismatch), "component mismatch preserved exactly");
        record(Map.of("case", "furnace_natural_tick", "paid_ticks", now, "energy", stored, "progress", furnaces[0].getProgress(),
            "output_count", furnaces[0].getOutputItem().getCount()));
        if (now != 40) return;
        check(furnaces[0].getInputItem().isEmpty() && ItemStack.matches(furnaces[0].getOutputItem(), recipeResult), "one atomic recipe after forty paid ticks");
        check(inputCallbackCount == 1 && furnaces[4].getInputItem().isEmpty() && furnaces[4].getOutputItem().isEmpty(), "callback input removal never creates unpaid material");
        check(removeCallbackCount == 1 && furnaces[5].isRemoved() && furnaces[5].getOutputItem().isEmpty(), "removed owner never creates output");
        check(world.getBlockEntity(furnaces[5].getBlockPos()) == null && world.getBlockState(furnaces[5].getBlockPos()).isAir(),
            "post-callback lit update never resurrects a removed owner");
        for (int i = 0; i < furnaces.length; i++) {
            cases.add(Map.of("case", furnaceNames[i], "saved_after", furnaces[i].saveWithFullMetadata(world.registryAccess()).toString()));
        }
        furnaceDone = true;
    }

    private void inspectMagnet() throws Exception {
        if (magnetDone) return;
        magneticTicks++;
        long energy = magnet.getEnergyStorageInternal().getAmount();
        if (magneticTicks <= 3) {
            check(energy == 100 - magneticTicks * 5L, "magnet pays 5 EU per natural tick");
            check(magnet.getMagnetizedFences().contains(MAGNET.east()), "paid live connected fence");
            check(target.getDeltaMovement().y > 0, "iron boots receive actual upward velocity");
            if (magneticTicks >= 2) check(target.getY() > initialY, "native Player physics increased actual Y");
        } else if (magneticTicks <= 5) {
            check(target.getY() == quietY && target.getDeltaMovement().y == 0, "bare feet do not rise");
            check(energy == 100 - magneticTicks * 5L, "continuous magnet tick remains paid with no eligible target");
        } else if (magneticTicks <= 7) {
            check(energy == 4 && magnet.getEnergyStorageInternal().scexSavedFraction() == HALF, "4.5 EU remains insufficient and exact");
            check(magnet.getMagnetizedFences().isEmpty() && !magnet.isWorking(), "loss of power clears derived field");
            check(target.getY() == quietY && target.getDeltaMovement().y == 0, "unpaid machine gives no lift");
        } else if (magneticTicks <= 9) {
            check(energy == 100 && magnet.getMagnetizedFences().isEmpty(), "redstone disabled tick does not pay or magnetize");
            check(target.getY() == quietY && target.getDeltaMovement().y == 0, "redstone disabled tick gives no lift");
        } else if (magneticTicks == 10) {
            check(energy == 95 && magnet.getMagnetizedFences().contains(MAGNET.east()), "redstone removal rebuilds paid field");
            check(target.getDeltaMovement().y > 0, "lift resumes from real power");
        } else if (magneticTicks == 11) {
            check(energy == 90 && magnet.getMagnetizedFences().isEmpty(), "removed fence invalidated on next paid tick");
            check(target.getDeltaMovement().y == 0, "removed fence gives no stale lift");
        } else if (magneticTicks == 12) {
            check(energy == 85 && magnet.getMagnetizedFences().contains(MAGNET.east()), "restored fence rediscovers connectivity");
        } else {
            check(magnet.isRemoved() && magnet.getMagnetizedFences().isEmpty() && energy == lastMagnetBalance, "removed owner releases field and cannot spend");
            check(target.getDeltaMovement().y == 0 && target.getY() == quietY, "removed owner gives no later lift");
        }
        record(Map.of("case", "magnet_natural_tick", "tick", magneticTicks, "energy", energy, "fraction", magnet.getEnergyStorageInternal().scexSavedFraction(),
            "player_y", target.getY(), "player_dy", target.getDeltaMovement().y, "natural_player_ticks", target.naturalTicks,
            "cached_fences", magnet.getMagnetizedFences().size(), "visited_positions", magnet.scexLastFenceScanPositions()));
        if (magneticTicks == 3) { target.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY); quiet(); }
        if (magneticTicks == 5) {
            target.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            magnet.getEnergyStorageInternal().setEnergy(4); magnet.getEnergyStorageInternal().scexLoadFraction(HALF); quiet();
        }
        if (magneticTicks == 7) {
            magnet.getEnergyStorageInternal().scexLoadFraction(0); magnet.getEnergyStorageInternal().setEnergy(100);
            put(MAGNET.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            check(world.hasNeighborSignal(MAGNET), "real adjacent redstone signal"); quiet();
        }
        if (magneticTicks == 9) { world.removeBlock(MAGNET.west(), false); check(!world.hasNeighborSignal(MAGNET), "redstone removed"); }
        if (magneticTicks == 10) { world.removeBlock(MAGNET.east(), false); quiet(); }
        if (magneticTicks == 11) put(MAGNET.east(), Blocks.IRON_BARS.defaultBlockState());
        if (magneticTicks == 12) {
            cases.add(Map.of("case", "magnet_player_physics", "initial_y", initialY, "lifted_y", target.getY(),
                "saved_before_removal", magnet.saveWithFullMetadata(world.registryAccess()).toString()));
            lastMagnetBalance = energy;
            check(world.removeBlock(MAGNET, false), "normal owner removal");
            check(magnet.getMagnetizedFences().isEmpty() && magnet.scexLastFenceScanPositions() == 0, "setRemoved releases derived state immediately");
            quiet();
        }
        if (magneticTicks == 14) {
            magnetDone = true;
            world.removePlayerImmediately(target, Entity.RemovalReason.DISCARDED); target = null;
        }
    }

    private void inspectAuxiliaryMagnets() throws Exception {
        check(dense.getEnergyStorageInternal().getAmount() == 5, "dense field pays exactly 5 EU");
        check(dense.scexLastFenceScanPositions() == 512, "large connected fixture reaches exact 512 position cap");
        check(dense.getMagnetizedFences().size() > 1 && dense.getMagnetizedFences().size() < 847, "budget bounds actual connected cache");
        var tag = dense.saveWithFullMetadata(world.registryAccess());
        tag.putIntArray("magnetized_x", new int[]{1, 2, 3});
        tag.putIntArray("magnetized_y", new int[0]);
        tag.putIntArray("magnetized_z", new int[]{Integer.MAX_VALUE});
        tag.putBoolean("is_working", true);
        var loaded = BlockEntity.loadStatic(DENSE, dense.getBlockState(), tag, world.registryAccess());
        check(loaded instanceof mio_icif_megnetizer, "ordinary full NBT reconstructs real magnetizer type");
        var loadedMagnet = (mio_icif_megnetizer)loaded;
        check(loadedMagnet.getMagnetizedFences().isEmpty() && !loadedMagnet.isWorking()
            && loadedMagnet.scexLastFenceScanPositions() == 0, "malformed legacy arrays never restore an active field");
        check(loadedMagnet.getEnergyStorageInternal().getAmount() == 5, "ordinary load preserves paid energy balance");
        int count = dense.getMagnetizedFences().size();
        // Direct lifecycle-contract check, separately labelled from a real chunk unload.
        dense.onChunkUnloaded();
        check(dense.getMagnetizedFences().isEmpty() && dense.scexLastFenceScanPositions() == 0 && !dense.isWorking(),
            "public chunk-unloaded lifecycle callback releases derived field");
        check(world.removeBlock(DENSE, false), "retire lifecycle fixture after direct callback");
        check(boundary.getEnergyStorageInternal().getAmount() == 5 && boundary.getMagnetizedFences().contains(BOUNDARY.east()),
            "boundary fixture performs actual paid loaded-prefix scan");
        check(boundary.scexLastFenceScanPositions() <= 512, "boundary scan budget");
        check(world.getChunkSource().getChunkNow(missing.getX() >> 4, missing.getZ() >> 4) == null, "boundary remains absent after paid scan");
        var row = Map.<String,Object>of("case", "dense_and_boundary", "dense_visited", 512, "dense_fences", count,
            "boundary_missing_pos", missing.toShortString(), "malformed_full_nbt", tag.toString(),
            "loaded_full_nbt", loadedMagnet.saveWithFullMetadata(world.registryAccess()).toString(),
            "lifecycle_scope", "direct onChunkUnloaded contract; actual cold unload remains a separate fixture");
        cases.add(row); record(row);
    }

    private void quiet() { target.setDeltaMovement(Vec3.ZERO); quietY = target.getY(); }
    private void record(Map<String,Object> row) throws Exception {
        Files.writeString(TRACE, new Gson().toJson(row) + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    private <T extends BlockEntity> T placeMachine(BlockPos at, String id, Class<T> type) {
        check(world.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4) != null
            && world.shouldTickBlocksAt(ChunkPos.asLong(at)), "parent provided loaded ticking chunk " + at);
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        check(block != Blocks.AIR, "registered block " + id);
        put(at, block.defaultBlockState());
        var entity = world.getBlockEntity(at);
        check(type.isInstance(entity), "normal factory " + id);
        return type.cast(entity);
    }
    private void put(BlockPos at, net.minecraft.world.level.block.state.BlockState state) {
        check(world.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4) != null, "fixture write never loads chunks");
        world.setBlock(at, state, 18);
        placed.add(at.immutable());
    }
    private void cleanup() {
        if (world == null) return;
        if (target != null) { world.removePlayerImmediately(target, Entity.RemovalReason.DISCARDED); target = null; }
        for (int i = placed.size() - 1; i >= 0; i--) {
            var at = placed.get(i);
            if (world.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4) != null) world.setBlock(at, Blocks.AIR.defaultBlockState(), 18);
        }
        placed.clear();
    }
}
