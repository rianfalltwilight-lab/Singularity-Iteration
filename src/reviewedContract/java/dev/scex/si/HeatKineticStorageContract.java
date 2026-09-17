// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.singularity_iteration.mio_icif.energy.heat.HeatStorage;
import com.singularity_iteration.mio_icif.energy.kinetic.KineticStorage;
import java.nio.file.Path;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.LongTag;

/** Real reviewed storage classes and ordinary NBT, without a Minecraft world/server. */
public final class HeatKineticStorageContract {
    private static int assertions;
    private HeatKineticStorageContract() { }
    private static void require(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }
    public static void main(String[] args) throws Exception {
        var expected = Path.of(args[0]).toRealPath();
        for (var type : new Class<?>[]{HeatStorage.class, KineticStorage.class})
            require(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),
                "Must load the just-compiled class, not the R5 binary");
        for (long capacity : new long[]{-1, 0, 1, 100, Long.MAX_VALUE})
            for (long initial : new long[]{Long.MIN_VALUE, -1, 0, 1, 100, Long.MAX_VALUE}) {
                var heat = new HeatStorage(capacity, 17, 13, initial, 20, 1000, .01F);
                var kinetic = new KineticStorage(capacity, 17, 13, initial, 10000, .005F);
                long bounded = Math.max(0, Math.min(Math.max(0, capacity), initial));
                require(heat.getHeatStored() == bounded && kinetic.getKineticStored() == bounded, "Constructor bounds");
                for (long request : new long[]{Long.MIN_VALUE, -1, 0}) {
                    require(heat.consumeHeatInternal(request, false) == 0 && heat.generateHeatInternal(request, false) == 0,
                        "Negative internal heat requests are inert");
                    require(kinetic.generateKineticInternal(request, false) == 0, "Negative internal kinetic request is inert");
                    require(heat.getHeatStored() == bounded && kinetic.getKineticStored() == bounded, "No generated/destroyed units");
                }
                long heatBefore = heat.getHeatStored(), kineticBefore = kinetic.getKineticStored();
                heat.receiveHeat(19, true); heat.extractHeat(19, true); heat.consumeHeatInternal(19, true); heat.generateHeatInternal(19, true);
                kinetic.receiveKinetic(19, true); kinetic.extractKinetic(19, true); kinetic.generateKineticInternal(19, true);
                require(heat.getHeatStored() == heatBefore && kinetic.getKineticStored() == kineticBefore, "Simulation does not mutate");
                long heatLoss = heat.applyHeatLoss(), kineticLoss = kinetic.getKineticLossPerTick();
                kinetic.applyFrictionLoss();
                require(heat.getHeatStored() == heatBefore - heatLoss, "Heat decay accounting");
                require(kinetic.getKineticStored() == kineticBefore - kineticLoss, "Advertised friction equals actual decay");
                for (long saved : new long[]{Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE}) {
                    heat.deserializeNBT(null, LongTag.valueOf(saved));
                    kinetic.deserializeNBT(null, LongTag.valueOf(saved));
                    long expectedSaved = Math.max(0, Math.min(Math.max(0, capacity), saved));
                    require(heat.getHeatStored() == expectedSaved && kinetic.getKineticStored() == expectedSaved, "Bounded long NBT restore");
                    var h2 = new HeatStorage(capacity, 17, 13);
                    var k2 = new KineticStorage(capacity, 17, 13);
                    h2.deserializeNBT(null, heat.serializeNBT(null));
                    k2.deserializeNBT(null, kinetic.serializeNBT(null));
                    require(h2.getHeatStored() == expectedSaved && k2.getKineticStored() == expectedSaved, "NBT round trip");
                }
                heat.deserializeNBT(null, IntTag.valueOf(-1)); kinetic.deserializeNBT(null, IntTag.valueOf(-1));
                require(heat.getHeatStored() == 0 && kinetic.getKineticStored() == 0, "Legacy int NBT restore");
            }
        var heatSource = new HeatStorage(1000, 0, 100, 100, 20, 1000, 0);
        var heatTarget = new HeatStorage(1000, 7, 0);
        long extracted = heatSource.extractHeat(100, false);
        long received = heatTarget.receiveHeat(extracted, false);
        require(heatSource.generateHeatInternal(extracted - received, false) == extracted - received, "Refund bypasses zero external HU input");
        require(heatSource.getHeatStored() + heatTarget.getHeatStored() == 100, "Partial HU acceptance conservation");
        var kineticSource = new KineticStorage(1000, 0, 100, 100, 10000, 0);
        var kineticTarget = new KineticStorage(1000, 7, 0);
        extracted = kineticSource.extractKinetic(100, false);
        received = kineticTarget.receiveKinetic(extracted, false);
        require(kineticSource.generateKineticInternal(extracted - received, false) == extracted - received, "Refund bypasses zero external KU input");
        require(kineticSource.getKineticStored() + kineticTarget.getKineticStored() == 100, "Partial KU acceptance conservation");
        var enormousHeat = new HeatStorage(Long.MAX_VALUE, 0, 0, Long.MAX_VALUE, 20, 1000, .01F);
        var enormousKinetic = new KineticStorage(Long.MAX_VALUE, 0, 0, Long.MAX_VALUE, 10000, .005F);
        require(enormousHeat.getTemperature() == 1000 && enormousKinetic.getRPM() == 10000, "No overflowing full gauge");
        System.out.println("SCEX_HU_KU_STORAGE assertions=" + assertions + " PASS scope=real_storage_and_NBT_world_NOT_RUN");
    }
}
