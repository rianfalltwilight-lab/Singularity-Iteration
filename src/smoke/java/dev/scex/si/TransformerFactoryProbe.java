// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;
/** Read-only fixture facade; runtime replacement belongs to the maintained module. */
public final class TransformerFactoryProbe {
    private TransformerFactoryProbe() { }
    public static java.util.Map<String,Object> metrics() {
        return dev.scex.energy.minecraft.integration.TransformerFactory.metrics();
    }
}
