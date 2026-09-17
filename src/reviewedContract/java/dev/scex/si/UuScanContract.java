// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import dev.scex.si.processing.IndependentScanSession;
import dev.scex.si.processing.IndependentUuValueIndex;
import java.nio.file.Path;
import java.util.ArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/** Candidate classes with vanilla registries, not a Minecraft world run. */
public final class UuScanContract {
    private static int assertions;
    private static final HolderLookup.Provider REGISTRIES = HolderLookup.Provider.create(java.util.stream.Stream.empty());
    private UuScanContract() { }
    private static void require(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
    private static ItemStack tagged(String value) {
        var item = new ItemStack(Items.DIAMOND);
        var data = new CompoundTag(); data.putString("value", value);
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return item;
    }
    private static void collision() {
        var first = tagged("Aa"); var second = tagged("BB");
        require(first.getComponents().hashCode() == second.getComponents().hashCode(), "Fixture really collides");
        require(!ItemStack.isSameItemSameComponents(first, second), "Fixture contains different components");
        var index = new IndependentUuValueIndex(2);
        require(index.register(first, 0.25), "Register first identity");
        require(index.lookup(second).isEmpty(), "Hash collision must not grant another pattern its UU value");
        require(index.register(second, 0.5) && index.size() == 2, "Colliding identities occupy different entries");
        require(index.lookup(first).orElseThrow() == 0.25 && index.lookup(second).orElseThrow() == 0.5, "Both costs remain distinct");
        require(!index.register(tagged("third"), 1), "New entries respect the bound");
        require(index.register(first, 0.75) && index.size() == 2, "An existing entry can update at capacity");
        var original = first.copy(); first.set(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        require(index.lookup(first).isEmpty() && index.lookup(original).orElseThrow() == 0.75, "Caller mutation cannot replace the stored identity");
        require(index.lookup(original.copyWithCount(64)).orElseThrow() == 0.75, "Lookup count does not change one-item cost");
        require(!index.register(original.copyWithCount(2), 1), "Registration requires one item");
        for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE / 1000.0})
            require(!index.register(original, invalid) && index.lookup(original).orElseThrow() == 0.75, "Invalid cost leaves earlier entry intact");
        var legacy = new IndependentUuValueIndex(1);
        legacy.register(new IndependentUuValueIndex.Key("minecraft:diamond", original.getComponents().hashCode()), 99);
        require(legacy.lookup(original).isEmpty(), "Legacy explicit hash keys cannot grant concrete item values");
        try { index.snapshot().clear(); throw new AssertionError("Mutable snapshot"); }
        catch (UnsupportedOperationException expected) { require(index.size() == 2, "Snapshot is read-only"); }
    }
    private static final class Energy implements IndependentScanSession.EnergyPort {
        long balance = 100, spent;
        long maximum = Long.MAX_VALUE;
        int calls;
        Runnable during = () -> {};
        public long available() { return balance; }
        public long consume(long amount) {
            calls++; during.run();
            long paid = Math.min(amount, Math.min(balance, maximum));
            balance -= paid; spent += paid; return paid;
        }
    }
    private static IndependentScanSession scan(int ticks) { return new IndependentScanSession(() -> true, ticks, 10); }
    private static void persistence() {
        var empty = scan(3); var idle = empty.save(REGISTRIES); empty.load(idle, REGISTRIES);
        require(empty.state() == IndependentScanSession.State.IDLE && empty.save(REGISTRIES).equals(idle), "Empty session round-trips");
        var item = tagged("persist"); var scan = scan(3); var energy = new Energy();
        require(scan.begin(item, 0.125, 30), "Start component-backed session");
        item.setCount(2);
        require(scan.sourceStack().getCount() == 1, "Admission takes an owned copy");
        scan.sourceStack().set(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        require(ItemStack.matches(scan.sourceStack(), tagged("persist")), "Getter cannot mutate owned scan input");
        require(scan.tick(100, energy) == 10, "First paid work tick");
        var saved = scan.save(REGISTRIES); var resumed = scan(3); resumed.load(saved, REGISTRIES);
        require(resumed.progress() == 1 && resumed.state() == IndependentScanSession.State.SCANNING, "Progress survives reload");
        require(ItemStack.matches(resumed.sourceStack(), tagged("persist")), "Reload retains components and one-item identity");
        require(resumed.tick(100, energy) == 0 && energy.calls == 1, "Same saved tick cannot debit twice");
        require(resumed.tick(1, energy) == 10, "A backwards clock does not stall forever");
        require(resumed.tick(2, energy) == 10 && energy.spent == 30, "Total work payment remains exact across reload");
        saved = resumed.save(REGISTRIES); var complete = scan(3); complete.load(saved, REGISTRIES);
        require(complete.state() == IndependentScanSession.State.COMPLETED, "Completed result survives reload");
        require(complete.takeResult() == null, "An item-backed result cannot be flattened to registry ID");
        var pattern = complete.takeStoredPattern();
        require(pattern != null && pattern.sameItem(tagged("persist")) && pattern.energy() == 30 && pattern.buckets() == 0.125, "Claim exact completed pattern");
        complete.load(complete.save(REGISTRIES), REGISTRIES);
        require(complete.state() == IndependentScanSession.State.IDLE && complete.takeStoredPattern() == null, "Claimed result is not replayed after save");
        require(complete.begin(tagged("next"), 1, 0), "Claimed session can accept the next input");
        try { complete.save(); throw new AssertionError("Component-losing save was accepted"); }
        catch (IllegalStateException expected) { require(complete.state() == IndependentScanSession.State.SCANNING, "Machine saves require registries"); }
    }
    private static void partialPayment() {
        var scan = scan(2); scan.begin(tagged("partial"), 1, 20); var energy = new Energy(); energy.maximum = 3;
        require(scan.tick(1, energy) == 3 && scan.progress() == 0 && scan.paidTick() == 3, "Partial debit is reported and retained as paid work");
        var resumed = scan(2); resumed.load(scan.save(REGISTRIES), REGISTRIES);
        require(resumed.paidTick() == 3 && resumed.tick(1, energy) == 0, "Partial work survives reload without recharging same tick");
        energy.maximum = 100;
        require(resumed.tick(2, energy) == 7 && resumed.progress() == 1 && energy.spent == 10, "Pay only the remainder of a partial work tick");
        energy.balance = 0; int calls = energy.calls;
        require(resumed.tick(3, energy) == 0 && resumed.state() == IndependentScanSession.State.WAITING_ENERGY && energy.calls == calls, "Insufficient balance never invokes debit");
        energy.balance = 10;
        require(resumed.tick(4, energy) == 10 && energy.spent == 20 && resumed.state() == IndependentScanSession.State.COMPLETED, "Wait resumes with exact total payment");
    }
    private static void malformedData() {
        var session = scan(2); session.begin(tagged("retained"), 1, 20);
        var good = session.save(REGISTRIES);
        var variants = new ArrayList<CompoundTag>();
        var tag = good.copy(); tag.putInt("scex_scan_version", 1); variants.add(tag);
        tag = good.copy(); tag.putString("unknown", "retain"); variants.add(tag);
        tag = good.copy(); tag.putInt("total_ticks", 9); variants.add(tag);
        tag = good.copy(); tag.putInt("energy_per_tick", 10); variants.add(tag);
        tag = good.copy(); tag.putLong("paid_tick", 10); variants.add(tag);
        tag = good.copy(); tag.putDouble("uu_buckets", Double.NaN); variants.add(tag);
        tag = good.copy(); tag.putInt("state", IndependentScanSession.State.COMPLETED.ordinal()); variants.add(tag);
        tag = good.copy(); tag.putInt("progress", 2); variants.add(tag);
        tag = good.copy(); tag.remove("item"); variants.add(tag);
        tag = good.copy(); tag.getCompound("item").putString("id", "unavailable:missing"); variants.add(tag);
        tag = good.copy(); tag.getCompound("item").putInt("count", 2); variants.add(tag);
        tag = good.copy(); tag.putString("item_key", "minecraft:stone"); variants.add(tag);
        for (var invalid : variants) {
            var loaded = scan(2); loaded.load(invalid, REGISTRIES); var energy = new Energy();
            require(loaded.state() == IndependentScanSession.State.FAILED && loaded.save(REGISTRIES).equals(invalid), "Invalid payload is retained without normalization");
            require(!loaded.begin(tagged("overwrite"), 1, 20) && loaded.tick(5, energy) == 0 && energy.calls == 0, "Unrecognized scan cannot be overwritten or resumed");
            var copy = loaded.save(REGISTRIES); copy.putString("mutated", "copy");
            require(loaded.save(REGISTRIES).equals(invalid), "Held raw data is detached");
        }
    }
    private static void failedAndReentrantPayment() {
        for (int kind = 0; kind < 3; kind++) {
            final int failureKind = kind; var scan = scan(2); scan.begin(tagged("uncertain"), 1, 20); int[] calls = {0};
            var during = new ArrayList<CompoundTag>();
            var port = new IndependentScanSession.EnergyPort() {
                public long available() { return 100; }
                public long consume(long amount) {
                    calls[0]++; during.add(scan.save(REGISTRIES));
                    scan.cancel(); scan.load(new CompoundTag(), REGISTRIES);
                    require(scan.tick(99, this) == 0 && !scan.begin(tagged("reenter"), 1, 20), "Callbacks cannot reenter or replace an in-flight scan");
                    if (failureKind == 2) throw new IllegalStateException("May already have debited");
                    return failureKind == 0 ? -1 : amount + 1;
                }
            };
            require(scan.tick(1, port) == 0 && scan.state() == IndependentScanSession.State.FAILED && scan.pendingPayment() == 10, "Unknown debit is held");
            require(scan.tick(2, port) == 0 && calls[0] == 1, "Failed external debit is not replayed");
            during.add(scan.save(REGISTRIES));
            for (var saved : during) {
                var resumed = scan(2); resumed.load(saved, REGISTRIES);
                require(resumed.state() == IndependentScanSession.State.FAILED && resumed.pendingPayment() == 10, "Intent snapshot resumes held");
                require(resumed.tick(3, port) == 0 && calls[0] == 1 && !resumed.begin(tagged("new"), 1, 20), "Reload cannot retry or replace unknown payment");
            }
        }
        var scan = scan(1); scan.begin(tagged("normal callback"), 1, 10); var energy = new Energy();
        energy.during = () -> require(scan.tick(2, energy) == 0, "Successful callbacks also reject reentry");
        require(scan.tick(1, energy) == 10 && energy.calls == 1 && scan.takeStoredPattern() != null, "Successful debit produces one result");
    }
    private static void authority() {
        boolean[] server = {true}; var scan = new IndependentScanSession(() -> server[0], 1, 10); var energy = new Energy();
        scan.begin(tagged("authority"), 1, 10); var before = scan.save(REGISTRIES); server[0] = false;
        scan.cancel(); scan.load(new CompoundTag(), REGISTRIES);
        require(scan.tick(1, energy) == 0 && !scan.begin(tagged("other"), 1, 10) && scan.save(REGISTRIES).equals(before), "Non-server mutation rejected");
        server[0] = true; scan.tick(1, energy); server[0] = false;
        require(scan.takeStoredPattern() == null && scan.state() == IndependentScanSession.State.COMPLETED, "Only server can claim a result");
        server[0] = true;
        require(scan.takeStoredPattern() != null && scan.takeStoredPattern() == null, "Server claims once");
        require(scan.begin(tagged("cancel"), 1, 10), "New scan after claim"); scan.cancel();
        require(scan.state() == IndependentScanSession.State.CANCELLED && scan.tick(2, energy) == 0 && scan.takeStoredPattern() == null, "Cancellation never awards an unfinished result");
        var resumed = scan(1); resumed.load(scan.save(REGISTRIES), REGISTRIES);
        require(resumed.state() == IndependentScanSession.State.CANCELLED && resumed.begin(tagged("fresh"), 1, 10), "Cancelled scan persists and can restart deliberately");
    }
    private static void completionOwnership() {
        var session = new IndependentScanSession(() -> true, 1, 10);
        require(session.begin(tagged("saved"), 0.25, 10), "Start item scan");
        session.tick(1, new IndependentScanSession.EnergyPort() {
            public long available() { return 10; }
            public long consume(long amount) { return amount; }
        });
        require(!session.begin(tagged("replacement"), 0.5, 10), "A new scan cannot overwrite an unclaimed completed result");
        require(session.takeStoredPattern().sameItem(tagged("saved")), "Retain original completed item components");
    }
    public static void main(String[] args) throws Exception {
        net.neoforged.fml.loading.LoadingModList.of(java.util.List.of(), java.util.List.of(),
            java.util.List.of(), java.util.List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (var type : new Class<?>[]{IndependentScanSession.class, IndependentUuValueIndex.class}) {
            require(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
                .equals(Path.of(args[0]).toRealPath()), "Load compiled candidate classes");
        }
        var failures = new ArrayList<String>();
        try { collision(); } catch (AssertionError | RuntimeException failure) { failures.add("collision: " + failure); }
        try { completionOwnership(); } catch (AssertionError | RuntimeException failure) { failures.add("completion: " + failure); }
        try { persistence(); } catch (AssertionError | RuntimeException failure) { failures.add("persistence: " + failure); }
        try { partialPayment(); } catch (AssertionError | RuntimeException failure) { failures.add("partial payment: " + failure); }
        try { malformedData(); } catch (AssertionError | RuntimeException failure) { failures.add("malformed data: " + failure); }
        try { failedAndReentrantPayment(); } catch (AssertionError | RuntimeException failure) { failures.add("payment failure: " + failure); }
        try { authority(); } catch (AssertionError | RuntimeException failure) { failures.add("authority: " + failure); }
        for (var failure : failures) System.out.println("SCEX_UU_SCAN_FAILURE " + failure);
        if (!failures.isEmpty()) throw new AssertionError("UU regressions: " + failures.size());
        System.out.println("SCEX_UU_SCAN assertions=" + assertions + " PASS scope=independent_components_no_world");
    }
}
