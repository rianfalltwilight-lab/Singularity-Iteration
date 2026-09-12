// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Structural and size-bound contracts for the new independent tree snapshot. */
public final class TreeTopologyContract {
    private static long assertions;

    private TreeTopologyContract() { }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void rejects(Class<? extends RuntimeException> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            require(type.isInstance(exception), "Unexpected rejection: " + exception);
            return;
        }
        throw new AssertionError("Expected rejection: " + type);
    }

    public static void main(String[] args) {
        long[] losses = {200, 200, 25, 800, 400};
        int[][] edges = {{0, 1}, {0, 2}, {2, 3}, {2, 4}};
        var tree = new TreeTopology(losses, edges);
        var routes = tree.routesFrom(0);
        require(tree.conductorCount() == 5, "Vertex count");
        require(routes.lossMilliTo(3) == 1025 && routes.wholeLossTo(3) == 1, "Accumulate before rounding");
        require(routes.conductorsTo(3) == 3, "Unique route includes source and receiver contacts");
        var path = new ArrayList<Integer>();
        routes.visitPath(3, path::add);
        require(path.equals(List.of(3, 2, 0)), "Concrete path membership");
        require(tree.routesFrom(4).lossMilliTo(1) == 825, "Rerooted shared route");
        require(tree.routesFrom(3).lossMilliTo(0) == routes.lossMilliTo(3), "Path reversal");
        Arrays.fill(losses, 0);
        edges[0][0] = 4;
        require(tree.routesFrom(0).lossMilliTo(3) == 1025, "Input mutation cannot change snapshot");
        require(routes.lossMilliTo(3) == 1025, "Existing index remains immutable");

        int size = 100000;
        long[] chainLoss = new long[size];
        Arrays.fill(chainLoss, 25);
        int[][] chainEdges = new int[size - 1][2];
        for (int i = 0; i < size - 1; i++) {
            chainEdges[i][0] = i;
            chainEdges[i][1] = i + 1;
        }
        var chain = new TreeTopology(chainLoss, chainEdges);
        var chainRoutes = chain.routesFrom(0);
        for (int i = 0; i < size; i++) {
            require(chainRoutes.lossMilliTo(i) == (i + 1L) * 25, "Long-chain prefix loss");
            require(chainRoutes.conductorsTo(i) == i + 1, "Long-chain length");
        }
        long[] visited = {0};
        chainRoutes.visitPath(size - 1, vertex -> visited[0]++);
        require(visited[0] == size, "Iterative traversal does not overflow stack");
        long checksum = 0;
        for (int i = 0; i < 1000000; i++) {
            checksum += chainRoutes.wholeLossTo(size - 1);
        }
        require(checksum == 2500000000L, "One million constant-time indexed queries");
        require(chain.routesFrom(size - 1).lossMilliTo(0) == size * 25L, "Long-chain reversal");

        var singleton = new TreeTopology(new long[]{200}, new int[0][]).routesFrom(0);
        require(singleton.conductorsTo(0) == 1 && singleton.wholeLossTo(0) == 0, "Single conductor");
        rejects(NullPointerException.class, () -> new TreeTopology(null, new int[0][]));
        rejects(IllegalArgumentException.class, () -> new TreeTopology(new long[0], new int[0][]));
        rejects(IllegalArgumentException.class, () -> new TreeTopology(new long[]{-1}, new int[0][]));
        rejects(IllegalArgumentException.class, () -> new TreeTopology(new long[2], new int[][]{{0, 0}}));
        rejects(IllegalArgumentException.class, () -> new TreeTopology(new long[2], new int[][]{{0, 2}}));
        rejects(IllegalArgumentException.class, () -> new TreeTopology(new long[2], new int[][]{{0}}));
        rejects(IllegalArgumentException.class, () -> new TreeTopology(new long[3], new int[][]{{0, 1}, {0, 1}}));
        rejects(IllegalArgumentException.class, () -> new TreeTopology(new long[4], new int[][]{{0, 1}, {1, 2}, {2, 0}}));
        rejects(IllegalArgumentException.class, () -> new TreeTopology(new long[3], new int[][]{{0, 1}}));
        rejects(IllegalArgumentException.class, () -> tree.routesFrom(-1));
        rejects(IllegalArgumentException.class, () -> routes.wholeLossTo(5));
        rejects(NullPointerException.class, () -> routes.visitPath(0, null));
        rejects(ArithmeticException.class, () -> new TreeTopology(new long[]{Long.MAX_VALUE, 1},
                new int[][]{{0, 1}}).routesFrom(0));
        System.out.println("SCEX_TREE_CONTRACT vertices=100000 indexed_queries=1000000 assertions=" + assertions + " PASS");
    }
}
