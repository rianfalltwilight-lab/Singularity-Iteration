// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Thread-confined, bounded registration of conductors on a six-neighbour lattice.
 * This new lifecycle mechanism is a platform-neutral design, not an observation
 * about an existing game's implementation. No method accesses or loads a world.
 */
public final class ConductorRegistry implements AutoCloseable {
    /** Coordinates are supplied by the platform adapter; no position is loaded. */
    public record Position(int x, int y, int z) implements Comparable<Position> {
        @Override
        public int compareTo(Position other) {
            int result = Integer.compare(x, other.x);
            if (result == 0) { result = Integer.compare(y, other.y); }
            if (result == 0) { result = Integer.compare(z, other.z); }
            return result;
        }
    }

    private final Thread owner = Thread.currentThread();
    private final int maximumNodes;
    private final int maximumCachedSources;
    private final Map<Position, Long> conductors = new HashMap<>();
    private final Map<Long, HashSet<Position>> chunks = new HashMap<>();
    private Snapshot cached;
    private boolean closed;
    private long revision;
    private long rebuilds;

    public ConductorRegistry(int maximumNodes, int maximumCachedSources) {
        if (maximumNodes <= 0 || maximumNodes > (Integer.MAX_VALUE - 1) / 6 || maximumCachedSources <= 0) {
            throw new IllegalArgumentException("Positive bounded registry/cache sizes required");
        }
        this.maximumNodes = maximumNodes;
        this.maximumCachedSources = maximumCachedSources;
    }

    private void active() {
        if (Thread.currentThread() != owner) { throw new IllegalStateException("Registry is thread-confined"); }
        if (closed) { throw new IllegalStateException("Registry is closed"); }
    }

