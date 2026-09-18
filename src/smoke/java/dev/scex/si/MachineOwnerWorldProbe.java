// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_advanced_miner_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_miner_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_pump_elc;
import dev.scex.si.processing.MachineActionOwner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** Lightweight F05 ownership contracts and real BlockItem placement; not a full protection-mod test. */
public final class MachineOwnerWorldProbe {
    private static final String OWNER_KEY = "scex_machine_action_owner";
    private static final BlockPos PUMP_A = new BlockPos(2450, 90, 0);
    private static final BlockPos MINER_A = PUMP_A.east();
    private static final BlockPos MINER_B = new BlockPos(2460, 90, 0);
    private static final BlockPos ADVANCED_A = new BlockPos(2470, 90, 0);
    private static final BlockPos LEGACY_PUMP = new BlockPos(2480, 90, 0);
    private static final BlockPos LEGACY_MINER = new BlockPos(2490, 90, 0);
    private static final BlockPos LEGACY_ADVANCED = new BlockPos(2500, 90, 0);
    private static final BlockPos REPLACED = new BlockPos(2510, 90, 0);
    private static final GameProfile A = new GameProfile(
        UUID.fromString("306f1474-88f4-4d93-bb7f-9742476cf6cc"), "F05OwnerA");
    private static final GameProfile A_RENAMED = new GameProfile(A.getId(), "F05OwnerA2");
    private static final GameProfile B = new GameProfile(
        UUID.fromString("c80e32ab-95cb-4fb3-964e-42182c1dc95e"), "F05OwnerB");
    private static final GameProfile LEGACY_MINER_PROFILE = new GameProfile(
        UUID.nameUUIDFromBytes("mio_icif:automated_miner".getBytes(StandardCharsets.UTF_8)), "[SI Miner]");
    private static final GameProfile LEGACY_PUMP_PROFILE = new GameProfile(
        UUID.nameUUIDFromBytes("mio_icif:automated_pump".getBytes(StandardCharsets.UTF_8)), "[SI Pump]");

