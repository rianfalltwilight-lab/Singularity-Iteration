// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

/** Frozen observed geometry/weather outputs plus a bounded read contract. */
public final class SolarColumnContract {
    private SolarColumnContract() { }
    public static void main(String[] args) throws Exception {
        int assertions = 0; var lines = Files.readAllLines(Path.of(args[0]));
        for (String line : lines.subList(1, lines.size())) {
            String[] row = line.split("\t"); int sample = Integer.parseInt(row[1]);
            var opacity = new HashMap<Integer, Integer>();
            if (!row[2].equals("-")) for (String block : row[2].split(",")) {
                String[] pair = block.split(":"); opacity.put(Integer.parseInt(pair[0]), Integer.parseInt(pair[1]));
            }
            int[] queries = {0};
            int light = SolarColumnLight.sample(sample, 256, y -> {
                queries[0]++;
                if (y < sample || Math.floorDiv(y, 16) != Math.floorDiv(sample, 16)) throw new AssertionError("Unbounded sky query");
                return opacity.getOrDefault(y, 0);
            });
            float actual = SolarOutputModel.rate(6000, light, Float.parseFloat(row[3]), Float.parseFloat(row[4]));
            if (Math.abs(actual - Double.parseDouble(row[5])) > 5e-6) throw new AssertionError("Frozen geometry " + row[0] + " actual=" + actual);
            assertions++;
            if (queries[0] < 1 || queries[0] > 16) throw new AssertionError("Sky read budget");
            assertions++;
        }
        if (SolarColumnLight.sample(320, 320, y -> { throw new AssertionError("Out of world read"); }) != 15) throw new AssertionError("World ceiling");
        assertions++;
        try { SolarColumnLight.sample(82, 320, y -> -1); throw new AssertionError("Invalid opacity accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
        System.out.println("SCEX_SOLAR_COLUMN rows=" + (lines.size() - 1) + " assertions=" + assertions + " PASS scope=observed_geometry_not_all_lighting_updates");
    }
}
