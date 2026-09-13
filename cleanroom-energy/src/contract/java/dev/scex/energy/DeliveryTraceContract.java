// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.util.List;
import java.util.ArrayList;
import java.util.SplittableRandom;

/** Detached delivery identity/order and reconciliation with the existing accounting path. */
public final class DeliveryTraceContract {
    private static int assertions;
    private DeliveryTraceContract() { }
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    private static RouteCosts routes(long... losses) {
        return new RouteCosts() {
            public boolean reaches(int contact) { return losses[contact] >= 0; }
            public long lossMilliTo(int contact) { return losses[contact]; }
        };
    }
    public static void main(String[] args) {
        var ordered = new DomainDistributor.Domain(new int[]{0}, List.of(routes(1000,2000,3000)), new int[][]{{2,0,1}});
        var one = DomainDistributor.allocateTraced(List.of(new DomainDistributor.Source(32,32,false)),
            List.of(ordered),new int[]{0,1,2},new long[]{10,10,20},new SplittableRandom(7));
        check(one.deliveries().equals(List.of(new DomainDistributor.Delivery(0,0,0,2,20,3),
            new DomainDistributor.Delivery(0,0,0,0,8,1))),"Delivery order follows receiver priority, not vector index");
        check(one.debit(0)==32 && one.dissipated()==4,"Ordered trace has independent expected totals");
        try { one.deliveries().clear(); throw new AssertionError("Mutable receipt"); }
        catch (UnsupportedOperationException expected) { assertions++; }

        long[] mutableLoss={1000};
        var captured=PacketDistributor.allocateTraced(32,32,routes(mutableLoss),new int[]{0},new long[]{5},new int[]{0});
        mutableLoss[0]=9000;
        check(captured.credit(0)==5 && captured.deliveryLoss(0)==1,"Delivery loss does not requery changed route provider");
        var absent=PacketDistributor.allocateTraced(31,32,routes(1000),new int[]{0},new long[]{5},new int[]{0});
        check(absent.sourceDebit()==0 && absent.deliveryLoss(0)==0,"No reserve yields no delivery loss");

        var sources=List.of(new DomainDistributor.Source(64,32,false),new DomainDistributor.Source(8,8,true));
        var domains=List.of(
            DomainDistributor.Domain.withSharedSourceContacts(new int[]{0,0,1},
                List.of(routes(1000,-1,-1),routes(-1,2000,0),routes(0,0,0)),
                new int[][]{{1,2,0},{2,0,1},{1,0,2}}),
            new DomainDistributor.Domain(new int[]{0,1},List.of(routes(0,0,-1),routes(-1,1000,0)),
                new int[][]{{0,2,1},{2,1,0}}));
        for (int seed=0;seed<128;seed++) {
            var a=new SplittableRandom(seed);var b=new SplittableRandom(seed);
            long[] room={seed%7,5,11};
            var plain=DomainDistributor.allocate(sources,domains,new int[]{2,0,1},room,a);
            var traced=DomainDistributor.allocateTraced(sources,domains,new int[]{2,0,1},room,b);
            check(plain.deliveries().isEmpty(),"Ordinary path does not build a delivery collection");
            check(a.nextLong()==b.nextLong(),"Tracing preserves random stream consumption");
            long[] debit=new long[2],credit=new long[3];long loss=0;
            for(var delivery:traced.deliveries()) {
                check(delivery.source()==(delivery.domain()==0 ? new int[]{0,0,1} : new int[]{0,1})[delivery.entry()],
                    "Domain and entry identify the exact physical source, including shared contacts");
                debit[delivery.source()]+=delivery.sourceDebit();credit[delivery.receiver()]+=delivery.credit();loss+=delivery.pathLoss();
            }
            for(int i=0;i<2;i++)check(debit[i]==traced.debit(i)&&traced.debit(i)==plain.debit(i),"Per-source trace reconciles and preserves debit");
            for(int i=0;i<3;i++)check(credit[i]==traced.credit(i)&&traced.credit(i)==plain.credit(i),"Per-receiver trace reconciles and preserves credit");
            check(loss==traced.dissipated()&&loss==plain.dissipated(),"Loss trace reconciles and preserves accounting");
        }
        try(var registry=new ConductorRegistry(8,2)) {
            var first=new ConductorRegistry.Position(5,6,7);var second=new ConductorRegistry.Position(5,7,7);
            registry.put(second,200);registry.put(first,100);
            var snapshot=registry.snapshot();var path=snapshot.path(first,second);
            check(snapshot.position(snapshot.vertex(first)).equals(first),"Global vertex round-trips to the original position");
            var visited=new ArrayList<ConductorRegistry.Position>();path.visit(visited::add);
            check(visited.equals(List.of(second,first))&&path.lossMilli()==300,"Selected route retains positions and summed loss");
            try { snapshot.position(-1);throw new AssertionError("Invalid vertex accepted"); }
            catch(IllegalArgumentException expected) { assertions++; }
            registry.remove(first);
            try { path.visit(visited::add);throw new AssertionError("Stale path accepted"); }
            catch(IllegalStateException expected) { assertions++; }
            check(visited.size()==2,"Stale path does not invoke consumer");
        }
        System.out.printf("SCEX_DELIVERY_TRACE_CONTRACT assertions=%d PASS world_effects_NOT_verified%n",assertions);
    }
}
