// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.file.Files;
import java.nio.file.Path;

/** Frozen public-game steady rates, not a self-generated implementation oracle. */
public final class SolarOutputContract {
    private SolarOutputContract() { }
    public static void main(String[] args) throws Exception {
        int assertions = 0; double maximumError = 0;
        var lines = Files.readAllLines(Path.of(args[0]));
        for (String line : lines.subList(1, lines.size())) {
            String[] row = line.split("\t");
            float rate = SolarOutputModel.rate(Long.parseLong(row[0]), Integer.parseInt(row[1]), Float.parseFloat(row[2]), Float.parseFloat(row[3]));
            double error = Math.abs(rate - Double.parseDouble(row[4])); maximumError = Math.max(error, maximumError);
            if (error > 5e-6) throw new AssertionError("Frozen steady input: " + line + " actual=" + rate);
            assertions++;
            if (EnergyAmount.fromDouble(rate).toDouble() != rate) throw new AssertionError("Observed solar fraction not exactly representable");
            assertions++;
        }
        System.out.println("SCEX_SOLAR_OUTPUT rows=" + (lines.size() - 1) + " assertions=" + assertions + " max_error=" + maximumError + " PASS scope=steady_model_not_refresh_or_runtime");
    }
}
