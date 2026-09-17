// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.energy;

import com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage;
import dev.scex.energy.ConsumableGeneration;
import net.minecraft.server.level.ServerLevel;

/** Generate from already owned fuel units; callers persist both returned counters together. */
public final class StoredFuelGeneration {
    private StoredFuelGeneration() { }
    public static ConsumableGeneration.Step tick(CustomEUEnergyStorage storage, int fuel, long credit, long production) {
        var quote = storage.scexNetworkQuote();
        if (!storage.scexNetworkControlled() || !(quote.ownerLevel() instanceof ServerLevel level)
                || !level.getServer().isSameThread())
            throw new IllegalStateException("Stored fuel requires an owned server-thread state");
        var plan = ConsumableGeneration.plan(fuel, credit, production, production,
            storage.scexExactAmount().roomBelow(storage.getCapacity()).whole(), 0);
        if (storage.generateEnergyInternal(plan.generated(), false) != plan.generated())
            throw new IllegalStateException("Owned EU capacity changed during stored-fuel generation");
        return plan;
    }
}
