// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_wire;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block;
import dev.scex.energy.IndependentEnergyMode;
import dev.scex.energy.minecraft.IndependentSpecialCableBlockEntity;
import dev.scex.energy.minecraft.IndependentTransformerBlockEntity;
import dev.scex.si.energy.IndependentSiEnergy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Actual placed entities and public saved-state factories, with fixed launch switches. */
public final class EnergyOwnershipWorldProbe {
    private record Case(String id, String kind, BlockPos pos) { }
    private final List<Case> cases = new ArrayList<>();
    private int assertions;
    private mio_icif_Energy_Block energyTile(ServerLevel world, BlockPos pos) {
        var chunk = world.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        check(chunk != null, "Loaded UU energy fixture");
        var tile = chunk.getBlockEntity(pos);
        check(tile instanceof mio_icif_Energy_Block, "UU energy fixture type");
        return (mio_icif_Energy_Block) tile;
    }
    public EnergyOwnershipWorldProbe() throws Exception {
        var json = JsonParser.parseString(Files.readString(Path.of("energy-ownership-world.json"))).getAsJsonArray();
        for (var element : json) {
            var row = element.getAsJsonObject(); var p = row.getAsJsonArray("position");
            cases.add(new Case(row.get("id").getAsString(), row.get("kind").getAsString(),
                    new BlockPos(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt())));
        }
        if (cases.size() != 15 || cases.stream().map(Case::id).distinct().count() != 15)
            throw new IllegalArgumentException("Expected nine conductors, four transformers and two special cables");
    }
    private void check(boolean value, String label) {
        assertions++; if (!value) throw new AssertionError("Energy ownership: " + label);
    }
    private void factory(BlockEntity placed, BlockEntity loaded, Case row) {
        check(loaded != null && loaded != placed, "New factory identity " + row.id());
        check(loaded.getClass() == placed.getClass() && loaded.getType() == placed.getType(), "Factory and placement agree " + row.id());
        check(loaded.getLevel() == null, "Factory copy never joins world " + row.id());
    }
    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        if (tick == 20) {
            for (int z : new int[]{40, 44}) {
                var source = energyTile(world, new BlockPos(0, 80, z));
                var sink = energyTile(world, new BlockPos(2, 80, z));
                check(source.getEnergyStorageInternal().scexNetworkControlled()
                        && sink.getEnergyStorageInternal().scexNetworkControlled(), "UU source and sink explicitly owned");
                check(sink.getEnergyStorageInternal().scexExactAmount().isZero(), "UU idle sink starts empty");
                source.getEnergyStorageInternal().setEnergy(5000);
            }
        }
        if (tick != 40) return null;
        check(IndependentEnergyMode.enabled() && IndependentEnergyMode.feature("transformers")
                && IndependentEnergyMode.feature("specialCables"), "Fixed launch configuration");
        check(!IndependentSiEnergy.controls(Blocks.STONE.defaultBlockState()), "Unknown block not claimed");
        var entries = new ArrayList<Map<String, Object>>(); int wires = 0, transformers = 0, specials = 0;
        for (var row : cases) {
            var chunk = world.getChunkSource().getChunkNow(row.pos().getX() >> 4, row.pos().getZ() >> 4);
            check(chunk != null, "Fixture already loaded");
            var tile = chunk.getBlockEntity(row.pos()); check(tile != null && !tile.isRemoved(), "Live placed entity " + row.id());
            var state = tile.getBlockState();
            check(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(row.id()), "Exact fixture registry identity");
            check(IndependentSiEnergy.controls(state), "Engine owns placed entity " + row.id());
            if (row.kind().equals("wire")) {
                check(tile instanceof mio_icif_wire, "Ordinary wire type");
                var wire = (mio_icif_wire) tile; wires++;
                var store = wire.getEnergyStorageInternal(); long before = store.getAmount();
                check(store.scexNetworkControlled(), "Numerical storage has same owner");
                for (var side : Direction.values()) {
                    check(!wire.emitsEnergyTo(null, side) && !wire.acceptsEnergyFrom(null, side), "Legacy faces are closed");
                    check(wire.injectEnergy(side, 17.5, 32) == 17.5, "Legacy injection fully returned");
                }
                check(wire.getDemandedEnergy() == 0 && store.getAmount() == before, "Legacy injection cannot alter stored energy");
            } else if (row.kind().equals("transformer")) {
                check(tile instanceof IndependentTransformerBlockEntity, "Maintained transformer actually placed"); transformers++;
            } else if (row.kind().equals("special")) {
                check(tile instanceof IndependentSpecialCableBlockEntity, "Maintained special cable actually placed"); specials++;
            } else throw new AssertionError("Unknown case kind");
            factory(tile, tile.getType().create(row.pos(), state), row);
            var saved = tile.saveWithFullMetadata(world.registryAccess());
            var loaded = BlockEntity.loadStatic(row.pos(), state, saved, world.registryAccess());
            factory(tile, loaded, row);
            check(saved.equals(loaded.saveWithFullMetadata(world.registryAccess())), "Full saved payload retained " + row.id());
            entries.add(Map.of("id", row.id(), "class", tile.getClass().getName(), "saved", saved.toString()));
        }
        check(wires == 9 && transformers == 4 && specials == 2, "Exact declared case counts");
        var uuTransfers = new ArrayList<Map<String, Object>>();
        for (int z : new int[]{40, 44}) {
            var source = energyTile(world, new BlockPos(0, 80, z));
            var sink = energyTile(world, new BlockPos(2, 80, z));
            double debit = 5000 - source.getEnergyStorageInternal().scexExactAmount().toDouble();
            double credit = sink.getEnergyStorageInternal().scexExactAmount().toDouble();
            check(debit >= 32 && debit <= 640 && debit % 32 == 0, "Real source debit uses bounded 32 EU packets");
            check(credit > 0 && credit <= debit, "Real UU machine receives through independent wire without creating energy");
            check(debit == credit, "Observed sub-40 glass path has zero whole-EU loss");
            uuTransfers.add(Map.of("sink", BuiltInRegistries.BLOCK.getKey(sink.getBlockState().getBlock()).toString(),
                    "debit_eu", debit, "credit_eu", credit, "loss_eu", debit - credit));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("passed", true); result.put("assertions", assertions); result.put("groups", List.of(
                "ordinary-wire-single-owner", "placement-create-and-saved-factory-identity", "uu-machines-actual-native-supply"));
        result.put("uu_transfers", uuTransfers);
        result.put("entries", entries); result.put("scope", "All fifteen actual registered placements; no foreign wire bridge, full default adoption, hot switch, client or cold JVM claim.");
        Files.writeString(Path.of("energy-ownership-result.json"), new Gson().toJson(result));
        return result;
    }
}
