// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Full observed transformer input/output steps through shared storage domains. */
public final class SharedStorageContract {
    private static long assertions;
    private SharedStorageContract() { }
    private static void require(boolean value,String label) {
        assertions++;if(!value)throw new AssertionError(label);
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch(IllegalArgumentException expected) { assertions++;return; }
        throw new AssertionError("Expected invalid shared snapshot rejection");
    }
    private static final class OrderRandom extends Random {
        private static final long serialVersionUID=1L;
        private final boolean reverse;
        OrderRandom(boolean reverse) { super(0);this.reverse=reverse; }
        @Override public int nextInt(int bound) { return reverse?0:bound-1; }
    }
    private static RouteCosts route(int target,boolean connected,long loss) {
        return new RouteCosts() {
            @Override public boolean reaches(int contact) { return connected && contact==target; }
            @Override public long lossMilliTo(int contact) {
                if(!reaches(contact))throw new IllegalArgumentException("Unreachable contact");
                return Math.multiplyExact(loss,1000);
            }
        };
    }
    private static DomainDistributor.Domain domain(int source,int receiver,boolean connected,long loss,int count) {
        int[] priority=new int[count];Arrays.setAll(priority,i->i);
        return new DomainDistributor.Domain(new int[]{source},List.of(route(receiver,connected,loss)),new int[][]{priority});
    }
    private static TransformerAccounting.State plan(TransformerAccounting.Configuration c,TransformerAccounting.State before,
                                                  long sourcePacket,long capacity,boolean connected,boolean outputFirst,long loss) {
        var sources=List.of(new DomainDistributor.Source(before.source(),sourcePacket,false),
            new DomainDistributor.Source(before.buffer(),c.outputPacket(),false,c.outputPackets()));
        var domains=List.of(domain(0,0,true,0,2),domain(1,1,connected,loss,2));
        var round=DomainDistributor.allocateTraced(sources,domains,new int[]{0,1},
            new long[]{c.capacity()-before.buffer(),Math.max(0,capacity-before.receiver())},
            List.of(new DomainDistributor.SharedStorage(1,0,c.capacity())),new OrderRandom(outputFirst));
        var after=new TransformerAccounting.State(before.source()-round.debit(0),
            before.buffer()-round.debit(1)+round.credit(0),before.receiver()+round.credit(1));
        require(after.buffer()<=c.capacity(),"Transformer capacity bound");
        require(before.source()+before.buffer()+before.receiver()==after.source()+after.buffer()+after.receiver()+round.dissipated(),"Shared ledger conservation");
        require(round.debit(1)<=Math.min(before.buffer()/c.outputPacket(),c.outputPackets())*c.outputPacket(),"No budget replenishment");
        return after;
    }
    public static void main(String[] args) throws Exception {
        var rows=Files.readAllLines(Path.of(args[0]),StandardCharsets.UTF_8);require(rows.size()==15201,"Frozen observation count");
        int inputOnly=0,outputOnly=0,both=0;
        for(var line:rows.subList(1,rows.size())) {
            var f=line.split("\t");var c=new TransformerAccounting.Configuration(Long.parseLong(f[1]),f[2].equals("1"));
            var before=new TransformerAccounting.State(Long.parseLong(f[6]),Long.parseLong(f[7]),Long.parseLong(f[8]));
            var expected=new TransformerAccounting.State(Long.parseLong(f[9]),Long.parseLong(f[10]),Long.parseLong(f[11]));
            boolean input=plan(c,before,Long.parseLong(f[3]),Long.parseLong(f[4]),f[5].equals("1"),false,0).equals(expected);
            boolean output=plan(c,before,Long.parseLong(f[3]),Long.parseLong(f[4]),f[5].equals("1"),true,0).equals(expected);
            require(input||output,f[0]);if(input&&output)both++;else if(input)inputOnly++;else outputOnly++;
        }
        var c=new TransformerAccounting.Configuration(32,false);
        require(plan(c,new TransformerAccounting.State(128,256,0),128,40000000,true,true,1)
            .equals(new TransformerAccounting.State(0,256,124)),"Output loss also releases input capacity");
        require(plan(c,new TransformerAccounting.State(128,0,0),128,40000000,true,false,0)
            .equals(new TransformerAccounting.State(0,128,0)),"New input cannot be emitted in same round");
        var sources=List.of(new DomainDistributor.Source(110,32,false),new DomainDistributor.Source(32,32,false));
        var domains=List.of(domain(0,1,true,0,2),domain(1,0,true,0,2));
        var bindings=List.of(new DomainDistributor.SharedStorage(0,0,100));
        var round=DomainDistributor.allocateTraced(sources,domains,new int[]{0,1},new long[]{0,1000},bindings,new OrderRandom(false));
        require(round.debit(0)==32 && round.credit(0)==22 && round.debit(1)==22,"Initial overcapacity is not free room");
        var plain=DomainDistributor.allocate(sources,domains,new int[]{0,1},new long[]{0,1000},bindings,new OrderRandom(false));
        require(plain.debit(0)==round.debit(0) && plain.debit(1)==round.debit(1) && plain.credit(0)==22
            && plain.deliveries().isEmpty(),"Untraced shared planning");
        var self=DomainDistributor.allocateTraced(List.of(new DomainDistributor.Source(50,32,false)),
            List.of(domain(0,0,true,0,1)),new int[]{0},new long[]{50},
            List.of(new DomainDistributor.SharedStorage(0,0,100)),new OrderRandom(false));
        require(self.debit(0)==0 && self.credit(0)==0,"No self-delivery");
        rejects(()->DomainDistributor.allocateTraced(sources,domains,new int[]{0,1},new long[]{1,1000},bindings,new OrderRandom(false)));
        rejects(()->DomainDistributor.allocateTraced(sources,domains,new int[]{0,1},new long[]{0,1000},
            List.of(new DomainDistributor.SharedStorage(2,0,100)),new OrderRandom(false)));
        rejects(()->DomainDistributor.allocateTraced(sources,domains,new int[]{0,1},new long[]{0,1000},
            List.of(new DomainDistributor.SharedStorage(0,2,100)),new OrderRandom(false)));
        rejects(()->DomainDistributor.allocateTraced(sources,domains,new int[]{0,1},new long[]{0,1000},
            List.of(new DomainDistributor.SharedStorage(0,0,100),new DomainDistributor.SharedStorage(0,1,100)),new OrderRandom(false)));
        rejects(()->DomainDistributor.allocateTraced(sources,domains,new int[]{0,1},new long[]{0,1000},
            List.of(new DomainDistributor.SharedStorage(0,0,100),new DomainDistributor.SharedStorage(1,0,100)),new OrderRandom(false)));
        System.out.printf("SCEX_SHARED_STORAGE rows=%d input_only=%d output_only=%d both=%d assertions=%d PASS%n",rows.size()-1,inputOnly,outputOnly,both,assertions);
    }
}
