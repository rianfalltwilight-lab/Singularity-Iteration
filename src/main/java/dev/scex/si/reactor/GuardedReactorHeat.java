// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.reactor;
import com.singularity_iteration.mio_icif.api.capability.IMioIcifCapabilities;
import com.singularity_iteration.mio_icif.energy.heat.HeatStorage;
import com.singularity_iteration.mio_icif.energy.heat.IHeatStorage;
import java.util.function.BooleanSupplier;

/** A cached capability never owns heat and cannot outlive its owner admission predicate. */
public final class GuardedReactorHeat implements IHeatStorage,IMioIcifCapabilities.IHeatStorage {
    private final HeatStorage storage;private final BooleanSupplier available;private final Runnable changed;
    public GuardedReactorHeat(HeatStorage storage,BooleanSupplier available,Runnable changed){this.storage=storage;this.available=available;this.changed=changed;}
    @Override public long getHeatStored(){return available.getAsBoolean()?storage.getHeatStored():0;}
    @Override public long getMaxHeatStored(){return available.getAsBoolean()?storage.getMaxHeatStored():0;}
    @Override public long receiveHeat(long amount,boolean simulate){return 0;}
    @Override public long extractHeat(long amount,boolean simulate){if(!available.getAsBoolean())return 0;long n=storage.extractHeat(amount,simulate);if(!simulate&&n>0)changed.run();return n;}
    @Override public boolean canReceiveHeat(){return false;}
    @Override public boolean canExtractHeat(){return available.getAsBoolean()&&storage.canExtractHeat();}
    @Override public int getTemperature(){return available.getAsBoolean()?storage.getTemperature():20;}
    @Override public boolean isOverheated(){return available.getAsBoolean()&&storage.isOverheated();}
    @Override public long getHeatLossPerTick(){return 0;}
    @Override public long getMaxReceive(){return 0;}
    @Override public long getMaxExtract(){return available.getAsBoolean()?storage.getMaxExtract():0;}
}
