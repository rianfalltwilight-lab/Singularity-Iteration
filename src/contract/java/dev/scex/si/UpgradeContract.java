// SPDX-License-Identifier: Apache-2.0
// SCEX 2026-09-12: independently authored tests of SI's observable API contract.
package dev.scex.si;

import com.singularity_iteration.mio_icif.Items.Upgrade.MachineUpgradeStats;
import com.singularity_iteration.mio_icif.api.energy.ICableTier;
import com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class UpgradeContract {
    private static int assertions;
    private static int failures;
    private static volatile long blackhole;
    private static volatile Object escape;
    private static final com.sun.management.ThreadMXBean MEMORY =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private static void check(boolean result, String label) {
        assertions++;
        if (!result) { failures++; System.out.println("FAIL " + label); }
    }

    private static MachineUpgradeStats stats(int overclockers, int transformers) {
        return new MachineUpgradeStats(overclockers, 0, transformers, 0, 0, 0, 0, false,
                List.of(), List.of(), List.of(), List.of());
    }

    private static CableTier custom(String name, long voltage, int ordinal) {
        return new CableTier(name, name, name, voltage, ordinal, 0, voltage + 1,
                voltage + 1, voltage, 0, voltage, 0, false);
    }

    private static void regression() {
        var builtins = CableTier.allTiers();
        check(builtins.size() == 14, "builtin-count");
        check(builtins == CableTier.allTiers(), "cached-snapshot");
        for (int base = 0; base < builtins.size(); base++) {
            for (int upgrades = 0; upgrades < 20; upgrades++) {
                check(stats(0, upgrades).getEffectiveCableTier(builtins.get(base)) ==
                        builtins.get(Math.min(base + upgrades, builtins.size() - 1)),
                        "builtin-tier-" + base + "-upgrades-" + upgrades);
            }
        }
        // Numeric regression uses SI's frozen public constants, not an IC2 equivalence claim.
        for (int count : new int[] {0, 1, 2, 8, 32, 128, 1024, Integer.MAX_VALUE}) {
            var stats = stats(count, 0);
            double speed = Math.pow(0.7, count);
            // Deliberate r2 behavior change; r1 froze the author's 1.3/ceil design.
            double cost = Math.pow(1.6, count);
            check(Double.doubleToLongBits(stats.getProcessTimeMultiplier()) == Double.doubleToLongBits(speed), "speed-bits-" + count);
            check(Double.doubleToLongBits(stats.getEnergyUsageMultiplier()) == Double.doubleToLongBits(cost), "cost-bits-" + count);
            for (int ticks : new int[] {1, 20, 200, Integer.MAX_VALUE}) {
                double duration = Math.max(1, ticks) * speed;
                int batch = Math.max(1, Math.min(64, (int) Math.ceil(1.0 / duration)));
                check(stats.getProcessTicks(ticks) == Math.max(1, Math.round(duration * batch)), "ticks-" + count + "-" + ticks);
            }
            for (long eu : new long[] {1, 32, 2048, Long.MAX_VALUE}) {
                check(stats.getEnergyPerTick(eu) == Math.max(1, Math.round(eu * cost)), "energy-" + count + "-" + eu);
            }
        }
        var empty = MachineUpgradeStats.empty();
        check(empty.getOverclockerCount() == 0 && empty.getTransformerCount() == 0, "empty-statistics");
        check(empty.getEjectorDirections().isEmpty() && empty.getPullingDirections().isEmpty(), "empty-directions");
        check(stats(0, 0).getProcessTicks(200) == 200 && empty.getEnergyPerTick(32) == 32, "empty-has-no-effects");
        var low = custom("contract_below_lv", 16, 100);
        var middle = custom("contract_between_lv_mv", 64, 101);
        CableTier.addTier(low);
        CableTier.addTier(middle);
        var after = CableTier.allTiers();
        check(CableTier.getTier(low.name) == low && CableTier.getTier(middle.name) == middle, "registered-lookup");
        check(after.size() == 16 && after.contains(low) && after.contains(middle), "registered-enumeration");
        check(builtins.size() == 14, "previous-snapshot-unchanged");
        for (int i = 1; i < after.size(); i++) check(after.get(i - 1).powerRating < after.get(i).powerRating, "strictly-sorted-" + i);
        try { after.clear(); check(false, "immutable-snapshot"); }
        catch (UnsupportedOperationException expected) { check(true, "immutable-snapshot"); }
        for (var tier : List.of(CableTier.LV, CableTier.MV, CableTier.HV, low, middle)) {
            check(stats(0, 0).getEffectiveCableTier(tier) == tier, "zero-upgrade-preserves-" + tier.name);
        }
        // SI extension policy: each upgrade selects the next registered voltage.
        check(stats(0, 1).getEffectiveCableTier(low) == CableTier.LV, "custom-low-upgrade");
        check(stats(0, 1).getEffectiveCableTier(CableTier.LV) == middle, "inserted-tier-upgrade");
        check(stats(0, 2).getEffectiveCableTier(CableTier.LV) == CableTier.MV, "inserted-tier-second-upgrade");
        check(stats(0, Integer.MAX_VALUE).getEffectiveCableTier(CableTier.MV) == CableTier.MAX, "upgrade-count-no-overflow");
        try { CableTier.addTier(custom(low.name, 17, 102)); check(false, "duplicate-name-rejected"); }
        catch (IllegalArgumentException expected) { check(true, "duplicate-name-rejected"); }
        try { CableTier.addTier(custom("contract_voltage_duplicate", 16, 102)); check(false, "duplicate-voltage-rejected"); }
        catch (IllegalArgumentException expected) { check(true, "duplicate-voltage-rejected"); }
        check(CableTier.allTiers() == after, "rejected-registration-does-not-invalidate");
        System.out.printf(Locale.ROOT, "SI_CONTRACT assertions=%d failed=%d%n", assertions, failures);
        if (failures != 0) throw new AssertionError("SI contract failures: " + failures);
    }

    private static long work(MachineUpgradeStats[] values, int loops) {
        long result = 0;
        for (int i = 0; i < loops; i++) {
            var value = values[i & (values.length - 1)];
            result += value.getProcessTicks(200) + value.getEnergyPerTick(32);
            result ^= Double.doubleToLongBits(value.getProcessTimeMultiplier());
            result += Double.doubleToLongBits(value.getEnergyUsageMultiplier());
        }
        return result;
    }

    private static void benchmark() {
        var values = new MachineUpgradeStats[64];
        for (int i = 0; i < values.length; i++) values[i] = stats(i % 24, i % 4);
        for (int i = 0; i < 10; i++) blackhole = work(values, 200_000);
        long[] elapsed = new long[21];
        long allocated = 0;
        for (int sample = 0; sample < elapsed.length; sample++) {
            long bytes = MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId());
            long start = System.nanoTime();
            blackhole = work(values, 1_000_000);
            elapsed[sample] = System.nanoTime() - start;
            allocated += MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes;
        }
        Arrays.sort(elapsed);
        System.out.printf(Locale.ROOT, "SI_BENCH workload=upgrade-getters iterations=1000000 samples=21 p50_ns=%d p95_ns=%d max_ns=%d allocated_bytes=%d checksum=%d%n",
                elapsed[10], elapsed[19], elapsed[20], allocated, blackhole);
        for (int i = 0; i < 200_000; i++) escape = MachineUpgradeStats.empty();
        long bytes = MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long start = System.nanoTime();
        for (int i = 0; i < 1_000_000; i++) escape = MachineUpgradeStats.empty();
        System.out.printf(Locale.ROOT, "SI_BENCH workload=empty-escaping iterations=1000000 ns=%d allocated_bytes=%d%n",
                System.nanoTime() - start, MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes);
        // Include creation cost, where cached multipliers are computed once.
        for (int i = 0; i < 200_000; i++) {
            var value = stats(i & 15, 0);
            escape = value;
            blackhole = value.getProcessTicks(200) + value.getEnergyPerTick(32);
        }
        bytes = MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId());
        start = System.nanoTime();
        long total = 0;
        for (int i = 0; i < 1_000_000; i++) {
            var value = stats(i & 15, 0);
            escape = value;
            total += value.getProcessTicks(200) + value.getEnergyPerTick(32);
        }
        blackhole = total;
        System.out.printf(Locale.ROOT, "SI_BENCH workload=construct-and-read iterations=1000000 ns=%d allocated_bytes=%d checksum=%d%n",
                System.nanoTime() - start, MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes, total);
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("benchmark")) benchmark();
        else regression();
    }
}
