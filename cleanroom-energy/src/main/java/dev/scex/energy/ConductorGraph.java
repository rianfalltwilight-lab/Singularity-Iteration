// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Immutable undirected conductor graph, including cycles and disconnected parts.
 * An independently chosen minimum-loss path model, not a claim about a game's
 * internal algorithm or which equal-cost physical path it selects.
 */
public final class ConductorGraph {
    private final long[] losses;
    private final int[] offsets;
    private final int[] neighbours;
    private final int[] components;

    public ConductorGraph(long[] conductorLossMilli, int[][] links) {
        Objects.requireNonNull(conductorLossMilli, "conductorLossMilli");
        Objects.requireNonNull(links, "links");
        int count = conductorLossMilli.length;
        if (count == 0 || count == Integer.MAX_VALUE || links.length > (Integer.MAX_VALUE - 1) / 2) {
            throw new IllegalArgumentException("Graph dimensions outside representable range");
        }
        losses = conductorLossMilli.clone();
        for (long loss : losses) {
            if (loss < 0) { throw new IllegalArgumentException("Negative conductor loss"); }
        }
        offsets = new int[count + 1];
        int[] ends = new int[links.length * 2];
        var unique = new HashSet<Long>();
        for (int i = 0; i < links.length; i++) {
            int[] link = Objects.requireNonNull(links[i], "link");
            if (link.length != 2) { throw new IllegalArgumentException("Link needs two endpoints"); }
            int a = link[0]; int b = link[1];
            check(a, count); check(b, count);
            long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
            if (a == b || !unique.add(key)) { throw new IllegalArgumentException("Self or duplicate link"); }
            ends[2 * i] = a; ends[2 * i + 1] = b;
            offsets[a + 1]++; offsets[b + 1]++;
        }
        for (int i = 1; i <= count; i++) { offsets[i] += offsets[i - 1]; }
        neighbours = new int[ends.length];
        int[] cursor = offsets.clone();
        for (int i = 0; i < ends.length; i += 2) {
            neighbours[cursor[ends[i]]++] = ends[i + 1];
            neighbours[cursor[ends[i + 1]]++] = ends[i];
        }
        // Label physical wire components once. Endpoint machines are not wire
        // vertices and therefore cannot accidentally merge distinct domains.
        components = new int[count]; Arrays.fill(components, -1);
        int[] queue = new int[count];
        for (int seed = 0; seed < count; seed++) {
            if (components[seed] >= 0) continue;
            int head = 0, tail = 0; queue[tail++] = seed; components[seed] = seed;
            while (head < tail) {
                int vertex = queue[head++];
                for (int i = offsets[vertex]; i < offsets[vertex + 1]; i++) {
                    int next = neighbours[i];
                    if (components[next] >= 0) continue;
                    components[next] = seed; queue[tail++] = next;
                }
            }
        }
    }

    /** Stable snapshot-local ID of the physical conductor component. */
    public int componentOf(int vertex) { check(vertex, losses.length); return components[vertex]; }

    /** O((V+E) log V) index construction; at most V active heap entries. */
    public Routes routesFrom(int sourceContact) {
        check(sourceContact, losses.length);
        long[] costs = new long[losses.length];
        int[] parents = new int[losses.length];
        Arrays.fill(parents, -2);
        boolean[] known = new boolean[losses.length];
        boolean[] settled = new boolean[losses.length];
        boolean[] overflowFrontier = new boolean[losses.length];
        var heap = new VertexHeap(costs);
        costs[sourceContact] = losses[sourceContact];
        known[sourceContact] = true;
        parents[sourceContact] = -1;
        heap.update(sourceContact);
        while (!heap.empty()) {
            int vertex = heap.take();
            settled[vertex] = true;
            for (int i = offsets[vertex]; i < offsets[vertex + 1]; i++) {
                int next = neighbours[i];
                if (settled[next]) { continue; }
                if (losses[next] > Long.MAX_VALUE - costs[vertex]) {
                    overflowFrontier[next] = true;
                    continue;
                }
                long proposed = costs[vertex] + losses[next];
                if (!known[next] || proposed < costs[next]) {
                    costs[next] = proposed;
                    parents[next] = vertex;
                    known[next] = true;
                    heap.update(next);
                }
            }
        }
        for (int i = 0; i < losses.length; i++) {
            if (overflowFrontier[i] && !settled[i]) {
                throw new ArithmeticException("A reachable route cost cannot fit in long");
            }
        }
        return new Routes(costs, parents, settled);
    }

    private static void check(int vertex, int count) {
        if (vertex < 0 || vertex >= count) { throw new IllegalArgumentException("Invalid conductor ID"); }
    }

    /** No world references; callers replace the index when its topology changes. */
    public static final class Routes implements RouteCosts {
        private final long[] costs;
        private final int[] parents;
        private final boolean[] reached;

        private Routes(long[] costs, int[] parents, boolean[] reached) {
            this.costs = costs; this.parents = parents; this.reached = reached;
        }

        @Override
        public boolean reaches(int receiverContact) {
            check(receiverContact, costs.length);
            return reached[receiverContact];
        }

        @Override
        public long lossMilliTo(int receiverContact) {
            if (!reaches(receiverContact)) { throw new IllegalStateException("Unreachable contact"); }
            return costs[receiverContact];
        }

        public void visitPath(int receiverContact, IntConsumer visitor) {
            Objects.requireNonNull(visitor, "visitor");
            if (!reaches(receiverContact)) { throw new IllegalStateException("Unreachable contact"); }
            for (int vertex = receiverContact; vertex != -1; vertex = parents[vertex]) { visitor.accept(vertex); }
        }
    }

    /** Indexed binary heap avoids duplicate/stale queue entries after decreases. */
    private static final class VertexHeap {
        private final int[] nodes;
        private final int[] positions;
        private final long[] costs;
        private int size;

        private VertexHeap(long[] costs) {
            this.costs = costs; nodes = new int[costs.length]; positions = new int[costs.length];
            Arrays.fill(positions, -1);
        }

        private boolean empty() { return size == 0; }

        private boolean less(int a, int b) {
            return costs[a] < costs[b] || (costs[a] == costs[b] && a < b);
        }

        private void swap(int a, int b) {
            int old = nodes[a]; nodes[a] = nodes[b]; nodes[b] = old;
            positions[nodes[a]] = a; positions[nodes[b]] = b;
        }

        private void update(int vertex) {
            int at = positions[vertex];
            if (at < 0) { at = size++; nodes[at] = vertex; positions[vertex] = at; }
            while (at > 0) {
                int parent = (at - 1) / 2;
                if (!less(nodes[at], nodes[parent])) { break; }
                swap(at, parent); at = parent;
            }
        }

        private int take() {
            int result = nodes[0];
            positions[result] = -1;
            if (--size > 0) {
                nodes[0] = nodes[size]; positions[nodes[0]] = 0;
                int at = 0;
                while (at <= (size - 2) / 2 && size > 1) {
                    int child = 2 * at + 1;
                    if (child >= size) { break; }
                    if (child + 1 < size && less(nodes[child + 1], nodes[child])) { child++; }
                    if (!less(nodes[child], nodes[at])) { break; }
                    swap(at, child); at = child;
                }
            }
            return result;
        }
    }
}
