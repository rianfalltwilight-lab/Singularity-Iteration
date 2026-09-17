// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.processing;

import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/** Server-owned, immutable price generations; stored crystal prices cannot grant authority. */
public final class UuQuoteBook {
    private static final Map<MinecraftServer, Snapshot> SERVERS = new WeakHashMap<>();
    private static long nextGeneration;
    public record Quote(double buckets, long generation) { }
    /** KNOWN_DENIED means quote exclusion only; eligibility is separately observed. */
    public enum Disposition { FINITE, KNOWN_DENIED, UNSUPPORTED, UNAVAILABLE }
    public record Assessment(Disposition disposition, Quote finite, long generation, boolean deniedScanEligible) {
        public Assessment {
            if (disposition == null || (disposition == Disposition.UNAVAILABLE ? generation != -1 : generation <= 0)
                    || (disposition == Disposition.FINITE) != (finite != null)
                    || finite != null && (finite.generation() != generation || !StoredPattern.validCosts(finite.buckets(), 0))
                    || deniedScanEligible && disposition != Disposition.KNOWN_DENIED)
                throw new IllegalArgumentException("Inconsistent UU assessment");
        }
    }
    private record Snapshot(long generation, Map<IndependentUuValueIndex.Key, Double> prices,
                            Set<IndependentUuValueIndex.Key> denied, Set<IndependentUuValueIndex.Key> scanEligible) { }
    private UuQuoteBook() { }

    /** A reviewed recipe/config resolver publishes one bounded generation after reload. */
    public static synchronized long install(MinecraftServer server, IndependentUuValueIndex prices) {
        return install(server, prices, Set.of(), Set.of());
    }
    public static synchronized long install(MinecraftServer server, IndependentUuValueIndex prices,
            Set<IndependentUuValueIndex.Key> denied, Set<IndependentUuValueIndex.Key> scanEligible) {
        if (server == null || !server.isSameThread() || prices == null || denied == null || scanEligible == null)
            throw new IllegalArgumentException("Server-owned bounded prices required");
        var entries=prices.snapshot();
        if(entries.size()>16384)throw new IllegalArgumentException("Price snapshot too large");
        var exclusions = Set.copyOf(denied); var eligible = Set.copyOf(scanEligible);
        if (entries.size() + exclusions.size() > 16384 || !exclusions.containsAll(eligible)
                || exclusions.stream().anyMatch(entries::containsKey))
            throw new IllegalArgumentException("Inconsistent bounded UU generation");
        var snapshot = new Snapshot(Math.incrementExact(nextGeneration), entries, exclusions, eligible);
        nextGeneration = snapshot.generation(); SERVERS.put(server, snapshot); return nextGeneration;
    }
    public static synchronized long generation(MinecraftServer server) {
        if (server == null || !server.isSameThread()) return -1;
        var snapshot = SERVERS.get(server); return snapshot == null ? -1 : snapshot.generation();
    }
    public static synchronized Quote quote(MinecraftServer server, ItemStack item) {
        if (server == null || !server.isSameThread() || item == null || item.isEmpty()) return null;
        var snapshot = SERVERS.get(server); if (snapshot == null) return null;
        var cost = snapshot.prices().get(IndependentUuValueIndex.keyOf(item));
        return cost == null ? null : new Quote(cost, snapshot.generation());
    }
    public static synchronized Assessment classify(MinecraftServer server, ItemStack item) {
        if (server == null || !server.isSameThread()) return new Assessment(Disposition.UNAVAILABLE, null, -1, false);
        var snapshot = SERVERS.get(server);
        if (snapshot == null) return new Assessment(Disposition.UNAVAILABLE, null, -1, false);
        long generation = snapshot.generation();
        if (item == null || item.isEmpty()) return new Assessment(Disposition.UNSUPPORTED, null, generation, false);
        var key = IndependentUuValueIndex.keyOf(item); var cost = snapshot.prices().get(key);
        if (cost != null) return new Assessment(Disposition.FINITE, new Quote(cost, generation), generation, false);
        if (snapshot.denied().contains(key))
            return new Assessment(Disposition.KNOWN_DENIED, null, generation, snapshot.scanEligible().contains(key));
        return new Assessment(Disposition.UNSUPPORTED, null, generation, false);
    }
    public static synchronized void remove(MinecraftServer server) {
        if (server != null && server.isSameThread()) SERVERS.remove(server);
    }
}
