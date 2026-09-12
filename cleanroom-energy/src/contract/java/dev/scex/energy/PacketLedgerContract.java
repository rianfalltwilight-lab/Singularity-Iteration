// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Random;

/** Tests use frozen external observations and accounting invariants, never target code. */
public final class PacketLedgerContract {
    private static long assertions;

    private PacketLedgerContract() { }

    private static void require(boolean condition, String label) {
        assertions++;
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private static void rejects(Class<? extends RuntimeException> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            require(type.isInstance(exception), "Unexpected rejection: " + exception);
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void accounting(long reserve, long packet, long room, PacketLedger.Line line,
                                   PacketLedger.Plan result) {
        require(result.sourceDebit() >= 0 && result.sourceDebit() <= reserve, "Source bounds");
        require(result.receiverCredit() >= 0 && result.receiverCredit() <= room, "Receiver bounds");
        require(result.dissipated() >= 0, "Nonnegative loss");
        require(result.sourceDebit() - result.receiverCredit() == result.dissipated(), "Conservation");
        require(result.dissipated() == Math.multiplyExact(result.transfers(), line.wholeLoss()), "Loss per transfer");
        require(!result.lineFused() || result.transfers() == 1, "Fuse stops subsequent transfers");
        require(result.lineFused() || reserve - result.sourceDebit() < packet
                || room == result.receiverCredit(), "Settled stop condition");
        if (result.transfers() == 0) {
            require(result.sourceDebit() == 0 && result.receiverCredit() == 0 && !result.lineFused(), "Idle has no effects");
        }
    }

    public static void main(String[] args) throws Exception {
        var rows = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        require(rows.getFirst().startsWith("case\tsource_energy\tpacket\t"), "Fixture header");
        var labels = new HashSet<String>();
        for (String row : rows.subList(1, rows.size())) {
            String[] fields = row.split("\t", -1);
            require(fields.length == 10 && labels.add(fields[0]), "Unique well-formed fixture");
            long reserve = Long.parseLong(fields[1]);
            long packet = Long.parseLong(fields[2]);
            long room = Long.parseLong(fields[3]);
            var line = new PacketLedger.Line(Integer.parseInt(fields[5]),
                    Long.parseLong(fields[4]), Long.parseLong(fields[6]));
            var result = PacketLedger.settle(reserve, packet, room, line);
            require(result.sourceDebit() == Long.parseLong(fields[7]), fields[0] + " source " + result);
            require(result.receiverCredit() == Long.parseLong(fields[8]), fields[0] + " receiver " + result);
            String mask = fields[9].equals("-") ? "" : fields[9];
            require(mask.length() == line.conductors(), "Wire mask length");
            for (char observed : mask.toCharArray()) {
                require(observed == '?' || observed == '0' || observed == '1', "Valid mask symbol");
                if (observed != '?') {
                    require((observed == '0') == result.lineFused(), fields[0] + " fuse " + result);
                }
            }
            accounting(reserve, packet, room, line, result);
        }
        require(labels.size() == 188, "Expected frozen observation count");

        var random = new Random(0x53434558L);
        for (int i = 0; i < 20000; i++) {
            long reserve = random.nextLong() & Long.MAX_VALUE;
            long room = random.nextLong() & Long.MAX_VALUE;
            var line = new PacketLedger.Line(random.nextInt(20), random.nextInt(500),
                    1L + random.nextInt(10000));
            long packet = line.wholeLoss() + 1 + random.nextInt(10000);
            var result = PacketLedger.settle(reserve, packet, room, line);
            accounting(reserve, packet, room, line, result);
            if (!result.lineFused()) {
                var after = PacketLedger.settle(reserve - result.sourceDebit(), packet,
                        room - result.receiverCredit(), line);
                require(after.transfers() == 0, "Settling is idempotent");
            }
        }

        var direct = new PacketLedger.Line(0, Long.MAX_VALUE, 1);
        var huge = PacketLedger.settle(Long.MAX_VALUE, 1, Long.MAX_VALUE, direct);
        require(huge.sourceDebit() == Long.MAX_VALUE && huge.receiverCredit() == Long.MAX_VALUE
                && huge.transfers() == Long.MAX_VALUE && !huge.lineFused(), "Maximum-size constant-time batch");
        var oneLoss = new PacketLedger.Line(5, 200, Long.MAX_VALUE);
        accounting(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, oneLoss,
                PacketLedger.settle(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, oneLoss));
        var partial = PacketLedger.settle(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 2, oneLoss);
        require(partial.sourceDebit() == Long.MAX_VALUE - 1, "Maximum-size partial debit");
        rejects(IllegalArgumentException.class, () -> new PacketLedger.Line(-1, 0, 1));
        rejects(IllegalArgumentException.class, () -> new PacketLedger.Line(1, -1, 1));
        rejects(IllegalArgumentException.class, () -> new PacketLedger.Line(1, 0, 0));
        rejects(ArithmeticException.class, () -> new PacketLedger.Line(2, Long.MAX_VALUE, 1));
        rejects(IllegalArgumentException.class, () -> PacketLedger.settle(-1, 32, 1, direct));
        rejects(IllegalArgumentException.class, () -> PacketLedger.settle(32, 0, 1, direct));
        rejects(IllegalArgumentException.class, () -> PacketLedger.settle(32, 32, -1, direct));
        rejects(NullPointerException.class, () -> PacketLedger.settle(32, 32, 1, null));
        rejects(IllegalArgumentException.class, () -> PacketLedger.settle(32, 1, 1, oneLoss));
        System.out.println("SCEX_LEDGER_CONTRACT observations=" + labels.size()
                + " randomized_accounts=20000 assertions=" + assertions + " PASS");
    }
}
