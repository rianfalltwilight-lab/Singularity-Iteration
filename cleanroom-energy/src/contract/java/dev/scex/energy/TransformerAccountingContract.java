// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

/** Validates real observations as one-step outcomes, not scheduler predictions. */
public final class TransformerAccountingContract {
    private static long assertions;
    private TransformerAccountingContract() { }
    private static void require(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
    private static void rejects(Class<? extends RuntimeException> type, Runnable action) {
        try { action.run(); }
        catch (RuntimeException exception) {
            require(type.isInstance(exception), "Unexpected exception " + exception);
            return;
        }
        throw new AssertionError("Missing rejection " + type);
    }
    private static void invariant(TransformerAccounting.Configuration configuration,
                                  TransformerAccounting.State before, TransformerAccounting.Step step) {
        var after = step.after();
        require(step.sourceDebit() >= 0 && step.sourceDebit() <= before.source(), "Source bound");
        require(after.buffer() >= 0 && after.buffer() <= configuration.capacity(), "Buffer bound");
        require(before.source() + before.buffer() + before.receiver()
                == after.source() + after.buffer() + after.receiver(), "Conservation");
    }
    public static void main(String[] args) throws Exception {
        var lines = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        require(lines.size() == 15201, "Expected frozen observation count");
        require(lines.getFirst().startsWith("case\tlow_packet\t"), "Fixture header");
        var labels = new HashSet<String>();
        long inputOnly = 0, outputOnly = 0, both = 0, unmatched = 0;
        var failures = new StringBuilder();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\t", -1);
            require(f.length == 12 && labels.add(f[0]), "Unique well-formed observation");
            var configuration = new TransformerAccounting.Configuration(Long.parseLong(f[1]), f[2].equals("1"));
            long sourcePacket = Long.parseLong(f[3]), receiverCapacity = Long.parseLong(f[4]);
            boolean connected = f[5].equals("1");
            var before = new TransformerAccounting.State(Long.parseLong(f[6]), Long.parseLong(f[7]), Long.parseLong(f[8]));
            var observed = new TransformerAccounting.State(Long.parseLong(f[9]), Long.parseLong(f[10]), Long.parseLong(f[11]));
            var input = TransformerAccounting.advance(configuration, before, sourcePacket, receiverCapacity,
                    connected, TransformerAccounting.Order.INPUT_FIRST);
            var output = TransformerAccounting.advance(configuration, before, sourcePacket, receiverCapacity,
                    connected, TransformerAccounting.Order.OUTPUT_FIRST);
            invariant(configuration, before, input); invariant(configuration, before, output);
            boolean i = input.after().equals(observed), o = output.after().equals(observed);
            if (i && o) both++;
            else if (i) inputOnly++;
            else if (o) outputOnly++;
            else {
                unmatched++;
                if (unmatched <= 8) failures.append(f[0]).append(" before=").append(before)
                        .append(" observed=").append(observed).append(" input=").append(input.after())
                        .append(" output=").append(output.after()).append('\n');
            }
        }
        System.out.printf("SCEX_TRANSFORMER_ACCOUNTING rows=%d input_only=%d output_only=%d both=%d unmatched=%d%n",
                lines.size()-1, inputOnly, outputOnly, both, unmatched);
        require(unmatched == 0, failures.toString());
        require(inputOnly > 0 && outputOnly > 0, "Both schedule-sensitive outcomes represented");
        rejects(IllegalArgumentException.class, () -> new TransformerAccounting.Configuration(0, false));
        rejects(IllegalArgumentException.class, () -> new TransformerAccounting.Configuration(Long.MAX_VALUE, true));
        rejects(IllegalArgumentException.class, () -> TransformerAccounting.advance(
                new TransformerAccounting.Configuration(32, true), new TransformerAccounting.State(512, 0, 0),
                128, 1000, true, TransformerAccounting.Order.INPUT_FIRST));
        rejects(IllegalArgumentException.class, () -> TransformerAccounting.advance(
                new TransformerAccounting.Configuration(32, false), new TransformerAccounting.State(0, 257, 0),
                128, 1000, true, TransformerAccounting.Order.INPUT_FIRST));
        rejects(ArithmeticException.class, () -> TransformerAccounting.advance(
                new TransformerAccounting.Configuration(32, false), new TransformerAccounting.State(0, 128, Long.MAX_VALUE-33),
                128, Long.MAX_VALUE, true, TransformerAccounting.Order.INPUT_FIRST));
        System.out.printf("SCEX_TRANSFORMER_CONTRACT PASS assertions=%d scope=one_step_accounting_not_schedule_prediction%n", assertions);
    }
}