    private static long chunkKey(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    private static long chunkKey(Position at) { return chunkKey(at.x >> 4, at.z >> 4); }

    private void changed() {
        // Fail before mutating a registry if its version cannot be represented.
        revision = Math.incrementExact(revision);
        if (cached != null) { cached.invalidate(); cached = null; }
    }

    /** Register or replace a loss. Identical updates retain valid cached work. */
    public boolean put(Position at, long lossMilli) {
        active(); Objects.requireNonNull(at, "at");
        if (lossMilli < 0) { throw new IllegalArgumentException("Negative conductor loss"); }
        Long previous = conductors.get(at);
        if (previous != null && previous.longValue() == lossMilli) { return false; }
        if (previous == null && conductors.size() >= maximumNodes) { throw new IllegalStateException("Registry capacity reached"); }
        changed();
        conductors.put(at, lossMilli);
        if (previous == null) { chunks.computeIfAbsent(chunkKey(at), key -> new HashSet<>()).add(at); }
        return true;
    }

    public boolean remove(Position at) {
        active(); Objects.requireNonNull(at, "at");
        if (!conductors.containsKey(at)) { return false; }
        changed(); conductors.remove(at);
        long key = chunkKey(at); var members = chunks.get(key); members.remove(at);
        if (members.isEmpty()) { chunks.remove(key); }
        return true;
    }

    /** Work is proportional to registered nodes in that chunk, not the world. */
    public int unloadChunk(int chunkX, int chunkZ) {
        active(); long key = chunkKey(chunkX, chunkZ); var members = chunks.get(key);
        if (members == null) { return 0; }
        changed();
        for (Position at : members) { conductors.remove(at); }
        chunks.remove(key);
        return members.size();
    }

    public int size() { active(); return conductors.size(); }
    /** Cheap membership observation without constructing or retaining a graph snapshot. */
    public boolean containsRegistered(Position at) { active(); return conductors.containsKey(Objects.requireNonNull(at, "at")); }
    public long revision() { active(); return revision; }
    public long rebuildCount() { active(); return rebuilds; }

    /** Coalesces any number of edits into one lazily rebuilt immutable topology. */
    public Snapshot snapshot() {
        active();
        if (cached != null) { return cached; }
        long nextRebuild = Math.incrementExact(rebuilds);
        Position[] positions = conductors.keySet().toArray(Position[]::new);
        Arrays.sort(positions);
        var ids = new HashMap<Position, Integer>();
        long[] losses = new long[positions.length];
        for (int i = 0; i < positions.length; i++) { ids.put(positions[i], i); losses[i] = conductors.get(positions[i]); }
        var links = new ArrayList<int[]>();
        for (int i = 0; i < positions.length; i++) {
            Position at = positions[i];
            if (at.x < Integer.MAX_VALUE) { link(links, ids, i, new Position(at.x + 1, at.y, at.z)); }
            if (at.y < Integer.MAX_VALUE) { link(links, ids, i, new Position(at.x, at.y + 1, at.z)); }
            if (at.z < Integer.MAX_VALUE) { link(links, ids, i, new Position(at.x, at.y, at.z + 1)); }
        }
        ConductorGraph graph = positions.length == 0 ? null : new ConductorGraph(losses, links.toArray(int[][]::new));
        cached = new Snapshot(owner, revision, Map.copyOf(ids), positions, graph, maximumCachedSources);
        rebuilds = nextRebuild;
        return cached;
    }

    private static void link(ArrayList<int[]> links, Map<Position, Integer> ids, int from, Position to) {
        Integer target = ids.get(to);
        if (target != null) { links.add(new int[]{from, target}); }
    }

    /** Must be checked again by a future world adapter before committing energy. */
    public boolean isCurrent(Snapshot snapshot) {
        active(); return snapshot != null && cached == snapshot && snapshot.valid;
    }

    @Override
    public void close() {
        if (Thread.currentThread() != owner) { throw new IllegalStateException("Registry is thread-confined"); }
        if (closed) { return; }
        if (cached != null) { cached.invalidate(); cached = null; }
        conductors.clear(); chunks.clear(); closed = true;
    }

    /**
     * A topology lease. Edits/close reject further lease access and release its
     * route cache. Previously returned pure route values remain old calculations;
     * their continued existence never authorizes applying them to a world.
     */
    public static final class Snapshot {
        private final Thread owner;
        private final long revision;
        private final Map<Position, Integer> ids;
        private final Position[] positions;
        private final ConductorGraph graph;
        private final int maximumCachedSources;
        private final LinkedHashMap<Integer, ConductorGraph.Routes> routes = new LinkedHashMap<>(16, .75f, true);
        private boolean valid = true;

        private Snapshot(Thread owner, long revision, Map<Position, Integer> ids, Position[] positions, ConductorGraph graph, int limit) {
            this.owner = owner; this.revision = revision; this.ids = ids; this.positions = positions; this.graph = graph; maximumCachedSources = limit;
        }

        private void active() {
            if (Thread.currentThread() != owner || !valid) { throw new IllegalStateException("Stale or foreign-thread snapshot"); }
        }
        private void invalidate() { valid = false; routes.clear(); }
        public long revision() { active(); return revision; }
        public int size() { active(); return ids.size(); }
        public boolean contains(Position at) { active(); return ids.containsKey(Objects.requireNonNull(at, "at")); }
        public int vertex(Position at) {
            active(); Integer result = ids.get(Objects.requireNonNull(at, "at"));
            if (result == null) { throw new IllegalArgumentException("Unregistered conductor position"); }
            return result;
        }
        public int cachedSources() { active(); return routes.size(); }
        public Position position(int vertex) {
            active();
            if (vertex < 0 || vertex >= positions.length) throw new IllegalArgumentException("Unknown conductor ID");
            return positions[vertex];
        }
        /** A path tied to this lease; retaining it never authorizes a later world write. */
        public Path path(Position source, Position receiver) {
            var index = routesFrom(source); int target = vertex(receiver);
            if (!index.reaches(target)) throw new IllegalArgumentException("Unreachable conductor");
            return new Path(this, index, target);
        }
        public int componentOf(Position at) { return graph.componentOf(vertex(at)); }
        public ConductorGraph.Routes routesFrom(Position source) {
            int id = vertex(source);
            var result = routes.get(id);
            if (result == null) {
                result = graph.routesFrom(id);
                if (routes.size() == maximumCachedSources) { routes.pollFirstEntry(); }
                routes.put(id, result);
            }
            return result;
        }
    }

    /** Immutable selected route. Every visit rechecks the snapshot lease. */
    public static final class Path {
        private final Snapshot snapshot;
        private final ConductorGraph.Routes routes;
        private final int receiver;
        private Path(Snapshot snapshot, ConductorGraph.Routes routes, int receiver) {
            this.snapshot = snapshot; this.routes = routes; this.receiver = receiver;
        }
        public long lossMilli() { snapshot.active(); return routes.lossMilliTo(receiver); }
        public void visit(Consumer<Position> visitor) {
            Objects.requireNonNull(visitor, "visitor"); snapshot.active();
            routes.visitPath(receiver, id -> visitor.accept(snapshot.position(id)));
        }
    }
}
