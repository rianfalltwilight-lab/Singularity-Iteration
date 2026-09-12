// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/** Observable vector checks; no hidden source-to-receiver attribution is assumed. */
public final class MultiSourceContract {
    private static long assertions;
    private MultiSourceContract() { }
    private static void require(boolean value, String message) {
        assertions++;
        if (!value) { throw new AssertionError(message); }
    }
    private static long[] longs(String s) { return Arrays.stream(s.split(",")).mapToLong(Long::parseLong).toArray(); }
    private static int[] ints(String s) { return Arrays.stream(s.split(",")).mapToInt(Integer::parseInt).toArray(); }
    private static List<int[]> orders(int n) {
        if (n == 1) { return List.of(new int[]{0}); }
        if (n == 2) { return List.of(new int[]{0, 1}, new int[]{1, 0}); }
        throw new IllegalArgumentException("Exhaustive oracle limited to one/two endpoints");
    }
    private static String key(long[] debits, long[] credits) { return Arrays.toString(debits) + ":" + Arrays.toString(credits); }

    private static String key(MultiSourceDistributor.Round round, int receivers) {
        long[] debits = new long[round.sourceCount()];
        long[] credits = new long[receivers];
        for (int s = 0; s < debits.length; s++) {
            debits[s] = round.source(s).sourceDebit();
            for (int r = 0; r < receivers; r++) { credits[r] = Math.addExact(credits[r], round.source(s).credit(r)); }
        }
        return key(debits, credits);
    }

    private static void accounting(long[] reserves, long[] packets, RouteCosts[] routes, int[] contacts,
                                   long[] quoted, MultiSourceDistributor.Round round) {
        BigInteger[] totalCredits = new BigInteger[quoted.length]; Arrays.fill(totalCredits, BigInteger.ZERO);
        for (int source = 0; source < reserves.length; source++) {
            var receipt = round.source(source);
            require(receipt.sourceDebit() >= 0 && receipt.sourceDebit() <= Math.min(reserves[source], packets[source]), "Source debit bounds");
            require(reserves[source] >= packets[source] || receipt.sourceDebit() == 0, "Complete packet threshold");
            long credit = 0; long loss = 0;
            for (int receiver = 0; receiver < quoted.length; receiver++) {
                long amount = receipt.credit(receiver);
                require(amount >= 0 && amount <= quoted[receiver], "Per-source quote bound");
                if (amount > 0) {
                    require(routes[source].reaches(contacts[receiver]), "No unreachable delivery");
                    loss = Math.addExact(loss, routes[source].wholeLossTo(contacts[receiver]));
                }
                credit = Math.addExact(credit, amount);
                totalCredits[receiver] = totalCredits[receiver].add(BigInteger.valueOf(amount));
            }
            require(credit == receipt.sourceDebit() - receipt.dissipated(), "Per-source conservation");
            require(loss == receipt.dissipated(), "Positive-delivery path losses");
        }
        for (int r = 0; r < quoted.length; r++) {
            BigInteger expectedRemaining = BigInteger.valueOf(quoted[r]).subtract(totalCredits[r]);
            require(expectedRemaining.equals(BigInteger.valueOf(round.remainingRoom(r))), "Receiver accounting including overshoot");
            BigInteger bound = quoted[r] == 0 ? BigInteger.ZERO : BigInteger.valueOf(quoted[r]).multiply(BigInteger.TWO).subtract(BigInteger.ONE);
            require(totalCredits[r].compareTo(bound) <= 0, "Filled receiver is skipped after final overshoot");
        }
    }

