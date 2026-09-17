// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy.minecraft;

import dev.scex.energy.ConductorRegistry.Position;

/** Pure-Java public journal behavior; requires only core and ContactOrder classes. */
public final class ContactOrderDirectionContract {
    private static long assertions;
    private static final int[][] STEP={{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
    private ContactOrderDirectionContract() { }
    private static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    private static Position at(int face,int distance){return new Position(STEP[face][0]*distance,STEP[face][1]*distance,STEP[face][2]*distance);}
    public static void main(String[] args) {
        for(int face=0;face<6;face++)try(var order=new ContactOrder(4,2)) {
            var source=at(face,0);var first=at(face,1);var last=at(face,2);var sink=at(face,3);
            order.putEndpoint(source);order.putConductor(first,1);order.putConductor(last,1);order.putEndpoint(sink);
            var initial=order.receivers(source,first);check(initial.contains(sink),"Initial contact trace");
            order.putConductor(first,1,63^(1<<face));check(order.receivers(source,first).isEmpty(),"First wire blocks forward face");
            order.putConductor(first,1,63);check(order.receivers(source,first).equals(initial),"Unblock restores existing history");
            order.putConductor(last,1,63^(1<<(face^1)));check(order.receivers(source,first).isEmpty(),"Opposite wire alone can block connection");
            order.putConductor(last,1,63);check(order.receivers(source,first).equals(initial),"Opposite unblock restores history");
            order.putConductor(first,1,63^(1<<(face^1)));check(order.receivers(source,first).isEmpty(),"Blocked source-to-wire contact");
            order.putConductor(first,1,63);order.putConductor(last,1,63^(1<<face));
            check(order.receivers(source,first).isEmpty(),"Blocked wire-to-receiver contact");
            order.putConductor(last,1,63);check(order.receivers(source,first).equals(initial),"Receiver contact restored");
            long rebuilds=order.rebuildCount();order.putConductor(last,1,63);order.receivers(source,first);
            check(order.rebuildCount()==rebuilds,"Unchanged mask retains cached journal graph");
            order.remove(last);check(order.receivers(source,first).isEmpty(),"Unload/removal releases directional path");
            order.putConductor(last,1,63^(1<<(face^1)));check(order.receivers(source,first).isEmpty(),"Reinserted saved mask remains closed");
        }
        System.out.printf("SCEX_CONTACT_DIRECTION assertions=%d PASS runtime_integration_NOT_RUN%n",assertions);
    }
}
