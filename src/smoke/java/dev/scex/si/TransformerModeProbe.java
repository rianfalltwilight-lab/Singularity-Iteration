// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block;
import com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage;
import dev.scex.energy.NetworkCell;
import dev.scex.energy.minecraft.IndependentTransformerBlockEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** Actual installed entities and vanilla server interaction path; no client-render claim. */
public final class TransformerModeProbe {
    private final List<BlockPos> controls = new ArrayList<>();
    private final List<IndependentTransformerBlockEntity.Snapshot> captured = new ArrayList<>();
    private int checks;
    public TransformerModeProbe() throws Exception {
        for (var r : JsonParser.parseString(Files.readString(Path.of("transformer-mode-probe.json"))).getAsJsonArray()) {
            var p = r.getAsJsonArray();
            controls.add(new BlockPos(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()));
        }
        if (controls.size() != 4) throw new IllegalArgumentException("Four mode controls required");
    }
    private void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private IndependentTransformerBlockEntity entity(ServerLevel level, BlockPos p) {
        var chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
        if (chunk == null || !(chunk.getBlockEntity(p) instanceof IndependentTransformerBlockEntity t))
            throw new AssertionError("Missing mode fixture");
        return t;
    }
    public Map<String,Object> inspect(ServerLevel level, int tick) {
        if (tick == 216) {
            for (var p : controls) {
                var t = entity(level, p); var q = t.snapshot(t.stepUpNow()).orElseThrow(); captured.add(q);
                check(t.savedMode() == 2 && !q.stepUp() && q.energy().amount() == 1, "initial automatic down state");
                check(t.getUpdatePacket() != null && t.getUpdateTag(level.registryAccess()).getInt("mode") == 2, "public client update payload");
            }
        } else if (tick == 218) {
            for (int i = 0; i < controls.size(); i++) {
                var t = entity(level, controls.get(i)); var old = captured.get(i);
                check(t.stepUpNow() && !t.isCurrent(old, old.stepUp()), "redstone revokes old orientation quote");
                var storage = ((mio_icif_Energy_Block) level.getBlockEntity(controls.get(i).below())).getEnergyStorageInternal();
                var fresh = storage.scexNetworkQuote(); check(fresh.amount() == 16, "ordinary source held below its packet");
                check(!CustomEUEnergyStorage.scexCommitNetwork(List.of(new CustomEUEnergyStorage.NetworkWrite(storage, fresh, 15)),
                    List.of(new NetworkCell.Write(old.energy(), 2)), 0, () -> true), "stale transformer rejects mixed write");
                check(storage.scexNetworkQuote().amount() == 16, "ordinary source debit rolled back");
                check(t.getUpdateTag(level.registryAccess()).getBoolean("active"), "active update tracks powered automatic mode");
            }
        } else if (tick == 370 || tick == 374 || tick == 378 || tick == 382) {
            var player = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("05ea51a2-28ac-4a51-a3c2-39d85c5cbd25"), "ScexMode25"));
            player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setShiftKeyDown(tick != 382);
            for (var p : controls) {
                var t = entity(level, p); int before = t.savedMode();
                player.setPos(p.getX() + 0.5, p.getY() + 1, p.getZ() + 1.5);
                var hit = new BlockHitResult(Vec3.atCenterOf(p), Direction.SOUTH, p, false);
                var result = player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
                check(result.consumesAction(), "vanilla survival block interaction consumed");
                check(t.savedMode() == (tick == 382 ? before : (before + 1) % 3), "server mode cycle or normal status interaction");
                check(t.getUpdateTag(level.registryAccess()).getInt("mode") == t.savedMode(), "mode in public update payload");
            }
        } else return null;
        return Map.of("passed", true, "checks", checks, "tick", tick, "positions", controls.size(), "real_server_interaction", true);
    }
}
