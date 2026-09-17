// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.math.BigInteger;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/** Adversarial fractional conservation, stale identity, overflow and integer compatibility. */
public final class FractionalCellContract {
    private static int assertions;
    private FractionalCellContract() { }
    private static void check(boolean value) {
        assertions++;
        if (!value) throw new AssertionError("Check " + assertions);
    }
    private static void invalid(Runnable action) {
        try { action.run(); throw new AssertionError("Expected invalid input"); }
        catch (IllegalArgumentException | ArithmeticException expected) { assertions++; }
    }
    public static void main(String[] args) throws InterruptedException {
        long unit = EnergyAmount.UNITS;
        var random = new Random(0x29_15_52L);
        for (int i = 0; i < 25000; i++) {
            var a = new EnergyAmount(random.nextLong() >>> 1, random.nextLong() & (unit - 1));
            var b = new EnergyAmount(random.nextLong() >>> 1, random.nextLong() & (unit - 1));
            // Independent arbitrary-width oracle, including fraction carry/borrow.
            BigInteger aUnits = BigInteger.valueOf(a.whole()).multiply(BigInteger.valueOf(unit)).add(BigInteger.valueOf(a.fraction()));
            BigInteger bUnits = BigInteger.valueOf(b.whole()).multiply(BigInteger.valueOf(unit)).add(BigInteger.valueOf(b.fraction()));
            check(a.units().equals(aUnits));
            check(EnergyAmount.fromUnits(aUnits).equals(a));
            var high = a.compareTo(b) >= 0 ? a : b;
            var low = a.compareTo(b) >= 0 ? b : a;
            check(high.subtract(low).units().equals(aUnits.subtract(bUnits).abs()));
            check(high.subtract(low).add(low).equals(high));
            BigInteger sum = aUnits.add(bUnits);
            if (sum.shiftRight(52).bitLength() > 63) invalid(() -> a.add(b));
            else check(a.add(b).units().equals(sum));
        }
        invalid(() -> new EnergyAmount(-1, 0));
        invalid(() -> new EnergyAmount(0, -1));
        invalid(() -> new EnergyAmount(0, unit));
        invalid(() -> EnergyAmount.fromDouble(Double.NaN));
        invalid(() -> EnergyAmount.fromDouble(Double.POSITIVE_INFINITY));
        invalid(() -> EnergyAmount.fromDouble(-.5));
        invalid(() -> EnergyAmount.fromDouble(0x1p63));
        invalid(() -> new EnergyAmount(Long.MAX_VALUE, unit - 1).add(new EnergyAmount(0, 1)));
        check(EnergyAmount.fromDouble(0x1p-53).equals(new EnergyAmount(0, 1)));
        check(EnergyAmount.fromDouble(Math.nextDown(0x1p-53)).isZero());
        for (double input : new double[]{.125, .5, .75, 1.25, 32.5, 40000.25, 1d / 8,
                .6309009194374084, .8040808439254761, .7605451941490173, .07823193073272705,
                .9333333373069763, .800000011920929, .6875, .47265625})
            check(EnergyAmount.fromDouble(input).toDouble() == input);
        var huge = new EnergyAmount(Long.MAX_VALUE, unit - 1);
        check(huge.subtract(EnergyAmount.of(Long.MAX_VALUE)).equals(new EnergyAmount(0, unit - 1)));
        check(new EnergyAmount(39999, unit / 2).roomBelow(40000).equals(new EnergyAmount(0, unit / 2)));
        check(new EnergyAmount(40000, 1).roomBelow(40000).isZero());

        var source = new NetworkCell(EnergyAmount.fromDouble(1.25));
        var sink = new NetworkCell(EnergyAmount.fromDouble(.5));
        var old = source.quote();
        var writes = List.of(new NetworkCell.Write(old, EnergyAmount.fromDouble(.125)),
            new NetworkCell.Write(sink.quote(), EnergyAmount.fromDouble(1.5)));
        check(NetworkCell.commit(writes, EnergyAmount.fromDouble(.125), () -> true));
        check(source.quote().exactAmount().equals(EnergyAmount.fromDouble(.125)));
        check(sink.quote().exactAmount().equals(EnergyAmount.fromDouble(1.5)));
        check(!NetworkCell.commit(writes, EnergyAmount.fromDouble(.125), () -> true));
        // Integer movement must retain existing fractional source/sink remainders.
        check(NetworkCell.commit(List.of(new NetworkCell.Write(sink.quote(), 0),
            new NetworkCell.Write(source.quote(), 1)), 0, () -> true));
        check(source.quote().exactAmount().equals(EnergyAmount.fromDouble(1.125)));
        check(sink.quote().exactAmount().equals(EnergyAmount.fromDouble(.5)));
        var stable = source.quote();
        invalid(() -> NetworkCell.commit(List.of(new NetworkCell.Write(stable, new EnergyAmount(1, stable.fraction() + 1))), 0, () -> true));
        check(source.quote() == stable);
        // A fractional-only update revokes an otherwise identical integer quote.
        writes = List.of(new NetworkCell.Write(stable, EnergyAmount.fromDouble(.625)),
            new NetworkCell.Write(sink.quote(), EnergyAmount.of(1)));
        source.replace(EnergyAmount.fromDouble(1.25)); source.replace(EnergyAmount.fromDouble(1.125));
        check(!NetworkCell.commit(writes, 0, () -> true));
        var guarded = List.of(new NetworkCell.Write(source.quote(), EnergyAmount.fromDouble(.625)),
            new NetworkCell.Write(sink.quote(), EnergyAmount.of(1)));
        check(!NetworkCell.commit(guarded, 0, () -> { sink.replace(EnergyAmount.fromDouble(.75)); return true; }));
        check(source.quote().exactAmount().equals(EnergyAmount.fromDouble(1.125)));
        check(sink.quote().exactAmount().equals(EnergyAmount.fromDouble(.75)));
        var retired = sink.quote(); sink.retire();
        check(!NetworkCell.commit(List.of(new NetworkCell.Write(source.quote(), EnergyAmount.fromDouble(.875)),
            new NetworkCell.Write(retired, EnergyAmount.of(1))), 0, () -> true));
        var fault = new AtomicReference<Throwable>();
        Thread other = new Thread(() -> {
            try { source.replace(EnergyAmount.fromDouble(.5)); } catch (Throwable error) { fault.set(error); }
        });
        other.start(); other.join();
        check(fault.get() instanceof IllegalStateException);
        var largeA = new NetworkCell(new EnergyAmount(Long.MAX_VALUE, unit - 1));
        var largeB = new NetworkCell(new EnergyAmount(Long.MAX_VALUE, unit - 1));
        var tiny = new NetworkCell(0);
        check(NetworkCell.commit(List.of(new NetworkCell.Write(largeA.quote(), new EnergyAmount(Long.MAX_VALUE, unit - 2)),
            new NetworkCell.Write(largeB.quote(), new EnergyAmount(Long.MAX_VALUE, unit - 2)),
            new NetworkCell.Write(tiny.quote(), new EnergyAmount(0, 1))), new EnergyAmount(0, 1), () -> true));
        check(tiny.quote().fraction() == 1);
        System.out.println("SCEX_FRACTIONAL_CELL assertions=" + assertions + " PASS scope=exact_fixed_fraction_transactions");
    }
}
