// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/** Frozen public source and receiver observations through real accounting entries. */
public final class PriorityDomainContract {
    private static int assertions;
    private PriorityDomainContract() { }
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    private static int[] ints(String text) { return Arrays.stream(text.split(",")).mapToInt(Integer::parseInt).toArray(); }
    private static long[] longs(String text) { return Arrays.stream(text.split(",")).mapToLong(Long::parseLong).toArray(); }
    private static final class Choices implements RandomGenerator {
        private final int[] choices; private int cursor;
        Choices(int... choices) { this.choices = choices.clone(); }
        @Override public long nextLong() { throw new AssertionError("Only declared bounded choices expected"); }
        @Override public int nextInt(int bound) {
            if (cursor == choices.length) throw new AssertionError("Unexpected random choice");
            int value = choices[cursor++];
            if (value < 0 || value >= bound) throw new AssertionError("Choice outside declared population");
            return value;
        }
        void exhausted() { check(cursor == choices.length, "Every declared bounded choice used"); }
    }
    private static RouteCosts costs(int receivers, long loss) {
        return new RouteCosts() {
            @Override public boolean reaches(int contact) { return contact >= 0 && contact < receivers; }
            @Override public long lossMilliTo(int contact) { return loss; }
        };
    }
    private static List<int[]> permutationsChoices(int count) {
        if (count <= 1) return List.of(new int[0]);
        var result = new ArrayList<int[]>();
        for (int first = 0; first < count; first++) {
            for (int[] tail : permutationsChoices(count - 1)) {
                int[] row = new int[tail.length + 1]; row[0] = first;
                System.arraycopy(tail, 0, row, 1, tail.length); result.add(row);
            }
        }
        return result;
    }
    private static String key(long[] debits, long credit) { return Arrays.toString(debits) + ":" + credit; }
    private static Map<String, Integer> sourcePossibilities(String[] f) {
        int[] registration = ints(f[2]); long[] reserves = longs(f[3]); long room = Long.parseLong(f[4]);
        long loss = Long.parseLong(f[5]); boolean separate = f[6].equals("1"); int count = reserves.length;
        var quotes = new ArrayList<DomainDistributor.Source>();
        for (long reserve : reserves) quotes.add(new DomainDistributor.Source(reserve, 32, true));
        var route = costs(1, loss); var domains = new ArrayList<DomainDistributor.Domain>();
        if (separate) {
            for (int source : registration)
                domains.add(new DomainDistributor.Domain(new int[]{source}, List.of(route), new int[][]{{0}}));
        } else {
            var routes = new ArrayList<RouteCosts>(); int[][] priorities = new int[count][];
            for (int i = 0; i < count; i++) { routes.add(route); priorities[i] = new int[]{0}; }
            domains.add(new DomainDistributor.Domain(registration, routes, priorities));
        }
        var choices = new ArrayList<int[]>();
        int offering = (int) Arrays.stream(reserves).filter(value -> value > 0).count();
        if (separate) choices.addAll(permutationsChoices(count));
        else if (offering <= 1) choices.add(new int[0]);
        else for (int start = 0; start < offering; start++) choices.add(new int[]{start});
        var results = new HashMap<String, Integer>();
        for (int[] choice : choices) {
            var random = new Choices(choice);
            var round = DomainDistributor.allocate(quotes, domains, new int[]{0}, new long[]{room}, random);
            random.exhausted(); long[] debits = new long[count]; long total = 0;
            for (int source = 0; source < count; source++) {
                debits[source] = round.debit(source); total += debits[source];
                check(debits[source] >= 0 && debits[source] <= reserves[source], "Source reserve bound");
            }
            check(total == round.credit(0) + round.dissipated(), "Actual domain receipt conserves energy");
            results.merge(key(debits, round.credit(0)), 1, Integer::sum);
        }
        return results;
    }
    private static void distribution(Map<String, Integer> observed, Map<String, Integer> weights, String label) {
        int samples = observed.values().stream().mapToInt(Integer::intValue).sum();
        int outcomes = weights.values().stream().mapToInt(Integer::intValue).sum();
        for (var entry : weights.entrySet()) {
            double p = (double) entry.getValue() / outcomes;
            double tolerance = Math.max(3, 6 * Math.sqrt(samples * p * (1 - p)));
            check(Math.abs(observed.getOrDefault(entry.getKey(), 0) - samples * p) <= tolerance,
                "Finite observed distribution outside predeclared 6-sigma guard: " + label);
        }
    }
    private static void sources(Path path) throws Exception {
        var lines = Files.readAllLines(path); check(lines.size() == 14521, "Exact source observation count");
        var models = new HashMap<String, Map<String, Integer>>();
        var observed = new HashMap<String, Map<String, Integer>>();
        var phases = new HashMap<String, Map<String, Integer>>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\t"); check(f.length == 9, "Source fixture columns");
            var possible = models.computeIfAbsent(f[0], ignored -> sourcePossibilities(f));
            String key = key(longs(f[7]), Long.parseLong(f[8]));
            check(possible.containsKey(key), "Observed source vector rejected: " + f[0] + " " + key);
            observed.computeIfAbsent(f[0], ignored -> new HashMap<>()).merge(key, 1, Integer::sum);
            String phase = f[0] + "/" + (Long.parseLong(f[1]) % 4);
            phases.computeIfAbsent(phase, ignored -> new HashMap<>()).merge(key, 1, Integer::sum);
        }
        check(models.size() == 111, "Every source topology represented");
        for (var entry : observed.entrySet()) distribution(entry.getValue(), models.get(entry.getKey()), entry.getKey());
        for (var entry : phases.entrySet()) {
            String label = entry.getKey().substring(0, entry.getKey().lastIndexOf('/'));
            distribution(entry.getValue(), models.get(label), entry.getKey());
        }
    }
    private static void receivers(Path path) throws Exception {
        var lines = Files.readAllLines(path); check(lines.size() == 1801, "Exact full receiver control count");
        var models = new HashMap<String, Map<String, Integer>>();
        var observed = new HashMap<String, Map<String, Integer>>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\t"); int[] registration = ints(f[2]); long[] rooms = longs(f[3]);
            long time = Long.parseLong(f[1]); String label = f[0] + "/" + (time % 4 == 0 ? "fixed" : "random");
            var possible = models.computeIfAbsent(label, ignored -> {
                var counts = new HashMap<String, Integer>(); boolean[] connected = new boolean[rooms.length];
                Arrays.fill(connected, true); int[] contacts = new int[rooms.length];
                for (int i = 0; i < contacts.length; i++) contacts[i] = i;
                int choices = time % 4 == 0 ? 1 : rooms.length;
                for (int start = 0; start < choices; start++) {
                    var random = time % 4 == 0 ? new Choices() : new Choices(start);
                    int[] order = ReceiverOrder.create(registration, connected, time, random); random.exhausted();
                    var receipt = PacketDistributor.allocate(32, 32, costs(rooms.length, 0), contacts, rooms, order);
                    String value = Arrays.toString(receipt.credits()) + ":" + (32 - receipt.sourceDebit());
                    counts.merge(value, 1, Integer::sum);
                }
                return counts;
            });
            String key = Arrays.toString(longs(f[5])) + ":" + Long.parseLong(f[4]);
            check(possible.containsKey(key), "Observed full receiver vector rejected");
            observed.computeIfAbsent(label, ignored -> new HashMap<>()).merge(key, 1, Integer::sum);
        }
        check(models.size() == 18, "Nine receiver networks and both time phases");
        for (var entry : observed.entrySet()) distribution(entry.getValue(), models.get(entry.getKey()), entry.getKey());
    }
    private static void boundaries() {
        var noChoices = new Choices();
        check(SourceOrder.create(0, noChoices).length == 0, "Empty source domain");
        check(Arrays.equals(SourceOrder.create(1, noChoices), new int[]{0}), "Singleton source domain");
        noChoices.exhausted();
        try { SourceOrder.create(-1, noChoices); throw new AssertionError("Negative count accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
        var graph = new ConductorGraph(new long[7], new int[][]{{0,1},{1,2},{2,0},{3,4},{4,5}});
        for (int i = 0; i < 7; i++) for (int j = 0; j < 7; j++) {
            boolean same = i / 3 == j / 3;
            check((graph.componentOf(i) == graph.componentOf(j)) == same, "Physical component membership");
            check(graph.routesFrom(i).reaches(j) == same, "Components agree with independent path reachability");
        }
        try (var registry = new ConductorRegistry(4, 1)) {
            var a = new ConductorRegistry.Position(0,0,0); var b = new ConductorRegistry.Position(2,0,0);
            registry.put(a, 0); registry.put(b, 0); var old = registry.snapshot();
            check(old.componentOf(a) != old.componentOf(b), "Separate registered conductors");
            registry.put(new ConductorRegistry.Position(1,0,0), 0);
            try { old.componentOf(a); throw new AssertionError("Stale component lease accepted"); }
            catch (IllegalStateException expected) { assertions++; }
            var current = registry.snapshot(); check(current.componentOf(a) == current.componentOf(b), "Bridge merges physical domains");
        }
        var huge = new MultiSourceDistributor.Offer(Long.MAX_VALUE, Long.MAX_VALUE - 1, costs(1,0), new int[]{0}, new int[]{0});
        try {
            MultiSourceDistributor.allocate(List.of(huge,huge,huge), new long[]{Long.MAX_VALUE}, new int[]{0,1,2});
            throw new AssertionError("Unrepresentable aggregate accepted");
        } catch (ArithmeticException expected) { assertions++; }
    }
    public static void main(String[] args) throws Exception {
        sources(Path.of(args[0])); receivers(Path.of(args[1])); boundaries();
        System.out.printf("SCEX_PRIORITY_DOMAIN_CONTRACT source_rows=14520 receiver_rows=1800 assertions=%d PASS mixed_shared_source_domains_and_PRNG_NOT_verified%n", assertions);
    }
}
