// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.util.Objects;

/** Lossless transformer output batch, with receiver order explicitly supplied. */
public final class TransformerBatch {
    private TransformerBatch() { }

    public static final class Allocation {
        private final long debit;
        private final long[] credits;
        private Allocation(long debit, long[] credits) { this.debit = debit; this.credits = credits; }
        public long debit() { return debit; }
        public long credit(int receiver) { return credits[receiver]; }
        public int receiverCount() { return credits.length; }
    }

    /**
     * Quotes whole output packets from the starting buffer. A demand no larger
     * than one nominal packet receives its exact amount; larger demand accepts
     * the remaining quoted batch. Partial consumption can leave a residual for
     * a later receiver. Inputs are not mutated. Caller owns topology, ordering,
     * input/output scheduling and commit; losses and overload are not modeled.
     */
    public static Allocation allocate(TransformerAccounting.Configuration configuration,
                                      long buffer, long[] room, int[] priority) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(room, "room");
        Objects.requireNonNull(priority, "priority");
        if (buffer < 0 || buffer > configuration.capacity() || room.length != priority.length)
            throw new IllegalArgumentException("Invalid buffer or vector dimensions");
        boolean[] seen = new boolean[room.length];
        for (int id : priority) {
            if (id < 0 || id >= room.length || seen[id])
                throw new IllegalArgumentException("Priority must be a complete permutation");
            seen[id] = true;
        }
        for (long value : room) if (value < 0) throw new IllegalArgumentException("Negative demand");
        long packet = configuration.outputPacket();
        long budget = Math.min(buffer / packet, configuration.outputPackets()) * packet;
        long remaining = budget;
        long[] credits = new long[room.length];
        for (int id : priority) {
            long amount = room[id] <= packet ? Math.min(room[id], remaining) : remaining;
            credits[id] = amount;
            remaining -= amount;
        }
        return new Allocation(budget - remaining, credits);
    }
}
