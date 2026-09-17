// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.energy;

import com.singularity_iteration.mio_icif.api.capability.IMioIcifCapabilities;
import com.singularity_iteration.mio_icif.energy.kinetic.KineticStorage;

/** Bind the reviewed numeric implementation to SI's public platform capability. Server-thread owned. */
public final class PlatformKineticStorage extends KineticStorage implements IMioIcifCapabilities.IKineticStorage {
    public PlatformKineticStorage(long capacity, long receive, long extract, int maxRPM, float friction) {
        super(capacity, receive, extract, maxRPM, friction);
    }
    @Override public boolean isOverspeed() { return getRPM() >= 8000; }
}
