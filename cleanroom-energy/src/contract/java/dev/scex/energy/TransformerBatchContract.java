// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Observed two-face batches plus arithmetic and caller-boundary invariants. */
public final class TransformerBatchContract {
    private static long assertions;
    private TransformerBatchContract() { }
    private static void require(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }
    private static boolean matches(TransformerBatch.Allocation a, long[] f) {
        return a.debit()==f[4] && a.credit(0)==f[5] && a.credit(1)==f[6];
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Missing rejection");
    }
    public static void main(String[] args) throws Exception {
        var lines=Files.readAllLines(Path.of(args[0]),StandardCharsets.UTF_8);
        require(lines.size()==1281,"Frozen observation count");
        int onlyAB=0,onlyBA=0,both=0,unmatched=0;
        for (var line:lines.subList(1,lines.size())) {
            String[] fields=line.split("\t");long[] f=Arrays.stream(fields).skip(1).mapToLong(Long::parseLong).toArray();
            var c=new TransformerAccounting.Configuration(f[0],false);long[] room={f[2],f[3]};
            var ab=TransformerBatch.allocate(c,f[1],room,new int[]{0,1});
            var ba=TransformerBatch.allocate(c,f[1],room,new int[]{1,0});
            boolean a=matches(ab,f),b=matches(ba,f);
            if(a&&b)both++;else if(a)onlyAB++;else if(b)onlyBA++;else unmatched++;
            require(a||b,fields[0]);
        }
        // Exhaust small independent numeric domains; these are arithmetic tests, not reference coverage.
        var c=new TransformerAccounting.Configuration(2,false);
        for(long buffer=0;buffer<=16;buffer++)for(long a=0;a<=9;a++)for(long b=0;b<=9;b++) {
            long[] room={a,b};int[] order={1,0};var result=TransformerBatch.allocate(c,buffer,room,order);
            require(result.debit()==result.credit(0)+result.credit(1),"Conservation");
            require(result.debit()<=Math.min(buffer/2,4)*2 && result.debit()>=0,"Budget");
            require(result.credit(0)>=0 && result.credit(1)>=0,"Nonnegative");
            require(Arrays.equals(room,new long[]{a,b}) && Arrays.equals(order,new int[]{1,0}),"No input mutation");
        }
        rejects(()->TransformerBatch.allocate(c,17,new long[]{1},new int[]{0}));
        rejects(()->TransformerBatch.allocate(c,0,new long[]{-1},new int[]{0}));
        rejects(()->TransformerBatch.allocate(c,0,new long[]{1,1},new int[]{0,0}));
        rejects(()->TransformerBatch.allocate(c,0,new long[]{1},new int[]{1}));
        rejects(()->TransformerBatch.allocate(c,0,new long[]{1},new int[]{}));
        var huge=new TransformerAccounting.Configuration(Long.MAX_VALUE/8,false);
        var edge=TransformerBatch.allocate(huge,huge.capacity(),new long[]{Long.MAX_VALUE},new int[]{0});
        require(edge.debit()==huge.lowPacket()*4,"Large budget without overflow");
        System.out.printf("SCEX_TRANSFORMER_BATCH rows=%d ab_only=%d ba_only=%d both=%d unmatched=%d assertions=%d%n",lines.size()-1,onlyAB,onlyBA,both,unmatched,assertions);
    }
}
