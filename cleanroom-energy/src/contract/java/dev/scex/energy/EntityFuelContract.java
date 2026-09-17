// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.util.SplittableRandom;

/** Resource admission and interrupted emission; no claim about world entity lifecycle. */
public final class EntityFuelContract {
    private static int assertions;
    private EntityFuelContract() { }
    private static void require(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        require(FuelAdmission.accepted(0, 7, 9) == 63, "Nine merged seven-XP orbs");
        require(FuelAdmission.accepted(Integer.MAX_VALUE - 63, 7, 9) == 63, "Exact remaining capacity");
        require(FuelAdmission.accepted(Integer.MAX_VALUE - 62, 7, 9) == 0, "Entire orb stays when it cannot fit");
        require(FuelAdmission.accepted(0, Integer.MAX_VALUE, Integer.MAX_VALUE) == 0, "Product cannot wrap into credit");
        for (int invalid : new int[]{Integer.MIN_VALUE, -1, 0}) {
            require(FuelAdmission.accepted(0, invalid, 1) == 0, "Nonpositive source value");
            require(FuelAdmission.accepted(0, 1, invalid) == 0, "Nonpositive merged count");
        }
        var random = new SplittableRandom(34);
        for (int production : new int[]{20, 40, 160, 320}) {
            for (int start : new int[]{1, 63, 20000, Integer.MAX_VALUE}) {
                long fuel = start;
                long remainder = 0;
                long emitted = 0;
                long initial = fuel * production;
                for (int tick = 0; tick < 600; tick++) {
                    long room = tick % 9 == 0 ? 0 : random.nextLong(production + 1L);
                    var step = ConsumableGeneration.plan(fuel, remainder, production, production, room, 0);
                    fuel = step.fuelRemaining();
                    remainder = step.bufferedEnergy();
                    emitted += step.generated();
                    require(emitted + fuel * production + remainder == initial, "Interrupted output conserves paid fuel");
                    require(step.generated() <= room && step.generated() <= production, "Output limits");
                    require(remainder >= 0 && remainder < production, "Persistable remainder");
                }
            }
        }
        System.out.println("SCEX_ENTITY_FUEL assertions=" + assertions + " PASS scope=admission_and_fuel_ledger_world_NOT_RUN");
    }
}
