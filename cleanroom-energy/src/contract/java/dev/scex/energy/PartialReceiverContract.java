// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.random.RandomGenerator;

/** Real distributor entry tested against binary-observed partial credit vectors. */
public final class PartialReceiverContract {
    private static int assertions;
    private PartialReceiverContract() { }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static int[] vector(String text) { return Arrays.stream(text.split(",")).mapToInt(Integer::parseInt).toArray(); }
    private static final class Choice implements RandomGenerator {
        private final int start;
        Choice(int start) { this.start = start; }
        @Override public long nextLong() { throw new AssertionError("Only bounded starting selection expected"); }
        @Override public int nextInt(int bound) {
            if (start < 0 || start >= bound) throw new AssertionError("Invalid controlled starting choice");
            return start;
        }
    }
    public static void main(String[] args) throws Exception {
        var lines = Files.readAllLines(Path.of(args[0])); check(lines.size() == 3601, "Exact observed vector coverage");
        int rows = 0, fixed = 0, underfilled = 0;
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split("\t"); long time = Long.parseLong(fields[1]);
            int[] registration = vector(fields[2]); int n = registration.length; long room = Long.parseLong(fields[3]);
            long remaining = Long.parseLong(fields[4]); int[] expected = vector(fields[5]);
            check((n >= 2 && n <= 4) && (room == 11 || room == 31) && expected.length == n,
                "Observed fixture dimensions");
            boolean[] eligible = new boolean[n]; Arrays.fill(eligible, true);
            int[] contacts = new int[n]; for (int i = 0; i < n; i++) contacts[i] = i;
            long[] rooms = new long[n]; Arrays.fill(rooms, room);
            RouteCosts routes = new RouteCosts() {
                @Override public boolean reaches(int contact) { return contact >= 0 && contact < n; }
                @Override public long lossMilliTo(int contact) { return 0; }
            };
            boolean matched = false;
            int choices = time % 4 == 0 ? 1 : n;
            for (int start = 0; start < choices; start++) {
                int[] order = ReceiverOrder.create(registration, eligible, time, new Choice(start));
                var receipt = PacketDistributor.allocate(32, 32, routes, contacts, rooms, order);
                boolean equal = receipt.sourceDebit() == 32 - remaining && receipt.dissipated() == 0;
                for (int i = 0; i < n; i++) equal &= receipt.credit(i) == expected[i];
                matched |= equal;
            }
            check(matched, "Independent priority and actual packet allocator cannot produce observed vector");
            if (time % 4 == 0) fixed++;
            if (remaining > 0) underfilled++;
            rows++;
        }
        check(rows == 3600 && fixed == 900 && underfilled == 600, "Both room controls and fixed phase covered");
        System.out.printf("SCEX_PARTIAL_ORDER_CONTRACT rows=%d fixed_phase=%d assertions=%d PASS complex_topology_and_multisource_NOT_verified%n", rows, fixed, assertions);
    }
}
