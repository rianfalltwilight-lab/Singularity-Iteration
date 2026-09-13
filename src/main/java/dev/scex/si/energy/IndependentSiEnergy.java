// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.energy;

import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Container;
import com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage;
import dev.scex.energy.ConductorRegistry;
import dev.scex.energy.DeferredEntries;
import dev.scex.energy.MultiSourceDistributor;
import dev.scex.energy.ReceiverOrder;
import dev.scex.energy.RouteCosts;
import dev.scex.energy.minecraft.PlatformTopology;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

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
    private final Map<ServerLevel, WorldGrid> worlds = new HashMap<>();
    // Record the independent seed in probe metrics so this selection stream can
    // be replayed. It is not a seed or algorithm taken from the reference mod.
    private final long selectionSeed = Long.getLong("scex.independent.selectionSeed", ThreadLocalRandom.current().nextLong());
    private final SplittableRandom selectionRandom = new SplittableRandom(selectionSeed);
    private long ticks, commits, rejected, debited, credited, dissipated;
    private String failure = "";
    private boolean closed;
    public record Metrics(long ticks, long commits, long rejected, long debited, long credited, long dissipated,
                          int dimensions, int endpoints, boolean closed, String failure, long selectionSeed) { }
    private record Port(mio_icif_Energy_Block tile, BlockPos position, BlockState state,
                        CustomEUEnergyStorage storage, CustomEUEnergyStorage.NetworkQuote quote,
                        long capacity, int inputs, int outputs, long packet) { }
    private record Element(ResourceLocation type, long conductorLoss, BlockEntity tile) {
        @Override public boolean equals(Object other) {
            return other instanceof Element e && type.equals(e.type) && conductorLoss == e.conductorLoss && tile == e.tile;
        }
        @Override public int hashCode() { return 31 * type.hashCode() + Long.hashCode(conductorLoss) + System.identityHashCode(tile); }
    }
    private static final class WorldGrid implements AutoCloseable {
        final DeferredEntries<BlockPos, Element> entries = new DeferredEntries<>(104_096, 65_536);
        final ConductorRegistry conductors = new ConductorRegistry(100_000, 32);
        final Map<BlockPos, mio_icif_Energy_Block> machines = new LinkedHashMap<>();
        final Map<Long, Integer> conductorChunks = new HashMap<>();
        boolean catchUp;
        void apply(List<DeferredEntries.Change<BlockPos, Element>> changes) {
            for (var change : changes) {
                var at = change.key(); long chunk = ChunkPos.asLong(at);
                if (change.before() != null && change.before().conductorLoss >= 0) {
                    conductors.remove(point(at));
                    conductorChunks.compute(chunk, (key, count) -> count == 1 ? null : count - 1);
                }
                machines.remove(at);
                var after = change.after();
                if (after == null) continue;
                if (after.conductorLoss >= 0) {
                    conductors.put(point(at), after.conductorLoss);
                    conductorChunks.merge(chunk, 1, Integer::sum);
                } else {
                    if (machines.size() >= 4096) throw new IllegalStateException("Endpoint limit reached");
                    machines.put(at, (mio_icif_Energy_Block) after.tile);
                }
            }
        }
        @Override public void close() {
            entries.close(); conductors.close(); machines.clear(); conductorChunks.clear(); catchUp = false;
        }
    }
    private IndependentSiEnergy(MinecraftServer server) {
        this.server = server;
        topology = new PlatformTopology(server, CONDUCTORS, 100_000, 32, 65_536, 4096, this);
        NeoForge.EVENT_BUS.register(this);
    }
    public Metrics metrics() {
        if (Thread.currentThread().threadId() != ownerThread) throw new IllegalStateException("Read engine metrics on server thread");
        return new Metrics(ticks, commits, rejected, debited, credited, dissipated,
            worlds.size(), worlds.values().stream().mapToInt(grid -> grid.machines.size()).sum(), closed, failure, selectionSeed);
    }
    @Override
    public void position(ServerLevel level, LevelChunk chunk, BlockPos at) {
        var state = chunk.getBlockState(at); var type = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        var loss = CONDUCTORS.get(type); Element element = null;
        var grid = worlds.get(level);
        // The chunk is already FULL. This can materialize its saved block entity,
        // without loading another chunk or renewing a world lookup ticket.
        if (loss != null || controls(state)) {
            var tile = chunk.getBlockEntity(at, LevelChunk.EntityCreationType.IMMEDIATE);
            if (tile == null) throw new IllegalStateException("Electrical block entity missing at " + at);
            if (loss == null && (!(tile instanceof mio_icif_Energy_Block machine) || !machine.getEnergyStorageInternal().scexNetworkControlled()))
                throw new IllegalStateException("Controlled endpoint lacks the new storage boundary at " + at);
            element = new Element(type, loss == null ? -1 : loss, tile);
            if (grid == null) { grid = new WorldGrid(); worlds.put(level, grid); }
        }
        // A late server-post observation belongs to the next publication frame
        // if the world's END decision has already been made.
        if (grid != null) grid.entries.observe(Math.max(ticks + 1, grid.entries.metrics().advancedFrame() + 1), at.immutable(), element);
    }
    @Override
    public void chunkRemoved(ServerLevel level, int chunkX, int chunkZ) {
        var grid = worlds.get(level);
        if (grid != null) {
            grid.apply(grid.entries.forgetIf(at -> (at.getX() >> 4) == chunkX && (at.getZ() >> 4) == chunkZ));
            grid.catchUp = false;
        }
    }
    @Override public void levelRemoved(ServerLevel level) { var grid = worlds.remove(level); if (grid != null) grid.close(); }
    @Override public void cleared() { worlds.values().forEach(WorldGrid::close); worlds.clear(); }
    @SubscribeEvent
    public void beforeLevel(LevelTickEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer() != server || closed || !failure.isEmpty()) return;
        var grid = worlds.get(level);
        if (grid == null || !grid.catchUp) return;
        grid.catchUp = false;
        try {
            if (!topology.metrics().failure().isEmpty()) throw new IllegalStateException(topology.metrics().failure());
            settle(level, grid);
        } catch (RuntimeException error) { fail(error); }
    }
    @SubscribeEvent
    public void tick(ServerTickEvent.Post event) {
        if (event.getServer() != server || closed || !failure.isEmpty()) return;
        ticks++;
        if (!topology.metrics().failure().isEmpty()) fail(new IllegalStateException(topology.metrics().failure()));
    }
    @SubscribeEvent(priority = EventPriority.LOW)
    public void afterLevel(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer() != server || closed || !failure.isEmpty()) return;
        try {
            if (!topology.metrics().failure().isEmpty()) throw new IllegalStateException(topology.metrics().failure());
            var grid = worlds.get(level);
            if (grid == null || !topology.ready(level)) return;
            var changes = grid.entries.advance(ticks + 1);
            if (changes.isEmpty()) settle(level, grid);
            else {
                grid.apply(changes);
                // Public reference observations: a matured electrical edit
                // pauses END once, followed by START and END packets next tick.
                grid.catchUp = true;
            }
        } catch (RuntimeException error) { fail(error); }
    }
    private void fail(RuntimeException error) { failure = error.toString(); error.printStackTrace(); topology.close(); }
    private boolean accessible(ServerLevel level, WorldGrid grid) {
        if (worlds.get(level) != grid || server.getLevel(level.dimension()) != level) return false;
        // Delayed wire edits never authorize transfer through inaccessible chunks.
        for (long chunk : grid.conductorChunks.keySet()) {
            if (level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) == null
                || !level.shouldTickBlocksAt(chunk)) return false;
        }
        return true;
    }
    private void settle(ServerLevel level, WorldGrid grid) {
        if (!accessible(level, grid)) return;
        var ports = new ArrayList<Port>();
        for (var entry : grid.machines.entrySet()) {
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
        var sources = ports.stream().filter(p -> p.outputs != 0 && p.packet > 0 && p.quote.amount() >= p.packet)
            .sorted(Comparator.comparingInt((Port p) -> p.position.getX()).thenComparingInt(p -> p.position.getY()).thenComparingInt(p -> p.position.getZ())).toList();
        var sinks = ports.stream().filter(p -> p.inputs != 0).toList();
        if (sources.isEmpty() || sinks.isEmpty()) return;
        var snapshot = grid.conductors.snapshot(); long[] room = new long[sinks.size()];
        int[] receivers = new int[sinks.size()]; int[] sourceOrder = new int[sources.size()];
        for (int i = 0; i < room.length; i++) { room[i] = Math.max(0, sinks.get(i).capacity - sinks.get(i).quote.amount()); receivers[i] = i; }
        var offers = new ArrayList<MultiSourceDistributor.Offer>();
        for (int i = 0; i < sources.size(); i++) {
            // Source order remains an explicit separate acceptance boundary.
            sourceOrder[i] = (int) ((i + ticks) % sources.size());
            var source = sources.get(i);
            var costs = routes(source, sinks, snapshot);
            boolean[] eligible = new boolean[sinks.size()];
            for (int receiver = 0; receiver < eligible.length; receiver++) {
                eligible[receiver] = room[receiver] > 0 && costs.reaches(receiver)
                    && costs.wholeLossTo(receiver) < source.packet;
            }
            int[] priorities = ReceiverOrder.create(receivers, eligible, level.getGameTime(), selectionRandom);
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
        if (CustomEUEnergyStorage.scexCommitNetwork(writes, loss, () -> valid(level, grid, snapshot, ports))) {
            commits++; debited = Math.addExact(debited, debit); credited = Math.addExact(credited, credit); dissipated = Math.addExact(dissipated, loss);
        } else rejected++;
    }
    private boolean valid(ServerLevel level, WorldGrid grid, ConductorRegistry.Snapshot snapshot, List<Port> ports) {
        // The independent published registry intentionally preserves one final
        // packet after a wire edit, as measured. Its lease is still current;
        // source/sink identity, all fresh quotes and chunk access remain mandatory.
        if (!accessible(level, grid) || !grid.conductors.isCurrent(snapshot)) return false;
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
        topology.close(); cleared(); closed = true;
        synchronized (IndependentSiEnergy.class) { SERVERS.remove(server); }
        server = null;
        NeoForge.EVENT_BUS.unregister(this);
    }
}
