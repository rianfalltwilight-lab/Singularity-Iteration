// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Real output-path observations exercised through the shared domain allocator. */
public final class DomainBatchContract {
    private static long assertions;
    private DomainBatchContract() { }
    private static void require(boolean value,String label) {
        assertions++;if(!value)throw new AssertionError(label);
    }
    private static long[] vector(String value) { return Arrays.stream(value.split(",")).mapToLong(Long::parseLong).toArray(); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch(IllegalArgumentException expected) { assertions++;return; }
        throw new AssertionError("Missing invalid-quote rejection");
    }
    private static void permutations(int[] a,int index,List<int[]> out) {
        if(index==a.length){out.add(a.clone());return;}
        for(int i=index;i<a.length;i++) {
            int saved=a[index];a[index]=a[i];a[i]=saved;
            permutations(a,index+1,out);
            saved=a[index];a[index]=a[i];a[i]=saved;
        }
    }
    /** Selects a supplied domain permutation; does not imitate any reference PRNG. */
    private static final class OrderRandom extends Random {
        private static final long serialVersionUID=1L;
        private final int[] choices;
        private int offset;
        OrderRandom(int[] wanted) {
            super(0);choices=new int[Math.max(0,wanted.length-1)];int[] current=new int[wanted.length];
            Arrays.setAll(current,i->i);
            for(int end=current.length-1;end>0;end--) {
                int selected=0;while(current[selected]!=wanted[end])selected++;
                choices[current.length-1-end]=selected;int saved=current[end];current[end]=current[selected];current[selected]=saved;
            }
        }
        @Override public int nextInt(int bound) {
            if(offset<choices.length) {
                require(bound==choices.length+1-offset,"Expected domain shuffle call");
                return choices[offset++];
            }
            return 0;
        }
    }
    private static DomainDistributor.Round allocate(long reserve,long packet,long[] room,long[] losses,int[] order) {
        var domains=new ArrayList<DomainDistributor.Domain>();int[] contacts=new int[room.length];Arrays.setAll(contacts,i->i);
        for(int i=0;i<room.length;i++) {
            final int target=i;
            RouteCosts route=new RouteCosts() {
                @Override public boolean reaches(int contact) { return contact==target; }
                @Override public long lossMilliTo(int contact) {
                    if(!reaches(contact))throw new IllegalArgumentException("Unreachable contact");
                    return Math.multiplyExact(losses[target],1000);
                }
            };
            domains.add(new DomainDistributor.Domain(new int[]{0},List.of(route),new int[][]{contacts}));
        }
        return DomainDistributor.allocateTraced(List.of(new DomainDistributor.Source(reserve,packet,false,4)),domains,contacts,room,new OrderRandom(order));
    }
    private static void invariant(DomainDistributor.Round round,long reserve,long packet) {
        long totalCredit=0,traceDebit=0,traceCredit=0,traceLoss=0;
        for(int i=0;i<round.receiverCount();i++){require(round.credit(i)>=0,"Nonnegative credit");totalCredit+=round.credit(i);}
        require(round.debit(0)==totalCredit+round.dissipated(),"Round conservation");
        require(round.debit(0)<=Math.min(reserve/packet,4)*packet,"Single frozen batch budget");
        for(var d:round.deliveries()) {
            require(d.source()==0 && d.entry()==0,"Stable source identity");
            require(d.domain()==d.receiver(),"Actual output domain retained");
            require(d.sourceDebit()<=packet,"Individual packet voltage bound");
            traceDebit+=d.sourceDebit();traceCredit+=d.credit();traceLoss+=d.pathLoss();
        }
        require(traceDebit==round.debit(0) && traceCredit==totalCredit && traceLoss==round.dissipated(),"Packet trace reconciles");
    }
    public static void main(String[] args) throws Exception {
        var rows=Files.readAllLines(Path.of(args[0]),StandardCharsets.UTF_8);require(rows.size()==5921,"Frozen observation count");
        long matched=0,ordersChecked=0;
        for(var line:rows.subList(1,rows.size())) {
            var f=line.split("\t");long packet=Long.parseLong(f[1]),reserve=Long.parseLong(f[2]),debit=Long.parseLong(f[5]);
            long[] room=vector(f[3]),losses=vector(f[4]),credits=vector(f[6]);int[] initial=new int[room.length];Arrays.setAll(initial,i->i);
            var orders=new ArrayList<int[]>();permutations(initial,0,orders);boolean found=false;
            for(var order:orders) {
                var round=allocate(reserve,packet,room,losses,order);invariant(round,reserve,packet);boolean same=round.debit(0)==debit;
                for(int i=0;i<credits.length;i++)same&=round.credit(i)==credits[i];found|=same;ordersChecked++;
            }
            require(found,f[0]);matched++;
        }
        // Explicit cross-domain continuation: 3 EU remainder must not be re-rounded to zero.
        var tail=allocate(256,32,new long[]{28,1000},new long[]{1,2},new int[]{0,1});
        require(tail.debit(0)==128 && tail.credit(0)==28 && tail.credit(1)==91 && tail.dissipated()==9,"Usable tail crosses domains");
        var blocked=allocate(256,32,new long[]{30,1000},new long[]{1,2},new int[]{0,1});
        require(blocked.debit(0)==127 && blocked.credit(1)==90,"Unusable tail remains in reserve");
        require(new DomainDistributor.Source(10,32,true).packetCount()==1,"Old constructor remains single-packet");
        rejects(()->new DomainDistributor.Source(128,32,false,0));
        rejects(()->new DomainDistributor.Source(128,32,false,5));
        rejects(()->new DomainDistributor.Source(128,32,true,4));
        rejects(()->TransformerBatch.allocateQuoted(32,129,new long[]{1},new int[]{0},new long[]{0}));
        var untraced=TransformerBatch.allocateQuoted(32,99,new long[]{1000},new int[]{0},new long[]{2},false);
        require(untraced.debit()==99 && untraced.credit(0)==91 && untraced.dissipated()==8
            && untraced.deliveries().isEmpty(),"Untraced accounting retains usable residual");
        long largePacket=Long.MAX_VALUE/2;
        var large=allocate(Long.MAX_VALUE,largePacket,new long[]{Long.MAX_VALUE},new long[]{0},new int[]{0});
        require(large.debit(0)==largePacket*2 && large.credit(0)==largePacket*2,"Reserve-limited quote does not overflow");
        System.out.printf("SCEX_DOMAIN_BATCH rows=%d matched=%d orders=%d assertions=%d PASS scope=shared_batch_output_domains%n",rows.size()-1,matched,ordersChecked,assertions);
    }
}
