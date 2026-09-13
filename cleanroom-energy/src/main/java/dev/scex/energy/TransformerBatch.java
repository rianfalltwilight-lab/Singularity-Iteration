// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.util.Objects;
import java.util.List;
import java.util.ArrayList;

/** Transformer output batch, with receiver order and route losses explicitly supplied. */
public final class TransformerBatch {
    private TransformerBatch() { }

    public static final class Allocation {
        private final long debit;
        private final long[] credits;
        private final long dissipated;
        private final List<Delivery> deliveries;
        private Allocation(long debit, long[] credits, long dissipated, List<Delivery> deliveries) {
            this.debit = debit; this.credits = credits; this.dissipated = dissipated;
            this.deliveries = List.copyOf(deliveries);
        }
        public long debit() { return debit; }
        public long credit(int receiver) { return credits[receiver]; }
        public int receiverCount() { return credits.length; }
        public long dissipated() { return dissipated; }
        /** Numeric packet decomposition; not a claim about reference event ordering. */
        public List<Delivery> deliveries() { return deliveries; }
    }

    public record Delivery(int receiver, long sourceDebit, long credit, long pathLoss) {
        public Delivery {
            if (receiver < 0 || credit <= 0 || pathLoss < 0 || sourceDebit != Math.addExact(credit, pathLoss))
                throw new IllegalArgumentException("Invalid packet delivery");
        }
    }

    /**
     * Quotes whole output packets from the starting buffer. A demand no larger
     * than one nominal packet receives its exact amount; larger demand accepts
     * the remaining quoted batch. Partial consumption can leave a residual for
     * a later receiver. Inputs are not mutated. Caller owns topology, ordering,
     * input/output scheduling and commit; overload is not modeled.
     */
    public static Allocation allocate(TransformerAccounting.Configuration configuration,
                                      long buffer, long[] room, int[] priority) {
        Objects.requireNonNull(room, "room");
        return allocate(configuration, buffer, room, priority, new long[room.length]);
    }

    /** Whole-EU path loss is paid per positive packet, including a usable residual. */
    public static Allocation allocate(TransformerAccounting.Configuration configuration,
                                      long buffer, long[] room, int[] priority, long[] pathLoss) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(room, "room");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(pathLoss, "pathLoss");
        if (buffer < 0 || buffer > configuration.capacity() || room.length != priority.length || room.length != pathLoss.length)
            throw new IllegalArgumentException("Invalid buffer or vector dimensions");
        boolean[] seen = new boolean[room.length];
        for (int id : priority) {
            if (id < 0 || id >= room.length || seen[id])
                throw new IllegalArgumentException("Priority must be a complete permutation");
            seen[id] = true;
        }
        for (long value : room) if (value < 0) throw new IllegalArgumentException("Negative demand");
        for (long value : pathLoss) if (value < 0) throw new IllegalArgumentException("Negative path loss");
        long packet = configuration.outputPacket();
        long budget = Math.min(buffer / packet, configuration.outputPackets()) * packet;
        long remaining = budget;
        long[] credits = new long[room.length];
        long dissipated = 0;
        List<Delivery> deliveries = new ArrayList<>();
        for (int id : priority) {
            long loss = pathLoss[id];
            if (room[id] == 0 || loss >= packet || remaining <= loss) continue;
            if (room[id] <= Math.min(packet, remaining) - loss) {
                long debit = room[id] + loss;
                deliveries.add(new Delivery(id, debit, room[id], loss));
                credits[id] = room[id];
                remaining -= debit;
                dissipated = Math.addExact(dissipated, loss);
            } else {
                long whole = remaining / packet, tail = remaining % packet;
                for (long i = 0; i < whole; i++) {
                    deliveries.add(new Delivery(id, packet, packet - loss, loss));
                    credits[id] = Math.addExact(credits[id], packet - loss);
                    remaining -= packet;
                    dissipated = Math.addExact(dissipated, loss);
                }
                // A residual unable to pay this route's loss stays available;
                // it must not suppress the full packets or be dissipated alone.
                if (tail > loss) {
                    deliveries.add(new Delivery(id, tail, tail - loss, loss));
                    credits[id] = Math.addExact(credits[id], tail - loss);
                    remaining -= tail;
                    dissipated = Math.addExact(dissipated, loss);
                }
            }
        }
        return new Allocation(budget - remaining, credits, dissipated, deliveries);
    }
}
