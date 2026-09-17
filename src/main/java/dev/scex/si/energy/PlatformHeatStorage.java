// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.energy;

import com.singularity_iteration.mio_icif.api.capability.IMioIcifCapabilities;
import com.singularity_iteration.mio_icif.energy.heat.HeatStorage;

/** Bind the reviewed numeric implementation to SI's public platform capability. Server-thread owned. */
public final class PlatformHeatStorage extends HeatStorage implements IMioIcifCapabilities.IHeatStorage {
    public PlatformHeatStorage(long capacity, long receive, long extract) { super(capacity, receive, extract); }
    public PlatformHeatStorage(long capacity, long receive, long extract, int baseTemp, int maxTemp, float loss) {
        super(capacity, receive, extract, 0, baseTemp, maxTemp, loss);
    }
    @Override public boolean isOverheated() { return getTemperature() >= 800; }
    /** Loaded heat is an existing balance; capacity governs future admission, not destruction of saved heat. */
    @Override public void setHeat(long heat) { this.heat = Math.max(0, heat); }
    @Override public void setCapacity(long capacity) { this.capacity = Math.max(0, capacity); }
}
