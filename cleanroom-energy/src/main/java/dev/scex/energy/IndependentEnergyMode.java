// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

/**
 * One admission policy for numerical ownership and placement/load factories.
 * Properties remain launch-time experimental switches; hot switching is unsupported.
 * Complete normal-default adoption is blocked by unimplemented endpoint contracts.
 */
public final class IndependentEnergyMode {
    private IndependentEnergyMode() { }
    public static boolean enabled() { return Boolean.getBoolean("scex.independent.energy"); }
    public static boolean feature(String feature) {
        return enabled() && Boolean.getBoolean("scex.independent." + feature);
    }
}
