// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public final class FractionalDistributorContract {
    private static int assertions;
    private FractionalDistributorContract() { }
    private static void check(boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError("Check " + assertions);
    }
    private static RouteCosts route(long[] losses) {
        long[] copy = losses.clone();
        return new RouteCosts() {
            @Override public boolean reaches(int contact) { return copy[contact] >= 0; }
            @Override public long lossMilliTo(int contact) { return copy[contact]; }
        };
    }
    private static EnergyAmount value(String text) { return EnergyAmount.fromDouble(Double.parseDouble(text)); }
    public static void main(String[] args) throws Exception {
        var lines = Files.readAllLines(Path.of(args[0])); int observed = 0;
        for (String line : lines.subList(1, lines.size())) {
            String[] row = line.split("\t");
            EnergyAmount source = value(row[1]), sink = value(row[2]);
            long packet = Long.parseLong(row[3]), loss = Long.parseLong(row[5]);
            boolean partial = Boolean.parseBoolean(row[4]);
            var domains = List.of(new DomainDistributor.Domain(new int[]{0}, List.of(route(new long[]{loss * 1000})), new int[][]{{0}}));
            for (int tick = 0; tick < 12; tick++) {
                var result = FractionalDistributor.allocateTraced(List.of(new DomainDistributor.Source(source.whole(), packet, partial)),
                    List.of(source), domains, new int[]{0}, List.of(sink.roomBelow(40000)), List.of(), new Random(29));
                check(result.debit(0).equals(result.credit(0).add(result.dissipated())));
                check(result.debit(0).compareTo(source) <= 0);
                source = source.subtract(result.debit(0)); sink = sink.add(result.credit(0));
            }
            if (!source.equals(value(row[6])) || !sink.equals(value(row[7]))) throw new AssertionError("Observed scene " + row[0]);
            assertions += 2; observed++;
        }
        // Force the fractional path with integral inputs and compare complete
        // traces against the established integer planner, including shared
        // identities, repeated contacts, batch overshoot and domain shuffles.
        var random = new Random(0x29_16_02L);
        for (int example = 0; example < 2000; example++) {
            int[] contacts = {0, 1, 2};
            var quotes = new ArrayList<DomainDistributor.Source>();
            for (int i = 0; i < 3; i++) {
                boolean partial = random.nextBoolean();
                quotes.add(new DomainDistributor.Source(random.nextInt(257), 32, partial, partial ? 1 : 1 + random.nextInt(4)));
            }
            long[] room = {Math.max(0, 128 - quotes.get(0).reserve()), random.nextInt(129), random.nextInt(129)};
            var shared = List.of(new DomainDistributor.SharedStorage(0, 0, 128));
            var domains = new ArrayList<DomainDistributor.Domain>();
            for (int d = 0; d < 3; d++) {
                var routes = new ArrayList<RouteCosts>(); int[][] priorities = new int[4][];
                for (int i = 0; i < 4; i++) {
                    long[] losses = new long[3];
                    for (int j = 0; j < 3; j++) losses[j] = random.nextInt(6) == 0 ? -1 : random.nextInt(35) * 1000L;
                    routes.add(route(losses)); int first = random.nextInt(3); priorities[i] = new int[]{first, (first + 1) % 3, (first + 2) % 3};
                }
                domains.add(DomainDistributor.Domain.withSharedSourceContacts(new int[]{0, 1, 2, 0}, routes, priorities));
            }
            long seed = random.nextLong();
            var original = DomainDistributor.allocateTraced(quotes, domains, contacts, room, shared, new Random(seed));
            var exact = FractionalDistributor.allocateExact(quotes, quotes.stream().map(q -> EnergyAmount.of(q.reserve())).toList(),
                domains, contacts, Arrays.stream(room).mapToObj(EnergyAmount::of).toList(), shared, new Random(seed));
            for (int i = 0; i < 3; i++) {
                check(exact.debit(i).equals(EnergyAmount.of(original.debit(i))));
                check(exact.credit(i).equals(EnergyAmount.of(original.credit(i))));
            }
            check(exact.dissipated().equals(EnergyAmount.of(original.dissipated())));
            check(exact.deliveries().size() == original.deliveries().size());
            for (int i = 0; i < original.deliveries().size(); i++) {
                var a = original.deliveries().get(i); var b = exact.deliveries().get(i);
                check(a.domain() == b.domain() && a.entry() == b.entry() && a.source() == b.source() && a.receiver() == b.receiver());
                check(EnergyAmount.of(a.credit()).equals(b.credit()) && EnergyAmount.of(a.pathLoss()).equals(b.pathLoss()));
            }
            // Independent aggregate-unit conservation for genuinely fractional
            // amounts with the same multiple-domain/shared-identity topology.
            var fractional = quotes.stream().map(q -> new EnergyAmount(q.reserve(), random.nextLong() & (EnergyAmount.UNITS - 1))).toList();
            var demand = new ArrayList<EnergyAmount>(); demand.add(fractional.getFirst().roomBelow(128));
            for (int i = 1; i < 3; i++) demand.add(new EnergyAmount(room[i], random.nextLong() & (EnergyAmount.UNITS - 1)));
            var result = FractionalDistributor.allocateExact(quotes, fractional, domains, contacts, demand, shared, new Random(seed));
            var debited = java.math.BigInteger.ZERO; var credited = java.math.BigInteger.ZERO;
            for (int i = 0; i < 3; i++) {
                debited = debited.add(result.debit(i).units()); credited = credited.add(result.credit(i).units());
                check(result.debit(i).compareTo(fractional.get(i)) <= 0);
            }
            check(debited.equals(credited.add(result.dissipated().units())));
        }
        System.out.println("SCEX_FRACTIONAL_DISTRIBUTOR rows=" + observed + " differential_rounds=2000 fractional_rounds=2000 assertions=" + assertions + " PASS scope=accounting_not_game_integration");
    }
}
