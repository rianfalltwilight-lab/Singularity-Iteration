// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/** Frozen public multi-domain source observations through the real allocation entry. */
public final class SharedSourceContract {
    private static int assertions;
    private SharedSourceContract() { }
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    private static long[] longs(String text) {
        return Arrays.stream(text.split(",")).mapToLong(Long::parseLong).toArray();
    }
    private static RouteCosts route(int receiver, long loss) {
        return new RouteCosts() {
            @Override public boolean reaches(int contact) { return receiver < 0 || receiver == contact; }
            @Override public long lossMilliTo(int contact) { return loss; }
        };
    }
    private static final class Choice implements RandomGenerator {
        private final int value;
        private int used;
        Choice(int value) { this.value = value; }
        @Override public long nextLong() { throw new AssertionError("Only a bounded domain-order choice expected"); }
        @Override public int nextInt(int bound) {
            check(bound == 2 && used++ == 0, "Exactly one two-domain order choice"); return value;
        }
    }
    private static String key(long debit, long[] credits) { return debit + ":" + Arrays.toString(credits); }
    private static Set<String> possibilities(long reserve, long[] room, long loss, boolean separate, boolean partial) {
        Set<String> result = new HashSet<>();
        for (int choice = 0; choice < 2; choice++) {
            var domains = separate ? List.of(
                new DomainDistributor.Domain(new int[]{0}, List.of(route(0,loss)), new int[][]{{0,1}}),
                new DomainDistributor.Domain(new int[]{0}, List.of(route(1,loss)), new int[][]{{0,1}}))
                : List.of(new DomainDistributor.Domain(new int[]{0}, List.of(route(-1,loss)),
                    new int[][]{choice == 0 ? new int[]{0,1} : new int[]{1,0}}));
            var random = new Choice(choice);
            var round = DomainDistributor.allocate(List.of(new DomainDistributor.Source(reserve,32,partial)),
                domains, new int[]{0,1}, room, random);
            check(random.used == (separate ? 1 : 0), "Only domain permutation consumes this source random stream");
            long budget = partial ? Math.min(32,reserve) : reserve >= 32 ? 32 : 0;
            check(round.debit(0) >= 0 && round.debit(0) <= budget, "Shared source packet budget bound");
            check(round.debit(0) == round.credit(0)+round.credit(1)+round.dissipated(), "Actual receipt conserves energy");
            check(round.credit(0) <= room[0] && round.credit(1) <= room[1], "One source respects both room quotes");
            result.add(key(round.debit(0),new long[]{round.credit(0),round.credit(1)}));
        }
        return result;
    }
    public static void main(String[] args) throws Exception {
        var lines=Files.readAllLines(Path.of(args[0]));check(lines.size()==18721,"Exact frozen observation count");
        Map<String,Set<String>> models=new HashMap<>();
        for (String line:lines.subList(1,lines.size())) {
            String[] f=line.split("\t");check(f.length==8,"Fixture columns");
            String input=String.join("/",f[2],f[3],f[4],f[5]);
            var possible=models.computeIfAbsent(input, ignored -> possibilities(Long.parseLong(f[2]),longs(f[3]),Long.parseLong(f[4]),f[5].equals("1"),true));
            check(possible.contains(key(Long.parseLong(f[6]),longs(f[7]))),"Observed shared-source vector rejected: "+line);
        }
        // Storage-style full-packet eligibility is decided before splitting once;
        // spending part of an eligible packet must not block its remaining debit.
        check(possibilities(31,new long[]{1,40000},0,true,false).equals(Set.of(key(0,new long[]{0,0}))),"Below-threshold storage remains idle");
        check(possibilities(32,new long[]{1,40000},0,true,false).contains(key(32,new long[]{1,31})),"Eligible storage shares the remainder");
        check(possibilities(Long.MAX_VALUE,new long[]{1,40000},1200,true,false).contains(key(32,new long[]{1,29})),"Loss consumes the same bounded packet budget");
        System.out.printf("SCEX_SHARED_SOURCE_CONTRACT rows=18720 assertions=%d PASS merged_graph_receiver_order_NOT_verified%n",assertions);
    }
}
