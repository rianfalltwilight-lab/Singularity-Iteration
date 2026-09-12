// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import dev.scex.energy.ConductorRegistry.Position;

/** Lifecycle/observable connectivity contracts, independent of registry internals. */
public final class ConductorRegistryContract {
    private static long assertions;
    private ConductorRegistryContract() { }
    private static void require(boolean value, String message) {
        assertions++; if (!value) { throw new AssertionError(message); }
    }
    private static Position p(int x, int y, int z) { return new Position(x, y, z); }
    private static void rejects(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { assertions++; return; }
        throw new AssertionError("Expected rejection");
    }
    private static Set<Position> connected(Set<Position> expected, Position start) {
        var reached = new HashSet<Position>(); var queue = new ArrayDeque<Position>();
        reached.add(start); queue.add(start);
        while (!queue.isEmpty()) {
            Position at = queue.remove();
            // Independent slow membership reference, without constructing graph IDs.
            for (Position next : expected) {
                long distance = Math.abs((long) next.x() - at.x()) + Math.abs((long) next.y() - at.y()) + Math.abs((long) next.z() - at.z());
                if (distance == 1 && reached.add(next)) { queue.add(next); }
            }
        }
        return reached;
    }

    public static void main(String[] args) throws Exception {
        rejects(() -> new ConductorRegistry(0, 1)); rejects(() -> new ConductorRegistry(1, 0));
        try (var registry = new ConductorRegistry(4, 2)) {
            var empty = registry.snapshot(); require(empty.size() == 0, "Empty snapshot");
            require(empty == registry.snapshot() && registry.rebuildCount() == 1, "Coalesced empty query");
            rejects(() -> empty.routesFrom(p(0, 0, 0)));
            for (int x = 0; x < 3; x++) { require(registry.put(p(x, 0, 0), 500), "Register line"); }
            require(!registry.isCurrent(empty), "Old lease cannot commit"); rejects(empty::size);
            require(registry.rebuildCount() == 1, "Edits do not eagerly rebuild");
            var line = registry.snapshot(); var routes = line.routesFrom(p(0, 0, 0));
            int end = line.vertex(p(2, 0, 0));
            require(routes.lossMilliTo(end) == 1500, "Registered loss/path inputs");
            require(PacketDistributor.allocate(32, 32, routes, new int[]{end}, new long[]{100}, new int[]{0}).credit(0) == 31, "Live topology account");
            long revision = registry.revision();
            require(!registry.put(p(1, 0, 0), 500) && !registry.remove(p(99, 0, 0)), "No-op edits");
            require(registry.revision() == revision && registry.snapshot() == line, "No-op retains lease");
            registry.remove(p(1, 0, 0)); rejects(line::size); require(!registry.isCurrent(line), "Split invalidates");
            var split = registry.snapshot(); int splitEnd = split.vertex(p(2, 0, 0));
            var disconnected = PacketDistributor.allocate(32, 32, split.routesFrom(p(0, 0, 0)), new int[]{splitEnd}, new long[]{100}, new int[]{0});
            require(disconnected.sourceDebit() == 0 && disconnected.credit(0) == 0, "No energy across removed bridge");
            registry.put(p(1, 0, 0), 1000); var restored = registry.snapshot();
            require(restored.routesFrom(p(0, 0, 0)).lossMilliTo(restored.vertex(p(2, 0, 0))) == 2000, "Replacement updates route loss");
            var first = restored.routesFrom(p(0, 0, 0)); var second = restored.routesFrom(p(1, 0, 0));
            require(restored.routesFrom(p(0, 0, 0)) == first, "Source index reused");
            restored.routesFrom(p(2, 0, 0)); require(restored.cachedSources() == 2, "Cache bounded");
            require(restored.routesFrom(p(0, 0, 0)) == first && restored.routesFrom(p(1, 0, 0)) != second, "Least-recent route evicted");
            registry.put(p(3, 0, 0), 0); var full = registry.snapshot();
            rejects(() -> registry.put(p(4, 0, 0), 0)); rejects(() -> registry.put(p(0, 0, 0), -1));
            require(registry.isCurrent(full) && registry.size() == 4, "Rejected update is atomic");
            try (var other = new ConductorRegistry(1, 1)) { require(!other.isCurrent(full), "Foreign snapshot cannot commit"); }
            var foreign = new AtomicReference<Throwable>();
            Thread thread = new Thread(() -> {
                try { registry.remove(p(0, 0, 0)); } catch (Throwable error) { foreign.set(error); }
            }); thread.start(); thread.join(); require(foreign.get() instanceof IllegalStateException, "Cross-thread edit rejected");
        }
        var closed = new ConductorRegistry(2, 1); closed.put(p(0, 0, 0), 0); var lease = closed.snapshot();
        closed.close(); closed.close(); rejects(closed::size); rejects(lease::size); rejects(() -> closed.isCurrent(lease));
        try (var chunks = new ConductorRegistry(10, 1)) {
            for (int x : new int[]{-17, -16, -1, 0, 15, 16}) { chunks.put(p(x, 80, -1), 0); }
            var before = chunks.snapshot(); require(chunks.unloadChunk(-1, -1) == 2, "Negative chunk floor");
            rejects(before::size); var after = chunks.snapshot();
            require(!after.contains(p(-16, 80, -1)) && !after.contains(p(-1, 80, -1)) && after.contains(p(0, 80, -1)), "Only target chunk removed");
            require(chunks.unloadChunk(-1, -1) == 0 && chunks.snapshot() == after, "Repeated unload has no side effects");
        }
        try (var bounds = new ConductorRegistry(4, 1)) {
            bounds.put(p(Integer.MAX_VALUE, 0, 0), 0); bounds.put(p(Integer.MIN_VALUE, 0, 0), 0);
            var snapshot = bounds.snapshot();
            require(!snapshot.routesFrom(p(Integer.MAX_VALUE, 0, 0)).reaches(snapshot.vertex(p(Integer.MIN_VALUE, 0, 0))), "Coordinates do not wrap into neighbours");
        }
        var random = new Random(0x52454749535452L); var expected = new HashSet<Position>();
        int randomEdits = 2000;
        try (var registry = new ConductorRegistry(512, 3)) {
            for (int edit = 0; edit < randomEdits; edit++) {
                Position at = p(random.nextInt(8) - 4, random.nextInt(4), random.nextInt(8) - 4);
                var old = registry.snapshot(); boolean changed;
                if (edit % 29 == 0) {
                    int cx = at.x() >> 4, cz = at.z() >> 4;
                    int count = expected.size(); expected.removeIf(v -> v.x() >> 4 == cx && v.z() >> 4 == cz);
                    int removed = count - expected.size(); require(registry.unloadChunk(cx, cz) == removed, "Random chunk unload count"); changed = removed != 0;
                } else if (random.nextBoolean()) {
                    changed = expected.add(at); require(registry.put(at, 0) == changed, "Random registration result");
                } else {
                    changed = expected.remove(at); require(registry.remove(at) == changed, "Random removal result");
                }
                require(registry.isCurrent(old) != changed, "Lease matches actual edit");
                var snapshot = registry.snapshot(); require(snapshot.size() == expected.size(), "Random membership size");
                for (Position position : expected) { require(snapshot.contains(position), "Random membership contents"); }
                if (!expected.isEmpty()) {
                    Position start = expected.iterator().next(); Set<Position> reachable = connected(expected, start);
                    var routes = snapshot.routesFrom(start);
                    for (Position to : expected) { require(routes.reaches(snapshot.vertex(to)) == reachable.contains(to), "Independent connectivity after edit"); }
                }
            }
        }
        int size = 100000;
        try (var registry = new ConductorRegistry(size, 2)) {
            for (int i = 0; i < size; i++) { registry.put(p(i, 0, 0), 1); }
            require(registry.rebuildCount() == 0, "Large edit batch coalesced");
            var snapshot = registry.snapshot(); require(registry.rebuildCount() == 1, "One rebuild per batch");
            var path = snapshot.routesFrom(p(0, 0, 0));
            require(path.lossMilliTo(snapshot.vertex(p(size - 1, 0, 0))) == size, "Large registered chain");
            for (int i = 0; i < 10000; i++) { require(registry.snapshot() == snapshot, "Stable snapshot reused"); }
            require(registry.unloadChunk(3000, 0) == 16, "Large registry targeted chunk removal");
            var split = registry.snapshot();
            require(!split.routesFrom(p(0, 0, 0)).reaches(split.vertex(p(size - 1, 0, 0))), "Unloaded middle does not continue supplying");
        }
        System.out.println("SCEX_REGISTRY_CONTRACT random_edits=" + randomEdits + " registered_nodes=" + size
                + " stable_snapshot_queries=10000 assertions=" + assertions + " PASS platform_lifecycle_NOT_verified");
    }
}
