// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy.minecraft;

import dev.scex.energy.ConductorRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkTicketLevelUpdatedEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Public-platform lifecycle adapter for registered block-entity conductors.
 * It observes supplied block IDs and losses, and never accesses an SI/IC2 Java
 * class or transfers energy. Chunk loads are deferred until getChunkNow succeeds.
 * The future energy adapter must use isCurrent again immediately before commit.
 */
public final class PlatformTopology implements AutoCloseable {
    /** Index-only callbacks on the server thread; they must not mutate the world. */
    public interface Observer {
        default void position(ServerLevel level, LevelChunk chunk, BlockPos at) { }
        default void blockChanged(ServerLevel level, BlockPos at, BlockState before, BlockState after) { }
        default void chunkRemoved(ServerLevel level, int chunkX, int chunkZ) { }
        default void levelRemoved(ServerLevel level) { }
        default void cleared() { }
    }
    private static final Observer NO_OBSERVER = new Observer() { };
    private static final Map<MinecraftServer, ArrayList<PlatformTopology>> INSTANCES = new java.util.IdentityHashMap<>();

    /** Public chunk mutation callback. Only observer metadata may change here. */
    public static synchronized void physicalBlockChanged(ServerLevel level, BlockPos at, BlockState before, BlockState after) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Block history outside server thread");
        var instances = INSTANCES.get(level.getServer());
        if (instances == null) return;
        for (var instance : instances) {
            if (instance.closed || !instance.failure.isEmpty()) continue;
            try { instance.observer.blockChanged(level, at, before, after); }
            catch (RuntimeException error) {
                instance.failure = error.getClass().getSimpleName() + ": " + error.getMessage();
                instance.pending.clear();
            }
        }
    }
    private enum Kind { SAMPLE, LOAD_CHUNK, UNLOAD_CHUNK, UNLOAD_LEVEL }
    private enum Scope { POSITION, CHUNK, LEVEL }
    private record Key(ServerLevel level, Scope scope, long coordinate) { }
    private record Change(Key key, Kind kind, BlockPos position) { }
    public record Metrics(int dimensions, int queued, long chunkLoads, long chunkUnloads,
                          long blockSignals, long sampledPositions, long deferredLoads, long accessibilityChanges,
                          boolean closed, String failure) { }

    private final Map<ResourceLocation, Long> losses;
    private Observer observer;
    private MinecraftServer server;
    private final int maximumNodes;
    private final int maximumSources;
    private final int maximumQueued;
    private final int workPerTick;
    private final Map<ServerLevel, ConductorRegistry> worlds = new HashMap<>();
    private final LinkedHashMap<Key, Change> pending = new LinkedHashMap<>();
    private volatile boolean closed;
    private volatile String failure = "";
    private long chunkLoads, chunkUnloads, blockSignals, sampledPositions, deferredLoads, accessibilityChanges;
    private int budgetTick = Integer.MIN_VALUE, remainingWork;

    public PlatformTopology(MinecraftServer server, Map<ResourceLocation, Long> losses, int maximumNodes,
                            int maximumSources, int maximumQueued, int workPerTick) {
        this(server, losses, maximumNodes, maximumSources, maximumQueued, workPerTick, NO_OBSERVER);
    }

    public PlatformTopology(MinecraftServer server, Map<ResourceLocation, Long> losses, int maximumNodes,
                            int maximumSources, int maximumQueued, int workPerTick, Observer observer) {
        this.server = Objects.requireNonNull(server, "server");
        this.observer = Objects.requireNonNull(observer, "observer");
        if (!server.isSameThread()) { throw new IllegalStateException("Attach on the server thread"); }
        this.losses = Map.copyOf(losses);
        if (this.losses.isEmpty() || this.losses.values().stream().anyMatch(v -> v < 0)
                || maximumNodes <= 0 || maximumNodes > (Integer.MAX_VALUE - 1) / 6
                || maximumSources <= 0 || maximumQueued <= 0 || workPerTick <= 0) {
            throw new IllegalArgumentException("Positive limits and nonnegative conductor losses required");
        }
        this.maximumNodes = maximumNodes; this.maximumSources = maximumSources;
        this.maximumQueued = maximumQueued; this.workPerTick = workPerTick;
        synchronized (PlatformTopology.class) { INSTANCES.computeIfAbsent(server, ignored -> new ArrayList<>()).add(this); }
        NeoForge.EVENT_BUS.register(this);
    }

    private synchronized void enqueue(Change change, boolean retry) {
        if (closed || !failure.isEmpty()) { return; }
        if (retry && pending.containsKey(change.key)) { return; }
        if (!pending.containsKey(change.key) && pending.size() >= maximumQueued) {
            // Fail closed and keep storage bounded. Callers can see the fault;
            // no energy commit can silently use an incomplete registry.
            failure = "Topology event queue capacity exceeded";
            pending.clear(); return;
        }
        pending.put(change.key, change);
    }

    /** Explicit hook for mutations which intentionally suppress neighbour events. */
    public void changed(ServerLevel level, BlockPos position) {
        Objects.requireNonNull(level, "level"); Objects.requireNonNull(position, "position");
        if (level.getServer() != server) { return; }
        BlockPos immutable = position.immutable();
        enqueue(new Change(new Key(level, Scope.POSITION, immutable.asLong()), Kind.SAMPLE, immutable), false);
    }

    @SubscribeEvent
    public void onNeighbours(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer() != server) { return; }
        synchronized (this) { blockSignals++; }
        changed(level, event.getPos());
        for (Direction direction : event.getNotifiedSides()) { changed(level, event.getPos().relative(direction)); }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer() != server) { return; }
        synchronized (this) { chunkLoads++; }
        enqueue(new Change(new Key(level, Scope.CHUNK, event.getChunk().getPos().toLong()), Kind.LOAD_CHUNK, BlockPos.ZERO), false);
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer() != server) { return; }
        synchronized (this) { chunkUnloads++; }
        queueChunkRemoval(level, event.getChunk().getPos().toLong());
    }

    @SubscribeEvent
    public void onTicketLevel(ChunkTicketLevelUpdatedEvent event) {
        ServerLevel level = event.getLevel();
        if (level.getServer() != server) { return; }
        boolean wasFull = ChunkLevel.fullStatus(event.getOldTicketLevel()) != FullChunkStatus.INACCESSIBLE;
        boolean nowFull = ChunkLevel.fullStatus(event.getNewTicketLevel()) != FullChunkStatus.INACCESSIBLE;
        if (wasFull == nowFull) { return; }
        synchronized (this) { accessibilityChanges++; }
        // A chunk can become inaccessible long before its physical unload event.
        // Conversely, a still-resident chunk can regain access without a new Load.
        // Coalesce public ticket transitions; do not retain a ChunkHolder or load here.
        if (nowFull) {
            enqueue(new Change(new Key(level, Scope.CHUNK, event.getChunkPos()), Kind.LOAD_CHUNK, BlockPos.ZERO), false);
        } else { queueChunkRemoval(level, event.getChunkPos()); }
    }

    private synchronized void queueChunkRemoval(ServerLevel level, long key) {
            // Unload cancels retrying loads and pending coordinate reads for this chunk.
            pending.entrySet().removeIf(entry -> entry.getKey().level == level
                    && (entry.getKey().scope == Scope.CHUNK ? entry.getKey().coordinate == key
                        : entry.getKey().scope == Scope.POSITION && ChunkPos.asLong(entry.getValue().position) == key));
            enqueue(new Change(new Key(level, Scope.CHUNK, key), Kind.UNLOAD_CHUNK, BlockPos.ZERO), false);
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer() != server) { return; }
        synchronized (this) { pending.entrySet().removeIf(entry -> entry.getKey().level == level); }
        enqueue(new Change(new Key(level, Scope.LEVEL, 0), Kind.UNLOAD_LEVEL, BlockPos.ZERO), false);
    }

    @SubscribeEvent
    public void onTick(ServerTickEvent.Post event) {
        if (event.getServer() != server) { return; }
        drain(null);
    }

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.getServer() == server) drain(level);
    }

    private void drain(ServerLevel onlyLevel) {
        ArrayList<Change> batch = new ArrayList<>();
        synchronized (this) {
            if (closed) { return; }
            if (!failure.isEmpty()) { clearWorlds(); return; }
            int now = server.getTickCount();
            if (now != budgetTick) { budgetTick = now; remainingWork = workPerTick; }
            var iterator = pending.values().iterator();
            while (iterator.hasNext() && remainingWork > 0) {
                Change change = iterator.next();
                if (change.key.level.getServer() == server && (onlyLevel == null || change.key.level == onlyLevel)) {
                    batch.add(change); iterator.remove(); remainingWork--;
                }
            }
        }
        try {
            for (Change change : batch) { apply(change); }
        } catch (RuntimeException error) {
            synchronized (this) { failure = error.getClass().getSimpleName() + ": " + error.getMessage(); pending.clear(); }
            clearWorlds();
        }
    }

    private void apply(Change change) {
        ServerLevel level = change.key.level;
        if (!level.getServer().isSameThread()) { throw new IllegalStateException("World mutation outside server thread"); }
        if (change.kind == Kind.UNLOAD_LEVEL) {
            var old = worlds.remove(level); if (old != null) { old.close(); }
            observer.levelRemoved(level); return;
        }
        if (change.kind == Kind.UNLOAD_CHUNK) {
            var registry = worlds.get(level);
            if (registry != null) { registry.unloadChunk(ChunkPos.getX(change.key.coordinate), ChunkPos.getZ(change.key.coordinate)); }
            observer.chunkRemoved(level, ChunkPos.getX(change.key.coordinate), ChunkPos.getZ(change.key.coordinate));
            return;
        }
        int cx = change.key.scope == Scope.CHUNK ? ChunkPos.getX(change.key.coordinate) : change.position.getX() >> 4;
        int cz = change.key.scope == Scope.CHUNK ? ChunkPos.getZ(change.key.coordinate) : change.position.getZ() >> 4;
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) {
            if (change.kind == Kind.LOAD_CHUNK) { deferredLoads++; enqueue(change, true); }
            else {
                var registry = worlds.get(level);
                if (registry != null) { registry.unloadChunk(cx, cz); }
                observer.chunkRemoved(level, cx, cz);
            }
            return;
        }
        if (change.kind == Kind.LOAD_CHUNK) {
            var registry = worlds.get(level);
            if (registry != null) { registry.unloadChunk(cx, cz); }
            observer.chunkRemoved(level, cx, cz);
            // This public set includes pending saved block-entity positions.
            // Sampling states does not instantiate a block entity or load a chunk.
            for (BlockPos at : chunk.getBlockEntitiesPos()) { sample(level, chunk, at); }
        } else { sample(level, chunk, change.position); }
    }

    private void sample(ServerLevel level, LevelChunk chunk, BlockPos at) {
        Long loss = losses.get(BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(at).getBlock()));
        var registry = worlds.get(level);
        if (loss != null) {
            if (!chunk.getBlockState(at).hasBlockEntity()) { throw new IllegalStateException("Registered conductor requires a block entity"); }
            if (registry == null) { registry = new ConductorRegistry(maximumNodes, maximumSources); worlds.put(level, registry); }
            registry.put(new ConductorRegistry.Position(at.getX(), at.getY(), at.getZ()), loss);
        } else if (registry != null) { registry.remove(new ConductorRegistry.Position(at.getX(), at.getY(), at.getZ())); }
        sampledPositions++;
        observer.position(level, chunk, at);
    }

    private synchronized boolean pendingFor(ServerLevel level) {
        return pending.keySet().stream().anyMatch(key -> key.level == level);
    }

    public boolean ready(ServerLevel level) {
        return !closed && failure.isEmpty() && level.getServer() == server && server.isSameThread()
                && server.getLevel(level.dimension()) == level && !pendingFor(level);
    }

    public ConductorRegistry.Snapshot snapshot(ServerLevel level) {
        if (!ready(level)) { throw new IllegalStateException("Topology is pending, closed, failed or on another thread"); }
        return worlds.computeIfAbsent(level, key -> new ConductorRegistry(maximumNodes, maximumSources)).snapshot();
    }

    public boolean isCurrent(ServerLevel level, ConductorRegistry.Snapshot snapshot) {
        if (!ready(level)) { return false; }
        var registry = worlds.get(level);
        return registry != null && registry.isCurrent(snapshot);
    }

    public synchronized Metrics metrics() {
        return new Metrics(worlds.size(), pending.size(), chunkLoads, chunkUnloads, blockSignals,
                sampledPositions, deferredLoads, accessibilityChanges, closed, failure);
    }

    private void clearWorlds() {
        for (var registry : worlds.values()) { registry.close(); }
        worlds.clear(); observer.cleared();
    }

    @SubscribeEvent
    public void onStopped(ServerStoppedEvent event) { if (event.getServer() == server) { close(); } }

    @Override
    public synchronized void close() {
        if (closed) { return; }
        if (!server.isSameThread()) { throw new IllegalStateException("Close on the server thread"); }
        synchronized (PlatformTopology.class) {
            var instances = INSTANCES.get(server);
            if (instances != null) { instances.remove(this); if (instances.isEmpty()) INSTANCES.remove(server); }
        }
        clearWorlds(); pending.clear(); closed = true; server = null; observer = NO_OBSERVER; NeoForge.EVENT_BUS.unregister(this);
    }
}
