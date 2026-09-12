// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.util.Objects;

/**
 * One packet shared by receiver contacts on an indexed tree. The caller supplies
 * receiver priority; this class does not reproduce a game's selection or random
 * distribution. Only safe conductors and accepting receivers are in scope.
 */
public final class PacketDistributor {
    private PacketDistributor() { }

    /** Immutable result, with caller-order receiver IDs retained in the vector. */
    public static final class Allocation {
        private final long debit;
        private final long loss;
        private final long[] credits;

        private Allocation(long debit, long loss, long[] credits) {
            this.debit = debit;
            this.loss = loss;
            this.credits = credits;
        }

        public long sourceDebit() { return debit; }
        public long dissipated() { return loss; }
        public int receiverCount() { return credits.length; }
        public long credit(int receiver) { return credits[receiver]; }
        public long[] credits() { return credits.clone(); }
    }

    /**
     * Requires a complete packet in reserve once, then visits each receiver at
     * most once in the supplied permutation. Route queries use the shared index.
     * Whole path loss is charged separately for each positive receiver delivery.
     * No world state or caller-owned arrays are mutated.
     */
    public static Allocation allocate(long reserve, long packet, TreeTopology.Routes routes,
                                      int[] contacts, long[] room, int[] priority) {
        Objects.requireNonNull(routes, "routes");
        Objects.requireNonNull(contacts, "contacts");
        Objects.requireNonNull(room, "room");
        Objects.requireNonNull(priority, "priority");
        if (reserve < 0 || packet <= 0 || contacts.length != room.length || priority.length != room.length) {
            throw new IllegalArgumentException("Invalid packet or receiver vectors");
        }
        int count = room.length;
        boolean[] seen = new boolean[count];
        long[] losses = new long[count];
        for (int i = 0; i < count; i++) {
            int receiver = priority[i];
            if (receiver < 0 || receiver >= count || seen[receiver]) {
                throw new IllegalArgumentException("Priority must be a complete receiver permutation");
            }
            seen[receiver] = true;
            losses[i] = routes.wholeLossTo(contacts[i]);
            if (room[i] < 0 || losses[i] >= packet) {
                throw new IllegalArgumentException("Negative room or path loss outside observed scope");
            }
        }
        long[] credits = new long[count];
        if (reserve < packet) {
            return new Allocation(0, 0, credits);
        }
        long remaining = packet;
        long dissipated = 0;
        for (int receiver : priority) {
            long loss = losses[receiver];
            if (room[receiver] == 0 || remaining <= loss) {
                continue;
            }
            long delivered = Math.min(room[receiver], remaining - loss);
            credits[receiver] = delivered;
            remaining -= delivered + loss;
            dissipated += loss;
        }
        return new Allocation(packet - remaining, dissipated, credits);
    }
}