    private final List<String> groups = new ArrayList<>();
    private int assertions;

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError("F05 machine owner " + label);
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:" + id));
    }

    private static boolean identity(MachineActionOwner owner, MachineActionOwner.Kind kind, GameProfile profile) {
        return owner.kind() == kind && owner.canAct() && profile.getId().equals(owner.uuid())
            && profile.getName().equals(owner.name());
    }

    private <T> T place(ServerLevel world, BlockPos pos, String id, GameProfile profile, Class<T> type) {
        var player = FakePlayerFactory.get(world, profile);
        player.getAbilities().instabuild = false;
        world.removeBlock(pos, false);
        check(world.setBlockAndUpdate(pos.below(), Blocks.OBSIDIAN.defaultBlockState()), "placement base " + id);
        var stack = new ItemStack(block(id).asItem());
        check(stack.getItem() instanceof BlockItem, "registered BlockItem " + id);
        check(!stack.has(DataComponents.BLOCK_ENTITY_DATA), "fresh machine item has no packed authority " + id);
        player.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + 2.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var hit = new BlockHitResult(Vec3.atCenterOf(pos.below()).add(0, .5, 0), Direction.UP, pos.below(), false);
        check(((BlockItem) stack.getItem()).place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit)).consumesAction(),
            "real BlockItem placement " + id);
        check(type.isInstance(world.getBlockEntity(pos)), "placed block entity " + id);
        return type.cast(world.getBlockEntity(pos));
    }

    private void lightContracts() {
        var playerA = MachineActionOwner.player(A);
        var playerARenamed = MachineActionOwner.player(A_RENAMED);
        var playerB = MachineActionOwner.player(B);
        var legacyMiner = MachineActionOwner.legacy(LEGACY_MINER_PROFILE);
        var legacyPump = MachineActionOwner.legacy(LEGACY_PUMP_PROFILE);
        check(playerA.canShareAutomationWith(playerARenamed), "same player UUID shares despite profile name refresh");
        check(!playerA.canShareAutomationWith(playerB), "different player UUID rejected");
        check(legacyMiner.canShareAutomationWith(legacyPump), "legacy to legacy handoff allowed");
        check(!playerA.canShareAutomationWith(legacyMiner) && !legacyPump.canShareAutomationWith(playerB),
            "mixed player and legacy rejected");
        check(!MachineActionOwner.invalid().canShareAutomationWith(playerA), "invalid owner rejected");

        var parent = new CompoundTag();
        parent.put("owner", playerA.save());
        check(identity(MachineActionOwner.load(parent, "owner", LEGACY_MINER_PROFILE), MachineActionOwner.Kind.PLAYER, A),
            "player owner codec round trip");
        check(identity(MachineActionOwner.load(new CompoundTag(), "owner", LEGACY_MINER_PROFILE),
            MachineActionOwner.Kind.LEGACY, LEGACY_MINER_PROFILE), "missing owner maps to legacy");
        var future = parent.copy();
        future.getCompound("owner").putInt("version", MachineActionOwner.CURRENT_VERSION + 1);
        check(MachineActionOwner.load(future, "owner", LEGACY_MINER_PROFILE).kind() == MachineActionOwner.Kind.INVALID,
            "future owner schema is inert");
        var malformed = parent.copy();
        malformed.getCompound("owner").remove("uuid");
        check(MachineActionOwner.load(malformed, "owner", LEGACY_MINER_PROFILE).kind() == MachineActionOwner.Kind.INVALID,
            "malformed owner schema is inert");
        groups.add("versioned-light-contract");
    }

    private void realPlacementAndPersistence(ServerLevel world) {
        var pumpA = place(world, PUMP_A, "producer/block_pump_elc", A, mio_icif_pump_elc.class);
        var minerA = place(world, MINER_A, "producer/block_miner_elc", A, mio_icif_miner_elc.class);
        var minerB = place(world, MINER_B, "producer/block_miner_elc", B, mio_icif_miner_elc.class);
        var advancedA = place(world, ADVANCED_A, "producer/block_advanced_miner_elc", A, mio_icif_advanced_miner_elc.class);
        check(identity(pumpA.getActionOwner(), MachineActionOwner.Kind.PLAYER, A), "pump captures real placer profile");
        check(identity(minerA.getActionOwner(), MachineActionOwner.Kind.PLAYER, A), "miner captures real placer profile");
        check(identity(advancedA.getActionOwner(), MachineActionOwner.Kind.PLAYER, A), "advanced miner captures real placer profile");
        check(pumpA.getActionOwner().canShareAutomationWith(minerA.getActionOwner()), "same-owner pump handoff domain");
        check(!pumpA.getActionOwner().canShareAutomationWith(minerB.getActionOwner()), "different-owner pump handoff domain");

        var pumpSaved = pumpA.saveWithoutMetadata(world.registryAccess());
        var minerSaved = minerA.saveWithoutMetadata(world.registryAccess());
        var advancedSaved = advancedA.saveWithoutMetadata(world.registryAccess());
        pumpA.loadAdditional(pumpSaved, world.registryAccess());
        minerA.loadAdditional(minerSaved, world.registryAccess());
        advancedA.loadAdditional(advancedSaved, world.registryAccess());
        check(identity(pumpA.getActionOwner(), MachineActionOwner.Kind.PLAYER, A), "pump owner NBT round trip");
        check(identity(minerA.getActionOwner(), MachineActionOwner.Kind.PLAYER, A), "miner owner NBT round trip");
        check(identity(advancedA.getActionOwner(), MachineActionOwner.Kind.PLAYER, A), "advanced owner NBT round trip");
        groups.add("real-blockitem-and-owner-nbt");

        check(world.setBlockAndUpdate(LEGACY_PUMP, block("producer/block_pump_elc").defaultBlockState()), "legacy pump direct fixture");
        check(world.setBlockAndUpdate(LEGACY_MINER, block("producer/block_miner_elc").defaultBlockState()), "legacy miner direct fixture");
        check(world.setBlockAndUpdate(LEGACY_ADVANCED, block("producer/block_advanced_miner_elc").defaultBlockState()),
            "legacy advanced direct fixture");
        var legacyPump = (mio_icif_pump_elc) world.getBlockEntity(LEGACY_PUMP);
        var legacyMiner = (mio_icif_miner_elc) world.getBlockEntity(LEGACY_MINER);
        var legacyAdvanced = (mio_icif_advanced_miner_elc) world.getBlockEntity(LEGACY_ADVANCED);
        var oldPump = legacyPump.saveWithoutMetadata(world.registryAccess());
        oldPump.getCompound("scex_pump_v1").remove("ActionOwner");
        legacyPump.loadAdditional(oldPump, world.registryAccess());
        var oldMiner = legacyMiner.saveWithoutMetadata(world.registryAccess());
        oldMiner.remove(OWNER_KEY);
        legacyMiner.loadAdditional(oldMiner, world.registryAccess());
        var oldAdvanced = legacyAdvanced.saveWithoutMetadata(world.registryAccess());
        oldAdvanced.remove(OWNER_KEY);
        legacyAdvanced.loadAdditional(oldAdvanced, world.registryAccess());
        check(identity(legacyPump.getActionOwner(), MachineActionOwner.Kind.LEGACY, LEGACY_PUMP_PROFILE), "old pump save legacy owner");
        check(identity(legacyMiner.getActionOwner(), MachineActionOwner.Kind.LEGACY, LEGACY_MINER_PROFILE), "old miner save legacy owner");
        check(identity(legacyAdvanced.getActionOwner(), MachineActionOwner.Kind.LEGACY, LEGACY_MINER_PROFILE),
            "old advanced save legacy owner");
        check(legacyPump.getActionOwner().canShareAutomationWith(legacyMiner.getActionOwner()), "actual legacy pair handoff domain");
        check(!pumpA.getActionOwner().canShareAutomationWith(legacyMiner.getActionOwner()), "actual mixed pair rejected");
        groups.add("legacy-save-compatibility");

        var futureMiner = minerSaved.copy();
        futureMiner.getCompound(OWNER_KEY).putInt("version", MachineActionOwner.CURRENT_VERSION + 1);
        minerB.loadAdditional(futureMiner, world.registryAccess());
        check(minerB.getActionOwner().kind() == MachineActionOwner.Kind.INVALID && !minerB.getActionOwner().canAct(),
            "future machine owner quarantined");
        var malformedPump = pumpSaved.copy();
        malformedPump.getCompound("scex_pump_v1").getCompound("ActionOwner").remove("uuid");
        pumpA.loadAdditional(malformedPump, world.registryAccess());
        long before = pumpA.getEnergyStorageInternal().getAmount();
        check(pumpA.getActionOwner().kind() == MachineActionOwner.Kind.INVALID
                && !pumpA.injectFluid(Fluids.WATER, 1000)
                && pumpA.getEnergyStorageInternal().getAmount() == before,
            "malformed pump owner is inert before payment or output");
        pumpA.loadAdditional(pumpSaved, world.registryAccess());
        check(identity(pumpA.getActionOwner(), MachineActionOwner.Kind.PLAYER, A), "valid owner restore after isolation check");
        groups.add("bad-and-future-state-isolation");
    }

    private void wrenchDropAndReplacement(ServerLevel world) {
        var pump = (mio_icif_pump_elc) world.getBlockEntity(PUMP_A);
        var state = world.getBlockState(PUMP_A);
        var actor = FakePlayerFactory.get(world, A);
        var wrench = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mio_icif:item_tool_wrench")));
        check(!wrench.isEmpty(), "registered wrench");
        var drops = Block.getDrops(state, world, PUMP_A, pump, actor, wrench);
        check(drops.size() == 1 && drops.getFirst().is(state.getBlock().asItem()), "wrench context returns one pump item");
        var packed = drops.getFirst().copy();
        var data = packed.get(DataComponents.BLOCK_ENTITY_DATA);
        check(data == null || !data.copyTag().contains(OWNER_KEY)
                && !data.copyTag().getCompound("scex_pump_v1").contains("ActionOwner"),
            "wrench machine item does not carry action owner");

        var playerB = FakePlayerFactory.get(world, B);
        playerB.getAbilities().instabuild = false;
        world.setBlockAndUpdate(REPLACED.below(), Blocks.OBSIDIAN.defaultBlockState());
        playerB.setPos(REPLACED.getX() + .5, REPLACED.getY(), REPLACED.getZ() + 2.5);
        playerB.setItemInHand(InteractionHand.MAIN_HAND, packed);
        var hit = new BlockHitResult(Vec3.atCenterOf(REPLACED.below()).add(0, .5, 0), Direction.UP, REPLACED.below(), false);
        check(((BlockItem) packed.getItem()).place(new BlockPlaceContext(playerB, InteractionHand.MAIN_HAND, packed, hit)).consumesAction(),
            "wrench machine item re-placement");
        var replaced = (mio_icif_pump_elc) world.getBlockEntity(REPLACED);
        check(identity(replaced.getActionOwner(), MachineActionOwner.Kind.PLAYER, B), "new placer becomes owner after wrench cycle");
        groups.add("wrench-item-does-not-transfer-owner");
    }

    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        if (tick != 5) return null;
        lightContracts();
        realPlacementAndPersistence(world);
        wrenchDropAndReplacement(world);
        check(groups.size() == 5, "five scoped groups complete");
        var result = new LinkedHashMap<String, Object>();
        result.put("passed", true);
        result.put("assertions", assertions);
        result.put("groups", groups);
        result.put("scope", "versioned owner codec, real BlockItem placement, BE save/load, handoff domain and wrench-item authority reset");
        result.put("not_claimed", List.of("real protection mod", "connected multiplayer", "cold JVM restart", "full miner/pump gameplay", "client", "performance", "production"));
        Files.writeString(Path.of("machine-owner-f05-result.json"), new Gson().toJson(result));
        System.out.println("SCEX_MACHINE_OWNER_F05_PASS assertions=" + assertions);
        return result;
    }
}
