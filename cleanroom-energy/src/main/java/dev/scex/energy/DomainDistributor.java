// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Pure accounting across separate conductor domains sharing endpoint balances. */
public final class DomainDistributor {
    private DomainDistributor() { }

    /** Full tier packets for storage, bounded residual packets for generators. */
    public record Source(long reserve, long packet, boolean partialPackets) {
        public Source {
            if (reserve < 0 || packet <= 0) throw new IllegalArgumentException("Invalid source quote");
        }
    }

    /** Sources use global IDs in registration order; route/receiver IDs are shared. */
    public static final class Domain {
        private final int[] sources;
        private final List<RouteCosts> routes;
        private final int[][] priorities;

        public Domain(int[] sourceIds, List<? extends RouteCosts> sourceRoutes, int[][] receiverPriorities) {
            sources = Objects.requireNonNull(sourceIds, "sourceIds").clone();
            routes = List.copyOf(sourceRoutes);
            priorities = Objects.requireNonNull(receiverPriorities, "receiverPriorities").clone();
            if (sources.length != routes.size() || sources.length != priorities.length)
                throw new IllegalArgumentException("Domain source vector lengths");
            for (int i = 0; i < priorities.length; i++) priorities[i] = priorities[i].clone();
        }
    }

    /** Immutable aggregate vectors; checked arithmetic rejects unrepresentable totals. */
    public static final class Round {
        private final long[] debits, credits;
        private final long dissipated;
        private Round(long[] debits, long[] credits, long dissipated) {
            this.debits = debits; this.credits = credits; this.dissipated = dissipated;
        }
        public int sourceCount() { return debits.length; }
        public int receiverCount() { return credits.length; }
        public long debit(int source) { return debits[source]; }
        public long credit(int receiver) { return credits[receiver]; }
        public long dissipated() { return dissipated; }
    }

    /**
     * Independently shuffle separate conductor domains. Each domain sees room
     * remaining after prior domains, but retains that quote for its own source
     * cycle. Direct machine contacts are separate domains, as demonstrated by
     * R16's no-wire controls: they fill live room without the wired overshoot.
     * One packet budget per source is shared across all domains. The complete
     * reserve threshold is checked once, before any domain; a partially spent
     * packet may continue into later domains. Incoming credit cannot replenish
     * that budget in this round. A fresh snapshot is required before any commit.
     * Mixed shared-source/multiple-domain behavior remains an integration model,
     * not an assertion that all target scheduling details have been established.
     */
    public static Round allocate(List<Source> sourceQuotes, List<Domain> conductorDomains,
                                 int[] receiverContacts, long[] receiverRoom, RandomGenerator random) {
        var sources = List.copyOf(sourceQuotes);
        var domains = List.copyOf(conductorDomains);
        int[] contacts = Objects.requireNonNull(receiverContacts, "receiverContacts").clone();
        long[] remaining = Objects.requireNonNull(receiverRoom, "receiverRoom").clone();
        Objects.requireNonNull(random, "random");
        if (contacts.length != remaining.length) throw new IllegalArgumentException("Receiver vector lengths");
        for (long room : remaining) if (room < 0) throw new IllegalArgumentException("Negative room");
        for (var domain : domains) {
            boolean[] seen = new boolean[sources.size()];
            for (int i = 0; i < domain.sources.length; i++) {
                int source = domain.sources[i];
                if (source < 0 || source >= seen.length || seen[source])
                    throw new IllegalArgumentException("Invalid or duplicate domain source");
                seen[source] = true;
                if (domain.priorities[i].length != remaining.length)
                    throw new IllegalArgumentException("Receiver priority dimensions");
            }
        }
        int[] domainOrder = new int[domains.size()];
        for (int i = 0; i < domainOrder.length; i++) domainOrder[i] = i;
        for (int end = domainOrder.length - 1; end > 0; end--) {
            int other = random.nextInt(end + 1), saved = domainOrder[end];
            domainOrder[end] = domainOrder[other]; domainOrder[other] = saved;
        }
        long[] budgets = new long[sources.size()];
        for (int source = 0; source < sources.size(); source++) {
            var quote = sources.get(source);
            budgets[source] = quote.partialPackets() ? Math.min(quote.reserve(), quote.packet())
                : quote.reserve() >= quote.packet() ? quote.packet() : 0;
        }
        long[] debits = new long[sources.size()], credits = new long[remaining.length];
        long loss = 0;
        for (int domainId : domainOrder) {
            var domain = domains.get(domainId);
            var offers = new ArrayList<MultiSourceDistributor.Offer>();
            int[] sourceMap = new int[domain.sources.length];
            for (int i = 0; i < domain.sources.length; i++) {
                int source = domain.sources[i];
                long packet = budgets[source] - debits[source];
                if (packet <= 0) continue;
                sourceMap[offers.size()] = source;
                offers.add(new MultiSourceDistributor.Offer(packet, packet, domain.routes.get(i), contacts, domain.priorities[i]));
            }
            long[] room = new long[remaining.length];
            for (int receiver = 0; receiver < room.length; receiver++) room[receiver] = Math.max(0, remaining[receiver]);
            var round = MultiSourceDistributor.allocate(offers, room, SourceOrder.create(offers.size(), random));
            for (int localSource = 0; localSource < offers.size(); localSource++) {
                var receipt = round.source(localSource); int source = sourceMap[localSource];
                debits[source] = Math.addExact(debits[source], receipt.sourceDebit());
                loss = Math.addExact(loss, receipt.dissipated());
                for (int receiver = 0; receiver < remaining.length; receiver++) {
                    long amount = receipt.credit(receiver);
                    credits[receiver] = Math.addExact(credits[receiver], amount);
                    remaining[receiver] = Math.subtractExact(remaining[receiver], amount);
                }
            }
        }
        return new Round(debits, credits, loss);
    }
}
