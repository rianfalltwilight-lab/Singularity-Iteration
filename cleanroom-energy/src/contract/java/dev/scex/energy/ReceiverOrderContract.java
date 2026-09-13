// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

public final class ReceiverOrderContract {
    private static int assertions;
    private ReceiverOrderContract() { }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static int[] vector(String text) {
        return Arrays.stream(text.split(",")).mapToInt(Integer::parseInt).toArray();
    }
    private static void rejects(Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException | NullPointerException expected) { rejected = true; }
        check(rejected, "Invalid order accepted");
    }
    public static void main(String[] args) throws Exception {
        var generator = new SplittableRandom(140214L);
        Map<String, int[]> observed = new HashMap<>(), modeled = new HashMap<>();
        int rows = 0, fixed = 0;
        var lines = Files.readAllLines(Path.of(args[0]));
        check(lines.size() == 9001, "Exact frozen reference coverage");
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split("\t");
            long time = Long.parseLong(fields[1]); int[] registration = vector(fields[2]);
            int winner = Integer.parseInt(fields[3]); int[] credits = vector(fields[5]);
            boolean[] eligible = new boolean[registration.length]; Arrays.fill(eligible, true);
            int[] order = ReceiverOrder.create(registration, eligible, time, generator);
            check(Integer.parseInt(fields[4]) == 32 && credits[winner] == 32 && Arrays.stream(credits).sum() == 32,
                "Observed packet conservation");
            if (time % 4 == 0) {
                check(winner == registration[0] && order[0] == winner, "Observed fixed world-time phase"); fixed++;
            } else {
                observed.computeIfAbsent(fields[0], ignored -> new int[registration.length])[winner]++;
                modeled.computeIfAbsent(fields[0], ignored -> new int[registration.length])[order[0]]++;
            }
            rows++;
        }
        check(rows == 9000 && fixed == 2250 && observed.size() == 9, "Complete reference windows");
        for (String name : observed.keySet()) {
            int[] a = observed.get(name), b = modeled.get(name);
            check(Arrays.stream(a).sum() == 750 && Arrays.stream(b).sum() == 750, "Three random phases per four ticks");
            // A deliberately broad six-standard-deviation consistency check;
            // deterministic fixed-phase checks above carry the exact behavior.
            double expected = 750.0 / a.length;
            double tolerance = 6 * Math.sqrt(expected * (1 - 1.0 / a.length));
            for (int i = 0; i < a.length; i++) {
                check(Math.abs(a[i] - expected) <= tolerance, "Reference eligible marginal outside declared model");
                check(Math.abs(b[i] - expected) <= tolerance, "Independent eligible marginal outside declared model");
            }
        }
        var smallRandom = new SplittableRandom(29); var paddedRandom = new SplittableRandom(29);
        int[] small = {1, 0}; boolean[] both = {true, true};
        int[] padded = {4, 1, 3, 0, 2}; boolean[] active = {false, true, false, true, false};
        int[] original = padded.clone(); boolean[] originalActive = active.clone();
        for (int time = 0; time < 1000; time++) {
            int[] a = ReceiverOrder.create(small, both, time, smallRandom);
            int[] b = ReceiverOrder.create(padded, active, time, paddedRandom);
            check((a[0] == 1 ? 1 : 3) == b[0] && (a[1] == 1 ? 1 : 3) == b[1], "Unreachable decoys changed eligible sequence");
            check(Arrays.equals(Arrays.copyOfRange(b, 2, 5), new int[]{4, 0, 2}), "Inactive tail changed");
        }
        check(Arrays.equals(padded, original) && Arrays.equals(active, originalActive), "Caller vectors mutated");
        var emptyRandom = new SplittableRandom(7); var untouched = new SplittableRandom(7);
        check(ReceiverOrder.create(new int[0], new boolean[0], 1, emptyRandom).length == 0, "Empty registry");
        check(Arrays.equals(ReceiverOrder.create(new int[]{1, 0}, new boolean[]{false, false}, 1, emptyRandom), new int[]{1, 0}), "No eligible receivers");
        check(Arrays.equals(ReceiverOrder.create(new int[]{0, 1}, new boolean[]{false, true}, 1, emptyRandom), new int[]{1, 0}), "Full receiver skipped");
        check(emptyRandom.nextLong() == untouched.nextLong(), "Zero/one eligible receiver consumed randomness");
        rejects(() -> ReceiverOrder.create(new int[]{0, 0}, both, 0, generator));
        rejects(() -> ReceiverOrder.create(new int[]{0, 2}, both, 0, generator));
        rejects(() -> ReceiverOrder.create(new int[]{0, -1}, both, 0, generator));
        rejects(() -> ReceiverOrder.create(new int[]{0}, both, 0, generator));
        rejects(() -> ReceiverOrder.create(small, both, -1, generator));
        rejects(() -> ReceiverOrder.create(null, both, 0, generator));
        rejects(() -> ReceiverOrder.create(small, null, 0, generator));
        rejects(() -> ReceiverOrder.create(small, both, 0, null));
        System.out.printf("SCEX_RECEIVER_ORDER_CONTRACT rows=%d fixed_phase=%d assertions=%d PASS original_PRNG_and_multisource_order_NOT_verified%n", rows, fixed, assertions);
    }
}
