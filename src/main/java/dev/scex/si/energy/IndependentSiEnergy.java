// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.energy;

import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Container;
import com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage;
import dev.scex.energy.ConductorRegistry;
import dev.scex.energy.MultiSourceDistributor;
import dev.scex.energy.RouteCosts;
import dev.scex.energy.minecraft.PlatformTopology;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Experimental SI-side bridge, independently written for numeric storage and
 * public world APIs. It does not implement or call any old grid/energy API.
 * This opt-in subset is an integration checkpoint, not the complete replacement.
 */
public final class IndependentSiEnergy implements PlatformTopology.Observer {
    private static final ResourceLocation BATBOX = id("wiring/block_bat_box");
    private static final ResourceLocation GENERATOR = id("generator/block_thermal_generator");
    private static final Set<ResourceLocation> ENDPOINTS = Set.of(BATBOX, GENERATOR, id("producer/block_furnace_elc"),
        id("producer/block_powder_elc"), id("producer/block_extractor_elc"), id("producer/block_compressor_elc"));
    private static final Map<ResourceLocation, Long> CONDUCTORS = Map.of(
        id("wiring/cable/block_cable"), 200L, id("wiring/cable/block_cable_o"), 200L,
        id("wiring/cable/block_glass_cable"), 25L);
    private static final Map<MinecraftServer, IndependentSiEnergy> SERVERS = new IdentityHashMap<>();
    private static boolean installed;
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mio_icif", path); }
    public static boolean controls(BlockState state) {
        return Boolean.getBoolean("scex.independent.energy") && ENDPOINTS.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }
    public static synchronized void install() {
        if (installed) return; installed = true;
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> {
            if (Boolean.getBoolean("scex.independent.energy")) attach(event.getServer());
        });
    }
    public static synchronized IndependentSiEnergy attach(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Attach on server thread");
        return SERVERS.computeIfAbsent(server, IndependentSiEnergy::new);
    }
    public static synchronized IndependentSiEnergy current(MinecraftServer server) { return SERVERS.get(server); }
    public static void changed(mio_icif_Energy_Block tile) {
        if (tile.getLevel() instanceof ServerLevel level) {
            var engine = current(level.getServer()); if (engine != null) engine.topology.changed(level, tile.getBlockPos());
        }
    }

    private MinecraftServer server;
    private final long ownerThread = Thread.currentThread().threadId();
    private final PlatformTopology topology;
    private final Map<ServerLevel, Map<BlockPos, mio_icif_Energy_Block>> endpoints = new HashMap<>();
    private long ticks, commits, rejected, debited, credited, dissipated;
    private String failure = "";
    private boolean closed;
    public record Metrics(long ticks, long commits, long rejected, long debited, long credited, long dissipated,
                          int dimensions, int endpoints, boolean closed, String failure) { }
    private record Port(mio_icif_Energy_Block tile, BlockPos position, BlockState state,
                        CustomEUEnergyStorage storage, CustomEUEnergyStorage.NetworkQuote quote,
                        long capacity, int inputs, int outputs, long packet) { }
    private IndependentSiEnergy(MinecraftServer server) {
        this.server = server;
        topology = new PlatformTopology(server, CONDUCTORS, 100_000, 32, 65_536, 4096, this);
        NeoForge.EVENT_BUS.register(this);
    }
    public Metrics metrics() {
        if (Thread.currentThread().threadId() != ownerThread) throw new IllegalStateException("Read engine metrics on server thread");
        return new Metrics(ticks, commits, rejected, debited, credited, dissipated,
            endpoints.size(), endpoints.values().stream().mapToInt(Map::size).sum(), closed, failure);
    }
    @Override
    public void position(ServerLevel level, LevelChunk chunk, BlockPos at) {
        var found = endpoints.get(level);
        if (!controls(chunk.getBlockState(at))) {
            if (found != null) found.remove(at); return;
        }
        // The chunk is already FULL. This can materialize its saved block entity,
        // without loading another chunk or renewing a world lookup ticket.
        var tile = chunk.getBlockEntity(at, LevelChunk.EntityCreationType.IMMEDIATE);
        if (!(tile instanceof mio_icif_Energy_Block machine) || !machine.getEnergyStorageInternal().scexNetworkControlled())
            throw new IllegalStateException("Controlled endpoint lacks the new storage boundary at " + at);
        if (found == null) { found = new HashMap<>(); endpoints.put(level, found); }
        if (!found.containsKey(at) && found.size() >= 4096) throw new IllegalStateException("Endpoint limit reached");
        found.put(at.immutable(), machine);
    }
    @Override
    public void chunkRemoved(ServerLevel level, int chunkX, int chunkZ) {
        var entries = endpoints.get(level);
        if (entries != null) entries.keySet().removeIf(at -> (at.getX() >> 4) == chunkX && (at.getZ() >> 4) == chunkZ);
    }
    @Override public void levelRemoved(ServerLevel level) { endpoints.remove(level); }
    @Override public void cleared() { endpoints.clear(); }
    @SubscribeEvent
    public void tick(ServerTickEvent.Post event) {
        if (event.getServer() != server || closed || !failure.isEmpty()) return;
        ticks++;
        try {
            if (!topology.metrics().failure().isEmpty()) throw new IllegalStateException(topology.metrics().failure());
            for (var entry : endpoints.entrySet()) {
                if (topology.ready(entry.getKey())) settle(entry.getKey(), entry.getValue());
            }
        } catch (RuntimeException error) {
            failure = error.toString(); error.printStackTrace(); topology.close();
        }
    }
    private void settle(ServerLevel level, Map<BlockPos, mio_icif_Energy_Block> known) {
        var ports = new ArrayList<Port>();
        for (var entry : known.entrySet()) {
            BlockPos at = entry.getKey(); var tile = entry.getValue();
            var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
            if (chunk == null || !level.shouldTickBlocksAt(ChunkPos.asLong(at)) || tile.isRemoved()
                || chunk.getBlockEntity(at, LevelChunk.EntityCreationType.CHECK) != tile) continue;
            int inputs = 63, outputs = 0;
            boolean generator = BuiltInRegistries.BLOCK.getKey(tile.getBlockState().getBlock()).equals(GENERATOR);
            if (generator) { inputs = 0; outputs = 63; }
            if (tile instanceof mio_icif_Energy_Container storageBox && BuiltInRegistries.BLOCK.getKey(tile.getBlockState().getBlock()).equals(BATBOX)) {
                inputs = 0;
                for (var side : Direction.values()) {
                    if (storageBox.canProvidePowerFromSide(side)) outputs |= 1 << side.ordinal();
                    if (storageBox.canConsumePowerFromSide(side)) inputs |= 1 << side.ordinal();
                }
            }
            var storage = tile.getEnergyStorageInternal(); var quote = storage.scexNetworkQuote();
            if (!quote.outputEnabled()) outputs = 0;
            // Original binary observations distinguish generator residual offers
            // from the BatBox full-packet reserve rule, including a 1 EU offer.
            long packet = generator ? Math.min(32, quote.amount()) : 32;
            ports.add(new Port(tile, at, tile.getBlockState(), storage, quote, tile.getEffectiveCapacity(), inputs, outputs, packet));
        }
        ports.sort(Comparator.comparingInt((Port p) -> p.position.getX()).thenComparingInt(p -> p.position.getY()).thenComparingInt(p -> p.position.getZ()));
        var sources = ports.stream().filter(p -> p.outputs != 0 && p.packet > 0 && p.quote.amount() >= p.packet).toList();
        var sinks = ports.stream().filter(p -> p.inputs != 0).toList();
        if (sources.isEmpty() || sinks.isEmpty()) return;
        var snapshot = topology.snapshot(level); long[] room = new long[sinks.size()];
        int[] receivers = new int[sinks.size()]; int[] sourceOrder = new int[sources.size()];
        for (int i = 0; i < room.length; i++) { room[i] = Math.max(0, sinks.get(i).capacity - sinks.get(i).quote.amount()); receivers[i] = i; }
        // Explicit deterministic rotation for this integration checkpoint. Target
        // source/receiver probability and timing remain a separate black-box gate.
        int[] priorities = new int[receivers.length];
        for (int i = 0; i < priorities.length; i++) priorities[i] = (int) ((i + ticks) % priorities.length);
        var offers = new ArrayList<MultiSourceDistributor.Offer>();
        for (int i = 0; i < sources.size(); i++) {
            sourceOrder[i] = (int) ((i + ticks) % sources.size());
            var source = sources.get(i);
            var costs = routes(source, sinks, snapshot);
            offers.add(new MultiSourceDistributor.Offer(source.quote.amount(), source.packet, costs, receivers, priorities));
        }
        var round = MultiSourceDistributor.allocate(offers, room, sourceOrder);
        var deltas = new IdentityHashMap<CustomEUEnergyStorage, Long>();
        long loss = 0, debit = 0, credit = 0;
        for (int i = 0; i < sources.size(); i++) {
            var result = round.source(i); var source = sources.get(i);
            debit = Math.addExact(debit, result.sourceDebit()); loss = Math.addExact(loss, result.dissipated());
            deltas.merge(source.storage, -result.sourceDebit(), Math::addExact);
            for (int receiver = 0; receiver < sinks.size(); receiver++) {
                long value = result.credit(receiver); credit = Math.addExact(credit, value);
                deltas.merge(sinks.get(receiver).storage, value, Math::addExact);
            }
        }
        if (debit == 0) return;
        var writes = new ArrayList<CustomEUEnergyStorage.NetworkWrite>();
        for (var port : ports) {
            long delta = deltas.getOrDefault(port.storage, 0L);
            if (delta != 0) writes.add(new CustomEUEnergyStorage.NetworkWrite(port.storage, port.quote, Math.addExact(port.quote.amount(), delta)));
        }
        if (CustomEUEnergyStorage.scexCommitNetwork(writes, loss, () -> valid(level, snapshot, ports))) {
            commits++; debited = Math.addExact(debited, debit); credited = Math.addExact(credited, credit); dissipated = Math.addExact(dissipated, loss);
        } else rejected++;
    }
    private boolean valid(ServerLevel level, ConductorRegistry.Snapshot snapshot, List<Port> ports) {
        if (!topology.isCurrent(level, snapshot)) return false;
        for (var port : ports) {
            var chunk = level.getChunkSource().getChunkNow(port.position.getX() >> 4, port.position.getZ() >> 4);
            if (chunk == null || !level.shouldTickBlocksAt(ChunkPos.asLong(port.position)) || port.tile.isRemoved()
                || chunk.getBlockEntity(port.position, LevelChunk.EntityCreationType.CHECK) != port.tile
                || chunk.getBlockState(port.position) != port.state || port.tile.getEffectiveCapacity() != port.capacity
                || !port.storage.scexNetworkQuote().equals(port.quote)) return false;
        }
        return true;
    }
    private static ConductorRegistry.Position point(BlockPos at) { return new ConductorRegistry.Position(at.getX(), at.getY(), at.getZ()); }
    private static RouteCosts routes(Port source, List<Port> sinks, ConductorRegistry.Snapshot graph) {
        long[] losses = new long[sinks.size()]; boolean[] reaches = new boolean[sinks.size()];
        for (int i = 0; i < sinks.size(); i++) {
            var sink = sinks.get(i); if (source.tile == sink.tile) continue;
            long best = Long.MAX_VALUE; boolean found = false;
            for (Direction output : Direction.values()) {
                if ((source.outputs & (1 << output.ordinal())) == 0) continue;
                BlockPos start = source.position.relative(output);
                if (start.equals(sink.position) && (sink.inputs & (1 << output.getOpposite().ordinal())) != 0) { best = 0; found = true; break; }
                if (!graph.contains(point(start))) continue;
                var paths = graph.routesFrom(point(start));
                for (Direction input : Direction.values()) {
                    if ((sink.inputs & (1 << input.ordinal())) == 0) continue;
                    var contact = point(sink.position.relative(input)); if (!graph.contains(contact)) continue;
                    int vertex = graph.vertex(contact);
                    if (paths.reaches(vertex)) { best = Math.min(best, paths.lossMilliTo(vertex)); found = true; }
                }
            }
            reaches[i] = found; losses[i] = best;
        }
        return new RouteCosts() {
            @Override public boolean reaches(int contact) { return reaches[contact]; }
            @Override public long lossMilliTo(int contact) {
                if (!reaches[contact]) throw new IllegalArgumentException("Unreachable endpoint"); return losses[contact];
            }
        };
    }
    @SubscribeEvent
    public void stopped(ServerStoppedEvent event) {
        if (event.getServer() != server) return;
        topology.close(); endpoints.clear(); closed = true;
        synchronized (IndependentSiEnergy.class) { SERVERS.remove(server); }
        server = null;
        NeoForge.EVENT_BUS.unregister(this);
    }
}
