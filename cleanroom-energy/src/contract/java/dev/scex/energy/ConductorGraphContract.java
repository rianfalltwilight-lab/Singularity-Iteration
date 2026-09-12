// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;

/** Independent all-pairs oracle and pathological-graph structural contracts. */
public final class ConductorGraphContract {
    private static long assertions;
    private ConductorGraphContract() { }

    private static void require(boolean value, String message) {
        assertions++;
        if (!value) { throw new AssertionError(message); }
    }

    private static void rejects(Class<? extends RuntimeException> type, Runnable action) {
        try { action.run(); }
        catch (RuntimeException e) { require(type.isInstance(e), "Unexpected rejection " + e); return; }
        throw new AssertionError("Expected " + type);
    }

    public static void main(String[] args) {
        var random = new Random(0x534345588L);
        for (int sample = 0; sample < 120; sample++) {
            int count = 2 + random.nextInt(15);
            long[] losses = new long[count];
            long[][] expected = new long[count][count];
            boolean[][] adjacent = new boolean[count][count];
            long infinity = Long.MAX_VALUE / 4;
            for (int i = 0; i < count; i++) {
                losses[i] = random.nextInt(1001);
                Arrays.fill(expected[i], infinity);
                expected[i][i] = losses[i];
            }
            var links = new ArrayList<int[]>();
            for (int a = 0; a < count; a++) {
                for (int b = a + 1; b < count; b++) {
                    if (random.nextInt(4) == 0) {
                        links.add(new int[]{a, b}); adjacent[a][b] = adjacent[b][a] = true;
                        expected[a][b] = expected[b][a] = losses[a] + losses[b];
                    }
                }
            }
            // All-pairs dynamic programming is independent of the production
            // indexed heap and single-source traversal.
            for (int k = 0; k < count; k++) {
                for (int a = 0; a < count; a++) {
                    for (int b = 0; b < count; b++) {
                        if (expected[a][k] != infinity && expected[k][b] != infinity) {
                            expected[a][b] = Math.min(expected[a][b], expected[a][k] + expected[k][b] - losses[k]);
                        }
                    }
                }
            }
            int[][] inputLinks = links.toArray(int[][]::new);
            var graph = new ConductorGraph(losses, inputLinks);
            for (int[] edge : inputLinks) { edge[0] = -1; }
            for (int source = 0; source < count; source++) {
                var routes = graph.routesFrom(source);
                for (int sink = 0; sink < count; sink++) {
                    require(routes.reaches(sink) == (expected[source][sink] != infinity), "Reachability");
                    if (!routes.reaches(sink)) { continue; }
                    require(routes.lossMilliTo(sink) == expected[source][sink], "All-pairs cost oracle");
                    var path = new ArrayList<Integer>(); routes.visitPath(sink, path::add);
                    require(path.getFirst() == sink && path.getLast() == source, "Path endpoints");
                    require(new HashSet<>(path).size() == path.size(), "Chosen path has no cycle");
                    long sum = 0;
                    for (int i = 0; i < path.size(); i++) {
                        sum += losses[path.get(i)];
                        if (i > 0) { require(adjacent[path.get(i - 1)][path.get(i)], "Real path edge"); }
                    }
                    require(sum == routes.lossMilliTo(sink), "Path costs match index");
                }
            }
        }
        var zeroCycle = new ConductorGraph(new long[3], new int[][]{{0, 1}, {1, 2}, {2, 0}}).routesFrom(2);
        var path = new ArrayList<Integer>(); zeroCycle.visitPath(0, path::add);
        require(path.size() <= 3 && zeroCycle.lossMilliTo(0) == 0, "Zero-cost cycle terminates");
        var island = new ConductorGraph(new long[]{200, 200}, new int[0][]).routesFrom(0);
        require(!island.reaches(1), "Disconnected component");
        require(PacketDistributor.allocate(32, 32, island, new int[]{1}, new long[]{1000}, new int[]{0}).sourceDebit() == 0,
                "Unreachable receiver cannot drain source");
        rejects(IllegalStateException.class, () -> island.lossMilliTo(1));
        rejects(IllegalArgumentException.class, () -> island.reaches(2));
        rejects(IllegalArgumentException.class, () -> new ConductorGraph(new long[]{-1}, new int[0][]));
        rejects(IllegalArgumentException.class, () -> new ConductorGraph(new long[2], new int[][]{{0, 1}, {1, 0}}));
        rejects(IllegalArgumentException.class, () -> new ConductorGraph(new long[2], new int[][]{{0, 0}}));
        rejects(ArithmeticException.class, () -> new ConductorGraph(new long[]{Long.MAX_VALUE, 1}, new int[][]{{0, 1}}).routesFrom(0));
        var alternative = new ConductorGraph(new long[]{0, Long.MAX_VALUE, 1}, new int[][]{{0, 1}, {1, 2}, {0, 2}}).routesFrom(0);
        require(alternative.lossMilliTo(1) == Long.MAX_VALUE && alternative.lossMilliTo(2) == 1,
                "Overflowing detour does not invalidate a representable shortest route");
        long[] mutable = {200, 25};
        var immutable = new ConductorGraph(mutable, new int[][]{{0, 1}}); mutable[1] = 10000;
        require(immutable.routesFrom(0).lossMilliTo(1) == 225, "Immutable conductor costs");

        int size = 100000;
        long[] weights = new long[size]; Arrays.fill(weights, 1);
        int[][] ring = new int[size][2];
        for (int i = 0; i < size; i++) { ring[i][0] = i; ring[i][1] = (i + 1) % size; }
        var routes = new ConductorGraph(weights, ring).routesFrom(0);
        for (int i = 0; i < size; i++) {
            require(routes.lossMilliTo(i) == 1L + Math.min(i, size - i), "Large ring minimum cost");
        }
        long checksum = 0;
        for (int i = 0; i < 1000000; i++) { checksum += routes.lossMilliTo(size / 2); }
        require(checksum == 50001000000L, "Million constant-time indexed graph queries");
        System.out.println("SCEX_GRAPH_CONTRACT random_graphs=120 ring_vertices=100000 indexed_queries=1000000 assertions=" + assertions + " PASS");
    }
}
