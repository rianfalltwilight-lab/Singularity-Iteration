// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import java.util.ArrayList;
import java.util.List;

/** Public global IDs, tie paths and overflow boundaries across interleaved components. */
public final class ComponentRoutesContract {
    private static int assertions;
    private ComponentRoutesContract() { }
    private static void check(boolean value,String message) { assertions++;if(!value)throw new AssertionError(message); }
    private static List<Integer> path(ConductorGraph.Routes routes,int contact) {
        var result=new ArrayList<Integer>();routes.visitPath(contact,result::add);return result;
    }
    public static void main(String[] args) {
        var graph=new ConductorGraph(new long[]{0,100,0,7,0,20,0,Long.MAX_VALUE,Long.MAX_VALUE,1},
            new int[][]{{0,2},{0,4},{2,6},{4,6},{1,3},{3,5},{8,9}});
        var zero=graph.routesFrom(0);
        for(int id:List.of(0,2,4,6))check(zero.reaches(id)&&zero.lossMilliTo(id)==0,"Zero-cost component remains reachable");
        for(int id:List.of(1,3,5,7,8,9))check(!zero.reaches(id),"Other component remains unreachable");
        check(path(zero,6).equals(List.of(6,2,0)),"Equal-cost route retains global-ID tie path");
        var other=graph.routesFrom(1);
        check(other.lossMilliTo(5)==127,"Interleaved global IDs retain summed loss");
        check(path(other,5).equals(List.of(5,3,1)),"Path visitor emits original global IDs");
        check(!other.reaches(0)&&!other.reaches(7),"Local index zero does not alias another component");
        check(graph.routesFrom(7).lossMilliTo(7)==Long.MAX_VALUE,"Representable isolated maximum remains valid");
        try { graph.routesFrom(8);throw new AssertionError("Overflowing reachable route accepted"); }
        catch(ArithmeticException expected) { assertions++; }
        try { zero.lossMilliTo(1);throw new AssertionError("Unreachable lookup accepted"); }
        catch(IllegalStateException expected) { assertions++; }
        try { zero.reaches(10);throw new AssertionError("Out-of-range global ID accepted"); }
        catch(IllegalArgumentException expected) { assertions++; }
        try { zero.reaches(-1);throw new AssertionError("Negative global ID accepted"); }
        catch(IllegalArgumentException expected) { assertions++; }
        check(path(graph.routesFrom(6),0).equals(List.of(0,2,6)),"Reverse zero-cost path retains ordered global parents");
        System.out.printf("SCEX_COMPONENT_ROUTES_CONTRACT assertions=%d PASS performance_measured_separately%n",assertions);
    }
}
