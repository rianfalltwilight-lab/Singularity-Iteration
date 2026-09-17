// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

public final class FuelCreditContract {
    private static int assertions;
    private FuelCreditContract() { }
    private static void require(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        for (int perUnit : new int[]{16, 24}) for (int reserved : new int[]{0, 1, 17, 999, 1000})
            for (int partial : new int[]{0, 1, perUnit - 1}) {
                long credit = ConsumableGeneration.legacyCredit(reserved, partial, perUnit, 1000);
                long initial = Math.max(0, reserved * (long) perUnit - partial);
                require(credit == initial, "Legacy paid-for fuel quantity");
                long freshFuel = 23, generated = 0;
                long total = initial + freshFuel * perUnit;
                while (credit > 0 || freshFuel > 0) {
                    var step = ConsumableGeneration.plan(freshFuel, credit, perUnit, 36, 7, 0);
                    require(step.generated() > 0 && step.generated() <= 7, "Progress with a small output hole");
                    if (credit >= 7) require(step.fuelConsumed() == 0, "Use already paid-for credit before new fuel");
                    credit = step.bufferedEnergy(); freshFuel = step.fuelRemaining(); generated += step.generated();
                    // This scalar pair is exactly the state written to NBT on interruption.
                    require(generated + credit + freshFuel * perUnit == total, "Credit survives arbitrary save boundaries");
                }
                require(generated == total, "No loss at legacy bucket or last partial-unit boundaries");
            }
        var paused = ConsumableGeneration.plan(10, 16000, 16, 16, 0, 0);
        require(paused.bufferedEnergy() == 16000 && paused.fuelRemaining() == 10, "Full output does not burn paid fuel");
        long units = 1000 / 16, credit = 0, emitted = 0;
        while (units > 0 || credit > 0) {
            var step = ConsumableGeneration.plan(units, credit, 120, 120, 1, 0);
            units = step.fuelRemaining(); credit = step.bufferedEnergy(); emitted += step.generated();
        }
        require(emitted == (1000 / 16) * 120L, "Diesel preserves the SI 16 mB / 120 EU operation and unconsumed tank tail");
        require(ConsumableGeneration.legacyCredit(-1, Long.MAX_VALUE, 16, 1000) == 0, "Malformed negative legacy counter");
        System.out.println("SCEX_FUEL_CREDIT assertions=" + assertions + " PASS scope=SI_counter_migration_and_conservation_not_world_acceptance");
    }
}
