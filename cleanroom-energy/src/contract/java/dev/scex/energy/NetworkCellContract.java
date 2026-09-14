// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class NetworkCellContract {
    private static int assertions;
    private NetworkCellContract() { }
    private static void check(boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError("Check " + assertions);
    }
    private static void invalid(Runnable operation) {
        try { operation.run(); throw new AssertionError("Expected rejection"); }
        catch (IllegalArgumentException expected) { assertions++; }
    }
    public static void main(String[] args) throws InterruptedException {
        var source = new NetworkCell(128);
        var transformer = new NetworkCell(32);
        var receiver = new NetworkCell(0);
        check(source.quote() == source.quote());
        var initialQuote = source.quote();
        var round = List.of(new NetworkCell.Write(source.quote(), 96),
            new NetworkCell.Write(transformer.quote(), 32),
            new NetworkCell.Write(receiver.quote(), 31));
        check(NetworkCell.commit(round, 1, () -> true));
        check(source.quote() != initialQuote);
        check(source.quote().amount() == 96 && transformer.quote().amount() == 32 && receiver.quote().amount() == 31);
        check(!NetworkCell.commit(round, 1, () -> true));
        check(source.quote().amount() == 96 && receiver.quote().amount() == 31);

        // Returning to the same amount must not revive a stale quote (ABA).
        round = List.of(new NetworkCell.Write(source.quote(), 64),
            new NetworkCell.Write(transformer.quote(), 32), new NetworkCell.Write(receiver.quote(), 63));
        transformer.replace(33); transformer.replace(32);
        check(!NetworkCell.commit(round, 0, () -> true));
        check(source.quote().amount() == 96 && receiver.quote().amount() == 31);

        var guarded = List.of(new NetworkCell.Write(source.quote(), 64), new NetworkCell.Write(receiver.quote(), 63));
        check(!NetworkCell.commit(guarded, 0, () -> false));
        check(!NetworkCell.commit(guarded, 0, () -> { receiver.replace(30); return true; }));
        check(source.quote().amount() == 96 && receiver.quote().amount() == 30);
        try {
            NetworkCell.commit(guarded, 0, () -> { throw new IllegalStateException("World unavailable"); });
            throw new AssertionError("Expected guard failure");
        } catch (IllegalStateException expected) { check(source.quote().amount() == 96); }

        var q = source.quote();
        invalid(() -> NetworkCell.commit(List.of(new NetworkCell.Write(q, 96), new NetworkCell.Write(q, 96)), 0, () -> true));
        invalid(() -> NetworkCell.commit(List.of(new NetworkCell.Write(q, 97)), 0, () -> true));
        invalid(() -> NetworkCell.commit(List.of(), -1, () -> true));
        invalid(() -> new NetworkCell.Write(q, -1));
        check(source.quote().amount() == 96);

        // Aggregate reserves can exceed long while each stored amount remains valid.
        var hugeA = new NetworkCell(Long.MAX_VALUE);
        var hugeB = new NetworkCell(Long.MAX_VALUE);
        var small = new NetworkCell(0);
        check(NetworkCell.commit(List.of(new NetworkCell.Write(hugeA.quote(), Long.MAX_VALUE - 1),
            new NetworkCell.Write(hugeB.quote(), Long.MAX_VALUE - 1), new NetworkCell.Write(small.quote(), 1)), 1, () -> true));
        check(small.quote().amount() == 1);

        var retiredQuote = receiver.quote(); receiver.retire();
        check(!NetworkCell.commit(List.of(new NetworkCell.Write(source.quote(), 66),
            new NetworkCell.Write(retiredQuote, 60)), 0, () -> true));
        check(source.quote().amount() == 96);
        var threadFailure = new AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
            try { source.replace(0); } catch (Throwable error) { threadFailure.set(error); }
        });
        thread.start(); thread.join();
        check(threadFailure.get() instanceof IllegalStateException && source.quote().amount() == 96);
        System.out.println("SCEX_NETWORK_CELL assertions=" + assertions + " PASS scope=owned_numeric_transaction");
    }
}
