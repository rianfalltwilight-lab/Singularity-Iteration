// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/** Validates allowed accounting transitions, explicitly not receiver scheduling. */
public final class PacketDistributorContract {
    private static long assertions;

    private PacketDistributorContract() { }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static long[] longs(String field) {
        return Arrays.stream(field.split(",")).mapToLong(Long::parseLong).toArray();
    }

    private static int[] ints(String field) {
        return Arrays.stream(field.split(",")).mapToInt(Integer::parseInt).toArray();
    }

    private static List<int[]> permutations(int count) {
        var results = new ArrayList<int[]>();
        int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        permute(order, 0, results);
        return results;
    }

    private static void permute(int[] order, int at, List<int[]> results) {
        if (at == order.length) {
            results.add(order.clone());
            return;
        }
        for (int i = at; i < order.length; i++) {
            int old = order[at]; order[at] = order[i]; order[i] = old;
            permute(order, at + 1, results);
            old = order[at]; order[at] = order[i]; order[i] = old;
        }
    }

    private static String key(long debit, long[] credits) {
        return debit + ":" + Arrays.toString(credits);
    }

    private static void accounting(long reserve, long packet, TreeTopology.Routes routes,
                                   int[] contacts, long[] room, PacketDistributor.Allocation result) {
        require(result.sourceDebit() >= 0 && result.sourceDebit() <= Math.min(reserve, packet), "Debit bounds");
        require(reserve >= packet || result.sourceDebit() == 0, "Complete source packet required");
        long credit = 0;
        long loss = 0;
        for (int i = 0; i < room.length; i++) {
            require(result.credit(i) >= 0 && result.credit(i) <= room[i], "Receiver bounds");
            credit = Math.addExact(credit, result.credit(i));
            if (result.credit(i) > 0) {
                loss = Math.addExact(loss, routes.wholeLossTo(contacts[i]));
            }
            if (reserve >= packet && result.credit(i) < room[i]) {
                require(packet - result.sourceDebit() <= routes.wholeLossTo(contacts[i]), "No deliverable budget left");
            }
        }
        require(credit == result.sourceDebit() - result.dissipated(), "Energy conservation");
        require(loss == result.dissipated(), "Only positive deliveries incur path loss");
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

    public static void main(String[] args) throws Exception {
        var rows = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        require(rows.getFirst().startsWith("case\treserve\tpacket\t"), "Fixture header");
        var labels = new HashSet<String>();
        int transitions = 0;
        int uniqueTransitions = 0;
        for (String row : rows.subList(1, rows.size())) {
            String[] f = row.split("\t", -1);
            require(f.length == 12 && labels.add(f[0]), "Unique well-formed case");
            long reserve = Long.parseLong(f[1]);
            long packet = Long.parseLong(f[2]);
            long[] losses = longs(f[3]);
            int[][] edges = f[4].equals("-") ? new int[0][] : Arrays.stream(f[4].split(";"))
                    .map(edge -> Arrays.stream(edge.split(":")).mapToInt(Integer::parseInt).toArray()).toArray(int[][]::new);
            var routes = new TreeTopology(losses, edges).routesFrom(Integer.parseInt(f[5]));
            int[] contacts = ints(f[6]);
            long[] initialRoom = longs(f[7]);
            long[] room = initialRoom.clone();
            long[] pathLoss = longs(f[8]);
            require(contacts.length >= 2 && contacts.length <= 3, "Bounded exhaustive permutations");
            for (int i = 0; i < contacts.length; i++) {
                require(routes.lossMilliTo(contacts[i]) == pathLoss[i], "Observed layout path loss");
            }
            var orders = permutations(contacts.length);
            long totalDebit = 0;
            long[] totalCredits = new long[contacts.length];
            for (String event : f[11].equals("-") ? new String[0] : f[11].split("\\|")) {
                String[] parts = event.split(":");
                long observedDebit = Long.parseLong(parts[0]);
                long[] observedCredits = longs(parts[1]);
                var possible = new HashSet<String>();
                for (int[] order : orders) {
                    var prediction = PacketDistributor.allocate(reserve, packet, routes, contacts, room, order);
                    accounting(reserve, packet, routes, contacts, room, prediction);
                    possible.add(key(prediction.sourceDebit(), prediction.credits()));
                }
                // Enumerate every order before examining which observed result
                // matches. No order is inferred from an oracle and fed as input.
                require(possible.contains(key(observedDebit, observedCredits)), f[0] + " transition outside permitted set: " + event);
                transitions++;
                if (possible.size() == 1) {
                    uniqueTransitions++;
                }
                reserve -= observedDebit;
                totalDebit += observedDebit;
                for (int i = 0; i < contacts.length; i++) {
                    room[i] -= observedCredits[i];
                    totalCredits[i] += observedCredits[i];
                }
            }
            require(totalDebit == Long.parseLong(f[9]), "Observed final debit");
            require(Arrays.equals(totalCredits, longs(f[10])), "Observed final credits");
            for (int[] order : orders) {
                require(PacketDistributor.allocate(reserve, packet, routes, contacts, room, order).sourceDebit() == 0,
                        f[0] + " unexplained premature stop");
            }
        }
        require(labels.size() == 70 && transitions == 88, "Expected frozen evidence count");

        var routes = new TreeTopology(new long[]{200, 200, 800, 400, 25},
                new int[][]{{0, 1}, {0, 2}, {0, 3}, {0, 4}}).routesFrom(0);
        int[] contacts = {1, 2, 3, 4};
        var random = new Random(0x534345587L);
        for (int sample = 0; sample < 20000; sample++) {
            long reserve = random.nextInt(100000);
            long packet = 1 + random.nextInt(2049);
            long[] room = {random.nextInt(500), random.nextInt(500), random.nextInt(500), random.nextInt(500)};
            long[] original = room.clone();
            int[] priority = {0, 1, 2, 3};
            for (int i = priority.length - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int old = priority[i]; priority[i] = priority[j]; priority[j] = old;
            }
            var result = PacketDistributor.allocate(reserve, packet, routes, contacts, room, priority);
            accounting(reserve, packet, routes, contacts, room, result);
            require(Arrays.equals(room, original), "Input balances unchanged");
            long before = result.credit(0);
            long[] external = result.credits(); external[0] = -1;
            require(result.credit(0) == before, "Returned vector cannot mutate allocation");
        }
        var huge = PacketDistributor.allocate(Long.MAX_VALUE, Long.MAX_VALUE, routes,
                new int[]{2, 1}, new long[]{Long.MAX_VALUE - 2, Long.MAX_VALUE}, new int[]{0, 1});
        require(huge.sourceDebit() == Long.MAX_VALUE && huge.credit(0) == Long.MAX_VALUE - 2
                && huge.credit(1) == 1 && huge.dissipated() == 1, "Maximum long budget without overflow");
        require(PacketDistributor.allocate(32, 32, routes, new int[0], new long[0], new int[0]).sourceDebit() == 0,
                "No receivers means no debit");
        rejects(IllegalArgumentException.class, () -> PacketDistributor.allocate(32, 32, routes,
                new int[]{1, 2}, new long[]{1, 1}, new int[]{0, 0}));
        rejects(IllegalArgumentException.class, () -> PacketDistributor.allocate(32, 32, routes,
                new int[]{1}, new long[]{-1}, new int[]{0}));
        rejects(IllegalArgumentException.class, () -> PacketDistributor.allocate(32, 0, routes,
                new int[]{1}, new long[]{1}, new int[]{0}));
        rejects(IllegalArgumentException.class, () -> PacketDistributor.allocate(32, 32, routes,
                new int[]{5}, new long[]{1}, new int[]{0}));
        require(PacketDistributor.allocate(32, 1, routes, new int[]{2}, new long[]{1}, new int[]{0}).sourceDebit() == 0,
                "Generator offer equal to path loss is retained without a debit");
        var mixed = PacketDistributor.allocate(1, 1, routes, new int[]{2, 1}, new long[]{100, 100}, new int[]{0, 1});
        require(mixed.credit(0) == 0 && mixed.credit(1) == 1 && mixed.sourceDebit() == 1 && mixed.dissipated() == 0,
                "Exhausted path does not block another deliverable receiver");
        var expensive = new TreeTopology(new long[]{5000}, new int[0][]).routesFrom(0);
        require(PacketDistributor.allocate(1, 1, expensive, new int[]{0}, new long[]{100}, new int[]{0}).sourceDebit() == 0,
                "Offer smaller than path loss cannot debit energy");
        rejects(IllegalArgumentException.class, () -> PacketDistributor.allocate(32, 32, routes,
                new int[]{1}, new long[]{1}, new int[0]));
        int generatorCases = 0;
        var generatorRows = Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8);
        require(generatorRows.size() == 13 && generatorRows.getFirst().equals("case\treserve\tloss_milli\tfinal_source\tfinal_receiver"),
                "Complete frozen generator fixture");
        for (String row : generatorRows.subList(1, generatorRows.size())) {
            String[] f = row.split("\t");
            long reserve = Long.parseLong(f[1]);
            var path = new TreeTopology(new long[]{Long.parseLong(f[2])}, new int[0][]).routesFrom(0);
            long credited = 0;
            for (int tick = 0; tick < 8 && reserve > 0; tick++) {
                var result = MultiSourceDistributor.allocate(List.of(new MultiSourceDistributor.Offer(reserve, Math.min(32, reserve),
                        path, new int[]{0}, new int[]{0})), new long[]{40000 - credited}, new int[]{0}).source(0);
                require(result.sourceDebit() == result.credit(0) + result.dissipated(), "Generator packet receipt conservation");
                reserve -= result.sourceDebit(); credited += result.credit(0);
            }
            require(reserve == Long.parseLong(f[3]) && credited == Long.parseLong(f[4]), "Observed generator residual offer: " + f[0]);
            generatorCases++;
        }
        require(generatorCases == 12, "Nonempty frozen generator packet evidence");
        System.out.println("SCEX_DISTRIBUTOR_CONTRACT cases=" + labels.size() + " generator_cases=" + generatorCases + " transitions=" + transitions
                + " unique_outcome_transitions=" + uniqueTransitions + " order_dependent_transitions=" + (transitions - uniqueTransitions)
                + " randomized_accounts=20000 assertions=" + assertions + " PASS order_selection_NOT_verified");
    }
}
