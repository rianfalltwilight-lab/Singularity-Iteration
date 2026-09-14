// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/** Finite, public two-contact observations through the explicit shared-contact entry. */
public final class ContactBudgetContract {
    private static int assertions;
    private ContactBudgetContract() { }
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    private static long[] longs(String text) { return Arrays.stream(text.split(",")).mapToLong(Long::parseLong).toArray(); }
    private static String key(long debit, long[] credits) { return debit+":"+Arrays.toString(credits); }
    private static RouteCosts route(int root, long milli) {
        return new RouteCosts() {
            @Override public boolean reaches(int contact) { return contact==0 || contact==1; }
            @Override public long lossMilliTo(int contact) { return milli*(root==contact ? 1 : 7); }
        };
    }
    private static final class Choice implements RandomGenerator {
        private final int first;
        private int used;
        Choice(int first) { this.first=first; }
        @Override public long nextLong() { throw new AssertionError("Only one source-contact start choice expected"); }
        @Override public int nextInt(int bound) { check(bound==2 && used++==0,"Two offering contact entries");return first; }
    }
    private static Map<String,Integer> possibilities(long reserve,long[] rooms,long milli,boolean fixed) {
        var result=new HashMap<String,Integer>();int receiverChoices=fixed ? 1 : 2;
        for (int first=0;first<2;first++) for (int left=0;left<receiverChoices;left++) for (int right=0;right<receiverChoices;right++) {
            int[][] order={left==0 ? new int[]{0,1} : new int[]{1,0}, right==0 ? new int[]{1,0} : new int[]{0,1}};
            var domain=DomainDistributor.Domain.withSharedSourceContacts(new int[]{0,0},List.of(route(0,milli),route(1,milli)),order);
            var random=new Choice(first);
            var round=DomainDistributor.allocate(List.of(new DomainDistributor.Source(reserve,32,true)),List.of(domain),new int[]{0,1},rooms,random);
            check(random.used==1,"Contact start is selected once in the domain");
            check(round.sourceCount()==1 && round.receiverCount()==2,"Two entries still debit only one physical source");
            check(round.debit(0)<=Math.min(reserve,32),"Shared contact packet bound");
            check(round.debit(0)==round.credit(0)+round.credit(1)+round.dissipated(),"Actual shared-contact receipt conserves energy");
            result.merge(key(round.debit(0),new long[]{round.credit(0),round.credit(1)}),1,Integer::sum);
        }
        return result;
    }
    public static void main(String[] args) throws Exception {
        var lines=Files.readAllLines(Path.of(args[0]));check(lines.size()==3841,"Exact valid first-tick reference count");
        var models=new HashMap<String,Map<String,Integer>>();var observations=new HashMap<String,Map<String,Integer>>();
        var weights=new HashMap<String,Map<String,Integer>>();
        for (String line:lines.subList(1,lines.size())) {
            String[] f=line.split("\t");check(f.length==7,"Fixture columns");boolean fixed=Long.parseLong(f[1])%4==0;
            String signature=f[2]+"/"+f[3]+"/"+f[4]+"/"+fixed;
            var possible=models.computeIfAbsent(signature, ignored -> possibilities(Long.parseLong(f[2]),longs(f[3]),Long.parseLong(f[4]),fixed));
            String vector=key(Long.parseLong(f[5]),longs(f[6]));check(possible.containsKey(vector),"Observed contact vector rejected: "+line);
            String group=f[0]+"/"+fixed;observations.computeIfAbsent(group,ignored -> new HashMap<>()).merge(vector,1,Integer::sum);weights.put(group,possible);
        }
        for (var entry:observations.entrySet()) {
            var possible=weights.get(entry.getKey());int n=entry.getValue().values().stream().mapToInt(Integer::intValue).sum();
            int total=possible.values().stream().mapToInt(Integer::intValue).sum();
            for (var vector:possible.entrySet()) {
                double p=(double)vector.getValue()/total,tolerance=Math.max(3,6*Math.sqrt(n*p*(1-p)));
                check(Math.abs(entry.getValue().getOrDefault(vector.getKey(),0)-n*p)<=tolerance,"Finite contact distribution guard: "+entry.getKey());
            }
        }
        var duplicate=new DomainDistributor.Domain(new int[]{0,0},List.of(route(0,200),route(1,200)),new int[][]{{0,1},{1,0}});
        try {
            DomainDistributor.allocate(List.of(new DomainDistributor.Source(32,32,true)),List.of(duplicate),new int[]{0,1},new long[]{31,31},new Choice(0));
            throw new AssertionError("Ordinary domain accepted accidental duplicate source IDs");
        } catch (IllegalArgumentException expected) { assertions++; }
        System.out.printf("SCEX_CONTACT_BUDGET_CONTRACT rows=3840 assertions=%d PASS three_contacts_merge_history_and_PRNG_NOT_verified%n",assertions);
    }
}
