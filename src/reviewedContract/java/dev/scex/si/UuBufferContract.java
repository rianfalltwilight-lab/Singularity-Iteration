// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.JsonParser;
import dev.scex.si.processing.IndependentUuBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;

/** Oracle rows come from R94 real original IC2 saves, not from this implementation. */
public final class UuBufferContract {
    private static int assertions;
    private static void require(boolean value, String why) {
        assertions++; if (!value) throw new AssertionError(why);
    }
    private static final class Tank implements IndependentUuBuffer.FluidPort {
        int amount, maximum = Integer.MAX_VALUE, calls;
        Runnable callback = () -> {};
        Tank(int amount) { this.amount = amount; }
        public int availableMilliBuckets() { return amount; }
        public int drainMilliBuckets(int requested) {
            calls++; callback.run();
            int taken = Math.min(requested, Math.min(maximum, amount)); amount -= taken; return taken;
        }
    }
    private static IndependentUuBuffer buffer() { return new IndependentUuBuffer(() -> true, () -> {}); }
    private static CompoundTag state(double credit) {
        var tag = new CompoundTag(); tag.putInt("version", 1); tag.putDouble("credit_buckets", credit);
        tag.putInt("pending_millibuckets", 0); tag.putBoolean("uncertain", false); return tag;
    }
    private static void referenceReplay(Path fixture) throws Exception {
        int steps = 0;
        try (var reader = Files.newBufferedReader(fixture)) {
            var cases = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("cases");
            require(cases.size() == 4, "Both reference items before and after real restart");
            for (var entry : cases) {
                var c = entry.getAsJsonObject(); var account = buffer();
                require(account.load(state(c.get("initialCredit").getAsDouble())), "Initial observed credit loads");
                var tank = new Tank(c.get("initialTank").getAsInt());
                for (var payment : c.getAsJsonArray("payments")) {
                    var row = payment.getAsJsonObject();
                    String at = c.get("label").getAsString() + " tick " + row.get("tick").getAsInt();
                    require(account.consume(row.get("costBuckets").getAsDouble(), tank), "Pay reference work step: " + at);
                    require(tank.amount == row.get("tankMilliBuckets").getAsInt(), "Exact integer tank debit: " + at);
                    require(Math.abs(account.creditBuckets() - row.get("creditBuckets").getAsDouble()) < 1e-15, "Reference fractional remainder: " + at);
                    if (++steps % 17 == 0) {
                        var saved = account.save(); var reloaded = buffer();
                        require(reloaded.load(saved) && reloaded.save().equals(saved), "Owned account save/load preserves its complete state");
                        account = reloaded;
                    }
                }
            }
        }
        require(steps == 1280, "All observed original work steps replayed");
    }
    private static void edgeCases() {
        var account = buffer(); var tank = new Tank(10);
        for (double bad : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Integer.MAX_VALUE})
            require(!account.consume(bad, tank) && tank.calls == 0, "Reject invalid amounts before touching fluid");
        var client = new IndependentUuBuffer(() -> false, () -> {});
        require(!client.consume(.00015, tank) && !client.load(state(.001)) && tank.calls == 0, "Server authority is required");
        var insufficient = new Tank(0);
        require(!account.consume(.00015, insufficient) && insufficient.calls == 0, "No drain with insufficient whole tank units");
        require(account.consume(.00015, tank) && tank.amount == 9, "Sub-mB price withdraws one whole tank unit");
        require(Math.abs(account.creditBuckets() - .00085) < 1e-18, "The excess is owned credit");
        int calls = tank.calls;
        require(account.consume(.00015, tank) && tank.calls == calls, "Reuse credit without an external drain");

        account = buffer(); tank = new Tank(10); tank.maximum = 1;
        require(!account.consume(.0015, tank) && tank.amount == 9 && account.creditBuckets() == .001, "Partial drain is retained without claiming payment");
        require(account.consume(.0015, tank) && tank.amount == 8 && account.creditBuckets() == .0005, "Retry requests only the missing amount");
        account.load(state(1)); calls = tank.calls;
        require(!account.consume(Double.MIN_VALUE, tank) && account.creditBuckets() == 1 && tank.calls == calls, "Positive unrepresentable subtraction cannot mint free outputs");

        var reentrant = buffer(); var reentrantTank = new Tank(2);
        reentrantTank.callback = () -> {
            require(!reentrant.consume(.00015, reentrantTank), "No recursive payment");
            require(!reentrant.load(state(100)), "No account replacement inside a debit callback");
            require(reentrant.save().getInt("pending_millibuckets") == 1, "Save retains outstanding debit intent");
        };
        require(reentrant.consume(.00015, reentrantTank) && reentrantTank.amount == 1, "Outer payment occurs once");

        var ambiguous = buffer(); var failing = new Tank(2);
        failing.callback = () -> { throw new IllegalStateException("Receipt lost"); };
        require(!ambiguous.consume(.00015, failing) && ambiguous.blocked(), "Ambiguous debit is held");
        var saved = ambiguous.save(); var loaded = buffer();
        require(!loaded.load(saved) && loaded.blocked() && loaded.pendingMilliBuckets() == 1, "Ambiguity persists across reload");
        require(!loaded.consume(.00015, failing) && failing.calls == 1, "A held debit is never replayed");
        for (int invalid : new int[]{-1, 2}) {
            var bad = buffer();
            require(!bad.consume(.00015, new IndependentUuBuffer.FluidPort() {
                public int availableMilliBuckets() { return 10; }
                public int drainMilliBuckets(int ignored) { return invalid; }
            }) && bad.blocked(), "Invalid external receipt is held");
        }
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
            var bad = buffer(); var raw = state(invalid);
            require(!bad.load(raw) && bad.blocked() && bad.save().equals(raw), "Malformed credit preserved verbatim");
        }
        var future = state(.00085); future.putInt("version", 99); future.putString("future_field", "preserve");
        var futureAccount = buffer(); require(!futureAccount.load(future) && futureAccount.save().equals(future), "Future state retained");
        future.putDouble("credit_buckets", 0);
        require(futureAccount.save().getDouble("credit_buckets") == .00085, "Own retained data is isolated from caller mutation");
        var writes = new java.util.ArrayList<CompoundTag>();
        var boundary = new IndependentUuBuffer[1];
        boundary[0] = new IndependentUuBuffer(() -> true, () -> writes.add(boundary[0].save()));
        boundary[0].load(state(.0005));
        require(boundary[0].consume(Integer.MAX_VALUE / 1000.0, new Tank(Integer.MAX_VALUE)), "Largest drain retains its fractional overpayment");
        for (var snapshot : writes) if (snapshot.getInt("pending_millibuckets") == 0)
            require(buffer().load(snapshot), "A credited intermediate save also remains loadable at the amount limit");
    }
    public static void main(String[] args) throws Exception {
        require(Path.of(IndependentUuBuffer.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
            .equals(Path.of(args[0]).toRealPath()), "Actual compiled candidate is loaded");
        referenceReplay(Path.of(args[1])); edgeCases();
        System.out.println("SCEX_UU_BUFFER assertions=" + assertions + " reference_steps=1280 PASS scope=numerical_account_no_si_machine_world");
    }
}
