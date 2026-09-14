// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Two distinct observed loss paths; supplied receiver order, no scheduler claim. */
public final class TransformerSplitLossContract {
    private static long assertions;
    private TransformerSplitLossContract() { }
    private static void require(boolean value,String label) {
        assertions++;if(!value)throw new AssertionError(label);
    }
    private static boolean matches(TransformerBatch.Allocation a,long[] f) {
        return a.debit()==f[4] && a.credit(0)==f[5] && a.credit(1)==f[6];
    }
    private static void invariants(TransformerBatch.Allocation a,long packet) {
        long debit=0,credit=0,loss=0;
        for(var d:a.deliveries()) {
            require(d.sourceDebit()<=packet,"Trace retains individual packet bound");
            require(d.sourceDebit()==d.credit()+d.pathLoss(),"Packet conservation");
            debit+=d.sourceDebit();credit+=d.credit();loss+=d.pathLoss();
        }
        require(debit==a.debit() && credit==a.credit(0)+a.credit(1) && loss==a.dissipated(),"Trace reconciles");
        require(a.debit()==a.credit(0)+a.credit(1)+a.dissipated(),"Batch conservation");
    }
    public static void main(String[] args) throws Exception {
        var rows=Files.readAllLines(Path.of(args[0]),StandardCharsets.UTF_8);require(rows.size()==641,"Frozen observation count");
        int abOnly=0,baOnly=0,both=0;
        for(var line:rows.subList(1,rows.size())) {
            var fields=line.split("\t");long[] f=Arrays.stream(fields).skip(1).mapToLong(Long::parseLong).toArray();
            var config=new TransformerAccounting.Configuration(f[0],false);long[] room={f[2],f[3]};
            var ab=TransformerBatch.allocate(config,f[1],room,new int[]{0,1},new long[]{1,2});
            var ba=TransformerBatch.allocate(config,f[1],room,new int[]{1,0},new long[]{1,2});
            boolean a=matches(ab,f),b=matches(ba,f);require(a||b,fields[0]);
            if(a&&b)both++;else if(a)abOnly++;else baOnly++;
            invariants(ab,config.outputPacket());invariants(ba,config.outputPacket());
        }
        var config=new TransformerAccounting.Configuration(32,false);
        for(long buffer=0;buffer<=256;buffer++)for(long room=0;room<=35;room++) {
            var a=TransformerBatch.allocate(config,buffer,new long[]{room,1000},new int[]{0,1},new long[]{1,2});
            invariants(a,32);require(a.debit()<=buffer,"Reserve bound");
        }
        var residual=TransformerBatch.allocate(config,256,new long[]{30,1000},new int[]{0,1},new long[]{1,2});
        require(residual.debit()==127 && residual.credit(0)==30 && residual.credit(1)==90 && residual.dissipated()==7,"Unproductive tail retained");
        var skipped=TransformerBatch.allocate(config,256,new long[]{1000,1000},new int[]{0,1},new long[]{Long.MAX_VALUE,2});
        require(skipped.credit(0)==0 && skipped.credit(1)==120,"Unproductive route does not suppress other receiver");
        System.out.printf("SCEX_TRANSFORMER_SPLIT_LOSS rows=%d ab_only=%d ba_only=%d both=%d assertions=%d PASS%n",rows.size()-1,abOnly,baOnly,both,assertions);
    }
}
