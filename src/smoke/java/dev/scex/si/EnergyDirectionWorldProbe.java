// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_wire;
import com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage;
import dev.scex.si.energy.IndependentSiEnergy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;

/** Actual world transfers controlled by the public wire direction and saved-state APIs. */
public final class EnergyDirectionWorldProbe {
    private record Line(Direction direction, BlockPos source) {
        BlockPos at(int offset) { return source.relative(direction, offset); }
    }
    private static final String[] PHASES = {"all-open", "first-wire-closes-edge", "second-wire-closes-edge",
            "source-contact-closed", "sink-contact-closed", "block-and-unblock-same-frame",
            "live-nbt-mask-reload", "saved-factory-replacement", "reopened-after-replacement"};
    private final List<Line> lines = new ArrayList<>();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private int assertions;
    public EnergyDirectionWorldProbe() {
        for (var face : Direction.values()) lines.add(new Line(face, new BlockPos(80 + face.get3DDataValue() * 12, 84, 64)));
        lines.add(new Line(Direction.EAST, new BlockPos(14, 84, 128)));
    }
    private void check(boolean value, String label) {
        assertions++; if (!value) throw new AssertionError("Energy direction: " + label);
    }
    private BlockEntity tile(ServerLevel world, BlockPos at) {
        var chunk = world.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
        check(chunk != null, "Fixture chunk already loaded " + at);
        var tile = chunk.getBlockEntity(at, LevelChunk.EntityCreationType.CHECK);
        check(tile != null && !tile.isRemoved(), "Fixture tile exists " + at);
        return tile;
    }
    private mio_icif_wire wire(ServerLevel world, Line line, int offset) {
        var tile = tile(world, line.at(offset)); check(tile instanceof mio_icif_wire, "Ordinary wire identity");
        return (mio_icif_wire) tile;
    }
    private CustomEUEnergyStorage storage(ServerLevel world, BlockPos at) {
        var tile = tile(world, at); check(tile instanceof mio_icif_Energy_Block, "Real storage endpoint");
        var storage = ((mio_icif_Energy_Block) tile).getEnergyStorageInternal();
        check(storage.scexNetworkControlled(), "Independent endpoint owner"); return storage;
    }
    private void place(ServerLevel world, Line line, int offset) {
        var at = line.at(offset);
        var chunk = world.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
        check(chunk != null && chunk.getBlockState(at).isAir(), "Dedicated empty fixture position");
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(offset == 0 || offset == 3
                ? "mio_icif:wiring/block_bat_box" : "mio_icif:wiring/cable/block_glass_cable"));
        check(block != Blocks.AIR, "Exact fixture block registration"); var state = block.defaultBlockState();
        if (offset == 0 || offset == 3) {
            check(state.hasProperty(BlockStateProperties.FACING), "Storage has six-way facing");
            state = state.setValue(BlockStateProperties.FACING, line.direction());
        }
        check(world.setBlock(at, state, 3), "Actual block placement");
    }
    private void resetPorts(ServerLevel world, Line line) {
        for (int offset = 1; offset <= 2; offset++) for (var face : Direction.values()) wire(world, line, offset).unblockDirection(face);
    }
    private void startPhase(ServerLevel world, int phase) {
        for (var line : lines) {
            resetPorts(world, line); var first = wire(world, line, 1); var second = wire(world, line, 2);
            if (phase == 1) first.blockDirection(line.direction());
            if (phase == 2) second.blockDirection(line.direction().getOpposite());
            if (phase == 3) first.blockDirection(line.direction().getOpposite());
            if (phase == 4) second.blockDirection(line.direction());
            if (phase == 5) { first.blockDirection(line.direction()); first.unblockDirection(line.direction()); }
            if (phase == 6) {
                var saved = first.saveWithFullMetadata(world.registryAccess());
                saved.putInt("BlockedDirections", 1 << line.direction().get3DDataValue());
                first.loadWithComponents(saved, world.registryAccess()); first.setChanged();
                check(first.isDirectionBlocked(line.direction()), "Public live NBT load updates mask");
            }
            if (phase == 7) {
                first.blockDirection(line.direction()); var saved = first.saveWithFullMetadata(world.registryAccess());
                var loaded = BlockEntity.loadStatic(first.getBlockPos(), first.getBlockState(), saved, world.registryAccess());
                check(loaded instanceof mio_icif_wire && loaded != first && loaded.getLevel() == null, "Independent saved factory identity");
                check(((mio_icif_wire) loaded).isDirectionBlocked(line.direction()), "Saved factory retains face mask");
                check(saved.getInt("BlockedDirections") == loaded.saveWithFullMetadata(world.registryAccess()).getInt("BlockedDirections"), "Existing NBT key round trip");
                world.removeBlockEntity(line.at(1)); world.setBlockEntity(loaded);
                check(first.isRemoved(), "Old conductor identity retired");
                IndependentSiEnergy.changed((mio_icif_wire) loaded);
            }
            storage(world, line.at(0)).setEnergy(32); storage(world, line.at(3)).setEnergy(0);
        }
    }
    private void inspectPhase(ServerLevel world, int phase) {
        boolean open = phase == 0 || phase == 5 || phase == 8;
        for (var line : lines) {
            var source = storage(world, line.at(0)).scexNetworkQuote().exactAmount();
            var sink = storage(world, line.at(3)).scexNetworkQuote().exactAmount();
            if (open) {
                check(source.isZero(), "Exactly one source packet spent " + PHASES[phase]);
                // R3 frozen glass-1/glass-39 observations: path milli-loss floors once per packet.
                check(sink.whole() == 32 && sink.fraction() == 0, "Observed whole-EU rounding for two glass conductors " + PHASES[phase]);
            } else {
                check(source.whole() == 32 && source.fraction() == 0, "Closed route never debits source " + PHASES[phase]);
                check(sink.isZero(), "Closed route never credits receiver " + PHASES[phase]);
            }
            results.add(Map.of("phase", PHASES[phase], "direction", line.direction().toString(),
                    "source_position", line.source().toShortString(), "source", source, "sink", sink));
        }
    }
    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        if (tick == 20) {
            check(Boolean.getBoolean("scex.independent.energy"), "Fixed independent launch flag");
            for (var line : lines) for (int offset = 0; offset <= 3; offset++) place(world, line, offset);
            return Map.of("direction_fixture_placed", true, "lines", lines.size());
        }
        if (tick >= 40 && tick <= 136 && (tick - 40) % 12 == 0) startPhase(world, (tick - 40) / 12);
        if (tick >= 48 && tick <= 144 && (tick - 48) % 12 == 0) inspectPhase(world, (tick - 48) / 12);
        if (tick != 152) return null;
        check(results.size() == 63, "Nine phases by six directions and one chunk border");
        var result = Map.<String, Object>of("passed", true, "assertions", assertions, "cases", results,
                "scope", "Actual SI port APIs, same-frame toggle, existing NBT, placed saved-factory replacement, six axes, chunk-border transfer. Physical chunk unload and cold JVM restart are separate scenarios.");
        Files.writeString(Path.of("energy-direction-result.json"), new Gson().toJson(result)); return result;
    }
}
