// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.reactor;

/** Server-thread-confined one-owner lifecycle. World effects remain the caller's responsibility. */
public final class ReactorAccidentLatch {
    public enum State { OPEN, PENDING, DISPATCHING, CLOSED, UNCERTAIN }
    public enum Effect { LOCAL_MACHINE, NUCLEAR_TERRAIN }
    public record Trigger(long finalHeat, long finalCapacity, long gameTime, Effect effect,
                          int explosiveCells, int containmentPlates) {
        public Trigger {
            if (finalCapacity <= 0 || finalHeat < finalCapacity || effect == null)
                throw new IllegalArgumentException("Invalid committed accident trigger");
            if (explosiveCells < 0 || explosiveCells > 216 || containmentPlates < 0 || containmentPlates > 54)
                throw new IllegalArgumentException("Invalid committed accident profile");
        }
        public Trigger(long finalHeat, long finalCapacity, long gameTime, Effect effect) {
            this(finalHeat, finalCapacity, gameTime, effect, 0, 0);
        }
        /** Independent bounded compatibility strength; not an original implementation formula. */
        public float terrainPower() {
            return Math.max(1, Math.min(32, 4 + explosiveCells - containmentPlates));
        }
    }
    public record Saved(State state, Trigger trigger) {
        public Saved {
            if (state == null || (state == State.OPEN) != (trigger == null))
                throw new IllegalArgumentException("Inconsistent accident state");
        }
    }
    private State state = State.OPEN;
    private Trigger trigger;

    /** Call while preparing the owned transaction, before any inventory/balance publication. */
    public static Trigger decide(long finalHeat, long finalCapacity, long gameTime, boolean nuclearTerrainEnabled) {
        return decide(finalHeat, finalCapacity, gameTime, nuclearTerrainEnabled, 0, 0);
    }
    public static Trigger decide(long finalHeat, long finalCapacity, long gameTime, boolean nuclearTerrainEnabled,
                                 int explosiveCells, int containmentPlates) {
        if (finalHeat < 0 || finalCapacity <= 0) throw new IllegalArgumentException("Invalid final thermal state");
        return finalHeat >= finalCapacity
            ? new Trigger(finalHeat, finalCapacity, gameTime,
                          nuclearTerrainEnabled ? Effect.NUCLEAR_TERRAIN : Effect.LOCAL_MACHINE,
                          explosiveCells, containmentPlates)
            : null;
    }
    public boolean mayOperateOrExport() { return state == State.OPEN; }
    public State state() { return state; }
    public Trigger trigger() { return trigger; }

    /** Publish only after the cycle's slots/thermal/fluid state commits. No world callbacks here. */
    public boolean armAfterCommit(Trigger prepared) {
        if (prepared == null || state != State.OPEN) return false;
        trigger = prepared;
        state = State.PENDING;
        return true;
    }
    /** Caller must first verify a supported effect executor and the same live loaded owner. */
    public boolean claimEffect() {
        if (state != State.PENDING) return false;
        state = State.DISPATCHING;
        return true;
    }
    public void closeEffect() {
        if (state != State.DISPATCHING) throw new IllegalStateException("No dispatched effect");
        state = State.CLOSED;
    }
    /** An exception after an external effect began must never silently schedule another explosion. */
    public void markUncertain() {
        if (state != State.DISPATCHING) throw new IllegalStateException("No dispatched effect");
        state = State.UNCERTAIN;
    }
    public Saved snapshot() { return new Saved(state, trigger); }
    public static ReactorAccidentLatch restore(Saved saved) {
        if (saved == null) throw new IllegalArgumentException("Missing accident state");
        var restored = new ReactorAccidentLatch();
        restored.state = saved.state() == State.DISPATCHING ? State.UNCERTAIN : saved.state();
        restored.trigger = saved.trigger();
        return restored;
    }
}
