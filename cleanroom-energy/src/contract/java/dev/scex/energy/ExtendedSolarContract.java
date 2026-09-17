// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

/** Cheap model/accounting checks; this is not Minecraft or reference charging acceptance. */
public final class ExtendedSolarContract {
    private static int assertions;
    private ExtendedSolarContract() { }
    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static final class Cell {
        long energy, capacity, rate;
        Cell(long energy, long capacity, long rate) { this.energy = energy; this.capacity = capacity; this.rate = rate; }
    }
    private record Adapter(long acceptanceLimit, int defect) implements StagedCharge.Battery<Cell> {
        public Cell copy(Cell item) { return defect == 4 ? item : new Cell(item.energy, item.capacity, item.rate); }
        public long stored(Cell item) { return item.energy; }
        public long capacity(Cell item) { return item.capacity; }
        public long rate(Cell item) { return item.rate; }
        public long charge(Cell item, long offer) {
            long accepted = Math.min(offer, acceptanceLimit);
            item.energy += accepted;
            if (defect == 1) return accepted + 1;
            if (defect == 2) { item.energy--; return accepted; }
            if (defect == 3) item.capacity++;
            if (defect == 5) throw new IllegalStateException("Injected detached-item failure");
            return accepted;
        }
    }
    public static void main(String[] args) throws Exception {
        var lines = Files.readAllLines(Path.of(args[0]));
        require(lines.size() == 121, "All 120 frozen ASP cells must be present");
        for (var line : lines.subList(1, lines.size())) {
            var row = line.split("\t");
            var profile = SolarGeneratorProfile.find(row[0]).orElseThrow();
            boolean sky = row[1].equals("air") || row[1].equals("glass");
            boolean day = !row[2].equals("night-clear");
            boolean wet = row[2].equals("noon-rain") || row[2].equals("noon-thunder");
            require(profile.discreteOutput(sky, day, wet) == Long.parseLong(row[3]), "Frozen steady rate: " + line);
        }
        require(SolarGeneratorProfile.all().size() == 7, "Seven distinct registered candidates");
        require(SolarGeneratorProfile.find("mio_icif:generator/block_solar_generator").isEmpty(), "Basic solar stays separate");
        require(SolarGeneratorProfile.find("other:generator/block_quantum_solar_panel").isEmpty(), "Exact namespace");
        for (var profile : SolarGeneratorProfile.all()) {
            require(profile.chargeSlots() == 4 && profile.capacity() > 2, "Extended buffer/slots");
            var saved = new EnergyAmount(profile.capacity() - 1, EnergyAmount.UNITS / 2);
            var generated = EnergyAmount.of(profile.dayPower()).min(saved.roomBelow(profile.capacity()));
            require(saved.add(generated).equals(EnergyAmount.of(profile.capacity())), "Fractional saturation without overfill");
            require(EnergyAmount.of(profile.capacity() + 1).roomBelow(profile.capacity()).isZero(), "Over-capacity saves cannot generate");
            if (!profile.discrete()) {
                require(profile.scaledOutput(1) == profile.dayPower(), "Generation is not packet/production limit");
                require(profile.scaledOutput(Float.NaN) == 0 && profile.scaledOutput(Float.POSITIVE_INFINITY) == 0,
                    "Invalid cached light cannot generate");
                require(profile.scaledOutput(-1) == 0 && profile.scaledOutput(2) == profile.dayPower(), "Brightness bounds");
            }
        }
        var photon = SolarGeneratorProfile.find("mio_icif:generator/block_photon_resonance_solar_generator").orElseThrow();
        require(photon.scaledOutput(1) == 512 && photon.outputPacket() == 2048, "Photon generation/output separation");

        // Partial acceptance, rejection and malformed implementations must not debit the offered amount.
        for (int defect = 0; defect <= 5; defect++) {
            var original = new Cell(10, 1000, 128);
            try {
                var result = StagedCharge.prepare(original, 500, 64, new Adapter(17, defect));
                require(defect < 4, "Unsafe copy or exception must abort preparation");
                require(result.isPresent() == (defect == 0), "Reject inconsistent charge reports");
                if (result.isPresent()) require(result.orElseThrow().debit() == 17, "Debit actual partial acceptance");
            } catch (IllegalArgumentException | IllegalStateException expected) {
                require(defect >= 4, "Only injected unsafe-copy/failure cases throw");
            }
            require(original.energy == 10 && original.capacity == 1000, "Original unchanged on preparation/failure");
        }
        var random = new SplittableRandom(31);
        for (int sample = 0; sample < 1000; sample++) {
            long available = random.nextLong(300), limit = random.nextLong(300), acceptedLimit = random.nextLong(300);
            var original = new Cell(random.nextLong(501), 500, random.nextLong(300));
            long expected = Math.min(Math.min(available, limit), Math.min(original.rate, Math.min(500 - original.energy, acceptedLimit)));
            var result = StagedCharge.prepare(original, available, limit, new Adapter(acceptedLimit, 0));
            require(result.isPresent() == (expected > 0), "Empty/full/rate-limited source or item");
            if (result.isPresent()) {
                var prepared = result.orElseThrow();
                require(prepared.debit() == expected, "Bounded actual acceptance");
                require(available + original.energy == available - prepared.debit() + prepared.item().energy,
                    "Source/item conservation after publishing the prepared pair");
            }
        }
        var enormous = new Cell(0, Long.MAX_VALUE, Long.MAX_VALUE);
        var result = StagedCharge.prepare(enormous, Long.MAX_VALUE, Long.MAX_VALUE, new Adapter(Long.MAX_VALUE, 0)).orElseThrow();
        require(result.debit() == Long.MAX_VALUE && enormous.energy == 0, "No overflow at long limits");
        System.out.println("SCEX_EXTENDED_SOLAR frozen_cells=120 assertions=" + assertions
            + " PASS scope=model_and_detached_charge_only runtime=NOT_RUN");
    }
}