    private static HashSet<String> possibilities(long[] reserves, long[] packets, RouteCosts[] routes,
                                                 int[] contacts, long[] room) {
        var results = new HashSet<String>();
        var receiverOrders = orders(room.length);
        int combinations = 1;
        for (int ignored = 0; ignored < reserves.length; ignored++) { combinations *= receiverOrders.size(); }
        for (int[] sourceOrder : orders(reserves.length)) {
            for (int choice = 0; choice < combinations; choice++) {
                int code = choice;
                var offers = new ArrayList<MultiSourceDistributor.Offer>();
                for (int s = 0; s < reserves.length; s++) {
                    int[] order = receiverOrders.get(code % receiverOrders.size()); code /= receiverOrders.size();
                    offers.add(new MultiSourceDistributor.Offer(reserves[s], packets[s], routes[s], contacts, order));
                }
                var result = MultiSourceDistributor.allocate(offers, room, sourceOrder);
                accounting(reserves, packets, routes, contacts, room, result);
                results.add(key(result, room.length));
            }
        }
        return results;
    }

    private static void rejects(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException e) { assertions++; return; }
        throw new AssertionError("Expected invalid input rejection");
    }

    public static void main(String[] args) throws Exception {
        var lines = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        require(lines.getFirst().startsWith("case\treserves\tpackets\t"), "Fixture header");
        int transitions = 0; int unique = 0; int overshootTransitions = 0;
        var labels = new HashSet<String>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\t", -1);
            require(f.length == 11 && labels.add(f[0]), "Unique fixture");
            long[] initialReserves = longs(f[1]); long[] reserves = initialReserves.clone(); long[] packets = longs(f[2]);
            int[][] links = f[4].equals("-") ? new int[0][] : Arrays.stream(f[4].split(";"))
                    .map(pair -> Arrays.stream(pair.split(":")).mapToInt(Integer::parseInt).toArray()).toArray(int[][]::new);
            var graph = new ConductorGraph(longs(f[3]), links);
            int[] sourceContacts = ints(f[5]); int[] contacts = ints(f[6]);
            RouteCosts[] routes = new RouteCosts[sourceContacts.length];
            for (int i = 0; i < routes.length; i++) { routes[i] = graph.routesFrom(sourceContacts[i]); }
            long[] initialRoom = longs(f[7]); long[] remaining = initialRoom.clone();
            long[] totalCredits = new long[remaining.length];
            for (String event : f[10].equals("-") ? new String[0] : f[10].split("\\|")) {
                long[] quote = Arrays.stream(remaining).map(x -> Math.max(0, x)).toArray();
                // Every model order is evaluated before consulting this event's
                // observed output, and only observable vectors are compared.
                var possible = possibilities(reserves, packets, routes, contacts, quote);
                String[] parts = event.split(":"); long[] debit = longs(parts[0]); long[] credit = longs(parts[1]);
                require(possible.contains(key(debit, credit)), f[0] + " unmatched transition " + event + " options=" + possible);
                transitions++; if (possible.size() == 1) { unique++; }
                boolean overshot = false;
                for (int s = 0; s < reserves.length; s++) { reserves[s] -= debit[s]; }
                for (int r = 0; r < remaining.length; r++) {
                    overshot |= credit[r] > quote[r]; remaining[r] -= credit[r]; totalCredits[r] += credit[r];
                }
                if (overshot) { overshootTransitions++; }
            }
            long[] totalDebits = new long[reserves.length];
            for (int i = 0; i < reserves.length; i++) { totalDebits[i] = initialReserves[i] - reserves[i]; }
            require(Arrays.equals(totalDebits, longs(f[8])), "Final source vector");
            require(Arrays.equals(totalCredits, longs(f[9])), "Final receiver vector");
            var idle = possibilities(reserves, packets, routes, contacts, Arrays.stream(remaining).map(x -> Math.max(0, x)).toArray());
            require(idle.size() == 1 && idle.contains(key(new long[reserves.length], new long[remaining.length])), "Steady-state stop");
        }
        require(labels.size() == 54 && transitions == 318, "Frozen R8 observation count");

        int[] contacts = {2, 4};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 5}, {5, 6}};
        long[] copper = new long[7]; Arrays.fill(copper, 200);
        var lineGraph = new ConductorGraph(copper, edges);
        RouteCosts[] routes = {lineGraph.routesFrom(0), lineGraph.routesFrom(6)};
        var regression = MultiSourceDistributor.allocate(List.of(
                new MultiSourceDistributor.Offer(64, 32, routes[0], contacts, new int[]{0, 1}),
                new MultiSourceDistributor.Offer(64, 32, routes[1], contacts, new int[]{0, 1})), new long[]{16, 16}, new int[]{1, 0});
        require(regression.source(0).sourceDebit() == 17 && regression.source(1).sourceDebit() == 32
                && regression.remainingRoom(0) == 0 && regression.remainingRoom(1) == -15, "Observed overshoot regression");
        var random = new Random(0x5343455888L);
        for (int sample = 0; sample < 20000; sample++) {
            long[] reserves = {random.nextInt(500), random.nextInt(500)};
            long[] packets = {32, 128}; long[] room = {random.nextInt(100), random.nextInt(100)}; long[] before = room.clone();
            int[] sourceOrder = random.nextBoolean() ? new int[]{0, 1} : new int[]{1, 0};
            var offers = new ArrayList<MultiSourceDistributor.Offer>();
            for (int s = 0; s < 2; s++) {
                int[] copiedContacts = contacts.clone(); int[] order = random.nextBoolean() ? new int[]{0, 1} : new int[]{1, 0};
                offers.add(new MultiSourceDistributor.Offer(reserves[s], packets[s], routes[s], copiedContacts, order));
                Arrays.fill(copiedContacts, -1); Arrays.fill(order, -1);
            }
            var result = MultiSourceDistributor.allocate(offers, room, sourceOrder);
            accounting(reserves, packets, routes, contacts, room, result);
            require(Arrays.equals(room, before), "Input capacities unchanged");
            long original = result.remainingRoom(0); long[] escaped = result.remainingRoom(); escaped[0] = Long.MIN_VALUE;
            require(result.remainingRoom(0) == original, "Immutable result");
        }
        var direct = new ConductorGraph(new long[]{0}, new int[0][]).routesFrom(0);
        var huge = MultiSourceDistributor.allocate(List.of(
                new MultiSourceDistributor.Offer(Long.MAX_VALUE, Long.MAX_VALUE - 1, direct, new int[]{0}, new int[]{0}),
                new MultiSourceDistributor.Offer(Long.MAX_VALUE, Long.MAX_VALUE, direct, new int[]{0}, new int[]{0})),
                new long[]{Long.MAX_VALUE}, new int[]{0, 1});
        require(huge.remainingRoom(0) == 1 - Long.MAX_VALUE, "Aggregate energy beyond long remains representable as vectors");
        require(MultiSourceDistributor.allocate(List.of(), new long[]{1}, new int[0]).remainingRoom(0) == 1, "Empty source set");
        rejects(() -> MultiSourceDistributor.allocate(List.of(), new long[]{-1}, new int[0]));
        rejects(() -> MultiSourceDistributor.allocate(List.of(), new long[]{1}, new int[]{0}));
        rejects(() -> new MultiSourceDistributor.Offer(-1, 32, direct, new int[]{0}, new int[]{0}));
        var one = new MultiSourceDistributor.Offer(32, 32, direct, new int[]{0}, new int[]{0});
        rejects(() -> MultiSourceDistributor.allocate(List.of(one, one), new long[]{1}, new int[]{0, 0}));
        System.out.println("SCEX_MULTISOURCE_CONTRACT cases=" + labels.size() + " transitions=" + transitions
                + " unique_outcome_transitions=" + unique + " order_dependent_transitions=" + (transitions - unique)
                + " overshoot_transitions=" + overshootTransitions + " randomized_accounts=20000 assertions=" + assertions
                + " PASS scheduling_NOT_verified");
    }
}
