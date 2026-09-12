// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

/** Scene identities and observable outcomes, with destroyed energy left unknown. */
public final class UniformPacketEffectsContract {
    private static long assertions;
    private UniformPacketEffectsContract() { }
    private static void require(boolean value, String message) {
        assertions++; if (!value) { throw new AssertionError(message); }
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Expected invalid input rejection");
    }
    public static void main(String[] args) throws Exception {
        var lines = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        require(lines.getFirst().startsWith("case\treserve\tpacket\troom\t"), "Fixture header");
        var labels = new HashSet<String>(); int destroyed = 0; int fused = 0; int wireExcluded = 0;
        for (String row : lines.subList(1, lines.size())) {
            String[] f = row.split("\t", -1); require(f.length == 12 && labels.add(f[0]), "Unique complete observation");
            long reserve = Long.parseLong(f[1]), packet = Long.parseLong(f[2]), room = Long.parseLong(f[3]);
            int count = Integer.parseInt(f[4]);
            var path = new PacketLedger.Line(count, Long.parseLong(f[5]), Long.parseLong(f[6]));
            var result = UniformPacketEffects.evaluate(reserve, packet, room, path, Long.parseLong(f[7]));
            require(result.sourceDebit() == Long.parseLong(f[8]), f[0] + " source debit");
            require(result.receiverDestroyed() == f[10].equals("0"), f[0] + " receiver survival");
            if (result.receiverDestroyed()) {
                destroyed++; require(f[9].equals("?") && result.retainedReceiverCredit().isEmpty(), "Destroyed energy remains unknown");
            } else {
                require(result.retainedReceiverCredit().orElseThrow() == Long.parseLong(f[9]), f[0] + " retained credit");
                require(result.sourceDebit() - result.retainedReceiverCredit().orElseThrow() == result.pathLoss(), "Surviving account conservation");
            }
            String[] survival = f[11].equals("-") ? new String[0] : f[11].split(",");
            require(survival.length == count, "Complete observed conductor vector");
            if (result.lineFused()) {
                fused++; require(!result.receiverDestroyed(), "Observed fuse/surviving receiver outcome");
                for (String alive : survival) { require(alive.equals("0"), f[0] + " entire uniform line removed"); }
            } else if (!result.receiverDestroyed()) {
                for (String alive : survival) { require(alive.equals("1"), f[0] + " safe line survived"); }
            } else {
                wireExcluded += survival.length; // Explosion propagation has no model here.
            }
        }
        require(labels.size() == 156, "Frozen observation count");
        var tin = new PacketLedger.Line(5, 200, 33);
        var none = UniformPacketEffects.evaluate(127, 128, 1000, tin, 32);
        require(none.sourceDebit() == 0 && !none.lineFused() && !none.receiverDestroyed(), "Below complete packet cannot damage");
        var full = UniformPacketEffects.evaluate(128, 128, 0, tin, 32);
        require(full.sourceDebit() == 0 && full.retainedReceiverCredit().orElseThrow() == 0 && !full.receiverDestroyed(), "Closed receiver no effects");
        var single = UniformPacketEffects.evaluate(Long.MAX_VALUE, 32, Long.MAX_VALUE, new PacketLedger.Line(0, 0, 33), 32);
        require(single.sourceDebit() == 32 && single.retainedReceiverCredit().orElseThrow() == 32, "Only one packet per call");
        rejects(() -> UniformPacketEffects.evaluate(-1, 128, 1, tin, 32));
        rejects(() -> UniformPacketEffects.evaluate(128, 0, 1, tin, 32));
        rejects(() -> UniformPacketEffects.evaluate(128, 128, -1, tin, 32));
        rejects(() -> UniformPacketEffects.evaluate(128, 128, 1, tin, 0));
        System.out.println("SCEX_EFFECTS_CONTRACT cases=" + labels.size() + " receiver_destroyed=" + destroyed
                + " uniform_fuse_cases=" + fused + " blast_affected_wire_observations_excluded=" + wireExcluded
                + " assertions=" + assertions + " PASS blast_shape_and_timing_NOT_verified");
    }
}
