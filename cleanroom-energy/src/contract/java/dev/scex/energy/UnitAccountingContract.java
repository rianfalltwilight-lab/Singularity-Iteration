// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.math.BigInteger;
import java.util.SplittableRandom;

public final class UnitAccountingContract {
    private static int assertions;
    private UnitAccountingContract() { }
    private static void require(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }
    private static BigInteger potential(long fuel, long buffered, long perUnit) {
        return BigInteger.valueOf(fuel).multiply(BigInteger.valueOf(perUnit)).add(BigInteger.valueOf(buffered));
    }
    public static void main(String[] args) {
        long[] boundary = {Long.MIN_VALUE, -1, 0, 1, 99, Long.MAX_VALUE - 1, Long.MAX_VALUE};
        for (long capacity : boundary) for (long amount : boundary) for (long request : boundary) {
            long stored = BoundedUnits.clamp(amount, capacity);
            long added = BoundedUnits.receive(stored, capacity, request, 100);
            long taken = BoundedUnits.extract(stored, request, 100);
            require(added >= 0 && taken >= 0 && taken <= stored, "Non-negative bounded transfers");
            require(added <= Math.max(0, capacity) - stored, "No capacity overflow");
            require(request > 0 || added == 0 && taken == 0, "Non-positive requests are inert");
        }
        for (int percent = 0; percent <= 100; percent++) {
            long amount = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(percent)).divide(BigInteger.valueOf(100)).longValueExact();
            int expectedPercent = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(100)).divide(BigInteger.valueOf(Long.MAX_VALUE)).intValueExact();
            require(BoundedUnits.gauge(amount, Long.MAX_VALUE, 20, 1000) == 20 + expectedPercent * 980 / 100, "Temperature gauge at long limits");
            require(BoundedUnits.gauge(amount, Long.MAX_VALUE, 0, Integer.MAX_VALUE)
                == (int) (expectedPercent * (long) Integer.MAX_VALUE / 100), "RPM gauge at long limits");
        }
        require(BoundedUnits.gauge(Long.MAX_VALUE, Long.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE) == Integer.MAX_VALUE,
            "Full signed temperature span");
        for (float factor : new float[]{-1, 0, .005F, 1, Float.MAX_VALUE, Float.NaN, Float.POSITIVE_INFINITY})
            for (long amount : new long[]{0, 1, 99, Long.MAX_VALUE}) {
                long loss = BoundedUnits.friction(amount, factor);
                require(loss >= 0 && loss <= amount, "Bounded decay");
            }

        long water = 1000, buffer = 0, emitted = 0;
        for (int tick = 0; tick < 4000; tick++) {
            // A one-EU hole exercises the partial final fuel unit and saveable carry each tick.
            var step = ConsumableGeneration.plan(water, buffer, 4, 4, 1, 0);
            water = step.fuelRemaining(); buffer = step.bufferedEnergy(); emitted += step.generated();
            require(water * 4 + buffer + emitted == 4000, "One bucket remains exactly 4000 candidate EU");
        }
        require(water == 0 && buffer == 0 && emitted == 4000, "No fuel loss at saturation");
        var full = ConsumableGeneration.plan(1000, 3, 4, 4, 0, 2);
        require(full.generated() == 0 && full.fuelConsumed() == 0 && full.bufferedEnergy() == 3, "Full output preserves inputs");

        var random = new SplittableRandom(32);
        for (int sample = 0; sample < 2000; sample++) {
            long fuel = sample % 10 == 0 ? Long.MAX_VALUE : random.nextLong(10000);
            long perUnit = sample % 11 == 0 ? Long.MAX_VALUE : random.nextLong(1, 10000);
            long buffered = random.nextLong(perUnit);
            long room = sample % 7 == 0 ? Long.MAX_VALUE : random.nextLong(10000);
            long rate = sample % 9 == 0 ? Long.MAX_VALUE : random.nextLong(10000);
            long ambient = random.nextLong(100);
            var step = ConsumableGeneration.plan(fuel, buffered, perUnit, rate, room, ambient);
            require(step.generated() >= 0 && step.generated() <= room, "Generation within output room");
            require(step.fuelConsumed() >= 0 && step.fuelRemaining() + step.fuelConsumed() == fuel, "Fuel count conservation");
            require(step.bufferedEnergy() >= 0 && step.bufferedEnergy() < perUnit, "Canonical saved remainder");
            require(potential(fuel, buffered, perUnit).equals(potential(step.fuelRemaining(), step.bufferedEnergy(), perUnit)
                .add(BigInteger.valueOf(step.fuelGenerated()))), "Fuel-energy conservation at long limits");
        }
        System.out.println("SCEX_UNIT_ACCOUNTING assertions=" + assertions + " PASS scope=integer_invariants_not_reference_parity");
    }
}
