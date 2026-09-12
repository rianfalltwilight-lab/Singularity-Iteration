// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public final class DeferredEntriesContract {
    private static int assertions;
    private static void check(boolean condition) { assertions++; if (!condition) throw new AssertionError("Contract " + assertions); }
    private static void rejects(Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException expected) { rejected = true; }
        check(rejected);
    }
    public static void main(String[] args) throws Exception {
        int rows = reference(Path.of(args[0]));
        try (var queue = new DeferredEntries<String, String>(4, 4)) {
            queue.observe(0, "a", "first"); check(queue.advance(0).isEmpty());
            queue.observe(1, "a", "first"); // Equal observation must not postpone.
            queue.observe(1, "b", "second");
            var one = queue.advance(1); check(one.size() == 1 && one.get(0).key().equals("a"));
            check(queue.metrics().published() == 1 && queue.metrics().pending() == 1);
            var two = queue.advance(2); check(two.size() == 1 && two.get(0).after().equals("second"));
            queue.observe(3, "a", null); check(queue.advance(3).isEmpty());
            queue.observe(4, "a", "first"); check(queue.advance(4).isEmpty()); // Restore cancels removal.
            queue.observe(5, "a", "replacement"); queue.observe(5, "c", "temporary");
            check(queue.advance(5).isEmpty());
            queue.observe(6, "c", null);
            var replacement = queue.advance(6); check(replacement.size() == 1);
            check(replacement.get(0).before().equals("first") && replacement.get(0).after().equals("replacement"));
            rejects(() -> replacement.clear());
            queue.observe(7, "future", "pending");
            var forgotten = queue.forgetIf(key -> key.equals("a") || key.equals("future"));
            check(forgotten.size() == 1 && forgotten.get(0).after() == null);
            check(queue.metrics().pending() == 0 && queue.metrics().published() == 1);
            check(queue.advance(8).isEmpty());
            rejects(() -> queue.advance(8)); rejects(() -> queue.observe(7, "x", "stale"));
            AtomicReference<Throwable> wrongThread = new AtomicReference<>();
            var thread = new Thread(() -> { try { queue.observe(9, "x", "foreign"); } catch (Throwable error) { wrongThread.set(error); } });
            thread.start(); thread.join(); check(wrongThread.get() instanceof IllegalStateException);
        }
        var bounded = new DeferredEntries<String, String>(1, 2);
        bounded.observe(0, "a", "a"); bounded.observe(0, "b", "b");
        rejects(() -> bounded.observe(0, "c", "c")); rejects(() -> bounded.advance(1));
        check(bounded.metrics().published() == 0 && bounded.metrics().pending() == 2 && bounded.metrics().advancedFrame() == -1);
        bounded.observe(1, "b", null); check(bounded.advance(1).size() == 1);
        bounded.close(); bounded.close();
        check(bounded.metrics().closed() && bounded.metrics().published() == 0 && bounded.metrics().pending() == 0);
        rejects(() -> bounded.advance(2)); rejects(() -> bounded.observe(2, "x", "x"));
        System.out.println("SCEX_DEFERRED_CONTRACT reference_rows=" + rows + " assertions=" + assertions + " PASS independent_publication_policy_and_observed_controls");
    }
    private static int reference(Path path) throws Exception {
        var lines = Files.readAllLines(path); check(lines.getFirst().equals("scenario\tframe\tkey\tvalue\tstart_credit\tend_credit"));
        String scenario = ""; DeferredEntries<String, String> queue = null; boolean catchUp = false; int rows = 0;
        for (String line : lines.subList(1, lines.size())) {
            var fields = line.split("\t");
            if (!fields[0].equals(scenario)) {
                if (queue != null) { check(queue.metrics().pending() == 0); queue.close(); }
                scenario = fields[0]; queue = new DeferredEntries<>(16, 16); catchUp = false;
            }
            long frame = Long.parseLong(fields[1]); long start = catchUp ? 31 : 0;
            if (!fields[2].equals(".")) queue.observe(frame, fields[2], fields[3].equals("-") ? null : fields[3]);
            catchUp = !queue.advance(frame).isEmpty();
            check(start == Long.parseLong(fields[4])); check(start + (catchUp ? 0 : 31) == Long.parseLong(fields[5])); rows++;
        }
        check(rows == 128); check(queue != null && queue.metrics().pending() == 0); queue.close(); return rows;
    }
}
