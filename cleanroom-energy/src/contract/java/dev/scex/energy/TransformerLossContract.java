// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** Frozen single-output-path loss observations; no multi-path routing claim. */
public final class TransformerLossContract {
    private static long assertions;
    private TransformerLossContract() { }
    private static void require(boolean value,String label) {
        assertions++;
        if(!value)throw new AssertionError(label);
    }
    public static void main(String[] args) throws Exception {
        var rows=Files.readAllLines(Path.of(args[0]),StandardCharsets.UTF_8);
        require(rows.size()==1441,"Frozen row count");
        for(var line:rows.subList(1,rows.size())) {
            var f=line.split("\t");require(f.length==8,"Row dimensions");
            var c=new TransformerAccounting.Configuration(Long.parseLong(f[1]),false);
            var before=new TransformerAccounting.State(0,Long.parseLong(f[4]),Long.parseLong(f[5]));
            var expected=new TransformerAccounting.State(0,Long.parseLong(f[6]),Long.parseLong(f[7]));
            for(var order:TransformerAccounting.Order.values()) {
                var step=TransformerAccounting.advance(c,before,Long.parseLong(f[2]),40000000,true,order,Long.parseLong(f[3]));
                require(step.after().equals(expected),f[0]);
                require(before.buffer()+before.receiver()==step.after().buffer()+step.after().receiver()+step.dissipated(),"Conservation with loss");
                require(step.dissipated()>=0 && step.sourceDebit()==0,"Loss and source bound");
            }
        }
        var c=new TransformerAccounting.Configuration(32,false);
        // Arithmetic contract: output losses also release physical buffer capacity.
        var before=new TransformerAccounting.State(128,256,0);
        var out=TransformerAccounting.advance(c,before,128,40000000,true,TransformerAccounting.Order.OUTPUT_FIRST,1);
        require(out.after().equals(new TransformerAccounting.State(0,256,124)) && out.dissipated()==4,"Output-debit capacity release");
        var in=TransformerAccounting.advance(c,before,128,40000000,true,TransformerAccounting.Order.INPUT_FIRST,1);
        require(in.after().equals(new TransformerAccounting.State(128,128,124)),"Input-before-output capacity");
        var disabled=TransformerAccounting.advance(c,before,128,40000000,true,TransformerAccounting.Order.OUTPUT_FIRST,Long.MAX_VALUE);
        require(disabled.after().equals(before) && disabled.dissipated()==0,"Nonproductive path has no debit");
        try {
            TransformerAccounting.advance(c,before,128,40000000,true,TransformerAccounting.Order.INPUT_FIRST,-1);
            throw new AssertionError("Missing negative loss rejection");
        } catch(IllegalArgumentException expected) { assertions++; }
        System.out.printf("SCEX_TRANSFORMER_LOSS rows=%d assertions=%d PASS scope=single_output_path%n",rows.size()-1,assertions);
    }
}
