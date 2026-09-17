// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy;

import dev.scex.energy.ConductorRegistry.Position;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** Behavioral contracts with an independent weighted-coordinate oracle; no Minecraft classes. */
public final class ConductorDirectionContract {
    private static long assertions;
    private static final int[][] STEP = {{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
    private record Expected(long loss, int mask) { }
    private ConductorDirectionContract() { }
    private static void check(boolean ok, String message) {
        assertions++; if (!ok) throw new AssertionError(message);
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { assertions++; return; }
        throw new AssertionError("Expected rejected stale or invalid operation");
    }
    private static Position p(int x, int y, int z) { return new Position(x,y,z); }
    private static int facing(Position from, Position to) {
        long x=(long)to.x()-from.x(), y=(long)to.y()-from.y(), z=(long)to.z()-from.z();
        if (Math.abs(x)+Math.abs(y)+Math.abs(z)!=1) return -1;
        if (x!=0) return x>0 ? 5 : 4;
        if (y!=0) return y>0 ? 1 : 0;
        return z>0 ? 3 : 2;
    }
    private static boolean joined(Position a, Position b, Map<Position,Expected> model) {
        int face=facing(a,b);
        return face>=0 && (model.get(a).mask()&(1<<face))!=0 && (model.get(b).mask()&(1<<(face^1)))!=0;
    }
    private static void compareOracle(ConductorRegistry registry, Map<Position,Expected> model) {
        var points=new ArrayList<>(model.keySet()); var snapshot=registry.snapshot(); int n=points.size();
        for (int source=0; source<n; source++) {
            // Bellman relaxation on coordinates is separate from the production graph/heap.
            long[] distance=new long[n]; Arrays.fill(distance,Long.MAX_VALUE);
            distance[source]=model.get(points.get(source)).loss();
            for (int pass=0; pass<n-1; pass++) {
                boolean changed=false;
                for (int a=0;a<n;a++) if (distance[a]!=Long.MAX_VALUE) for(int b=0;b<n;b++) {
                    if (!joined(points.get(a),points.get(b),model)) continue;
                    long candidate=distance[a]+model.get(points.get(b)).loss();
                    if(candidate<distance[b]){distance[b]=candidate;changed=true;}
                }
                if(!changed)break;
            }
            var routes=snapshot.routesFrom(points.get(source));
            for(int target=0;target<n;target++) {
                var at=points.get(target); int id=snapshot.vertex(at); boolean expected=distance[target]!=Long.MAX_VALUE;
                check(routes.reaches(id)==expected,"Masked coordinate connectivity differs");
                if(!expected)continue;
                check(routes.lossMilliTo(id)==distance[target],"Masked weighted route differs");
                var path=new ArrayList<Position>(); snapshot.path(points.get(source),at).visit(path::add);
                long loss=0;
                for(int i=0;i<path.size();i++) {
                    loss+=model.get(path.get(i)).loss();
                    if(i>0)check(joined(path.get(i-1),path.get(i),model),"Selected path crosses a closed face");
                }
                check(loss==distance[target],"Selected path has incorrect loss");
            }
        }
    }
    private static void exhaustiveAdjacentMasks() {
        Position a=p(0,0,0);
        for(int face=0;face<6;face++)try(var registry=new ConductorRegistry(2,2)) {
            Position b=p(STEP[face][0],STEP[face][1],STEP[face][2]);
            for(int first=0;first<64;first++)for(int second=0;second<64;second++) {
                registry.put(a,3,first);registry.put(b,7,second);var snapshot=registry.snapshot();
                boolean expected=(first&(1<<face))!=0 && (second&(1<<(face^1)))!=0;
                check(snapshot.routesFrom(a).reaches(snapshot.vertex(b))==expected,"Both faces must admit forward connection");
                check(snapshot.routesFrom(b).reaches(snapshot.vertex(a))==expected,"Both faces must admit reverse connection");
                check(snapshot.permits(a,face)==((first&(1<<face))!=0),"Source contact mask");
                check(snapshot.permits(b,face^1)==((second&(1<<(face^1)))!=0),"Receiver contact mask");
                if(expected)check(snapshot.path(a,b).lossMilli()==10,"Two-wire loss conserved");
            }
        }
    }
    private static void leasesAndDetours() {
        try(var r=new ConductorRegistry(6,3)) {
            for(int x=0;x<3;x++){r.put(p(x,0,0),1);r.put(p(x,1,0),1);}
            var before=r.snapshot();var path=before.path(p(0,0,0),p(2,0,0));
            check(path.lossMilli()==3,"Straight path before closing a face");
            long rebuilds=r.rebuildCount(),revision=r.revision();
            check(r.put(p(1,0,0),1,63^(1<<5)),"Mask-only edit is material");
            check(r.rebuildCount()==rebuilds && r.revision()==revision+1,"Mask edit invalidates lazily");
            check(!r.isCurrent(before),"Old lease cannot authorize a debit");rejects(()->path.visit(at->{}));
            var detour=r.snapshot();check(detour.path(p(0,0,0),p(2,0,0)).lossMilli()==5,"Open alternate route survives blocked direct edge");
            check(!r.put(p(1,0,0),1,63^(1<<5)) && r.snapshot()==detour,"No-op retains cached topology");
            rejects(()->r.put(p(1,0,0),1,-1));rejects(()->r.put(p(1,0,0),1,64));
            check(r.isCurrent(detour),"Invalid masks do not invalidate or mutate state");
            r.put(p(0,0,0),1,1<<5);r.put(p(1,0,0),1,1<<4);
            var isolated=r.snapshot();check(!isolated.routesFrom(p(0,0,0)).reaches(isolated.vertex(p(2,0,0))),"Closing both escape routes disconnects sink");
            r.put(p(1,0,0),1);check(r.snapshot().path(p(0,0,0),p(2,0,0)).lossMilli()==3,"Legacy overload explicitly restores all faces");
        }
        var r=new ConductorRegistry(2,2);r.put(p(15,0,0),1,63);r.put(p(16,0,0),1,63^(1<<4));
        var before=r.snapshot();int savedMask=before.openFaces(p(16,0,0));
        check(!before.routesFrom(p(15,0,0)).reaches(before.vertex(p(16,0,0))),"One-sided chunk-border block is sufficient");
        check(r.unloadChunk(1,0)==1 && !r.isCurrent(before),"Chunk unload revokes directional lease");
        r.put(p(16,0,0),1,savedMask);var loaded=r.snapshot();
        check(!loaded.routesFrom(p(15,0,0)).reaches(loaded.vertex(p(16,0,0))),"Saved mask survives coordinate re-registration");
        r.put(p(16,0,0),1,63);var finalLease=r.snapshot();check(finalLease.path(p(15,0,0),p(16,0,0)).lossMilli()==2,"Unblock restores cross-chunk route");
        r.close();rejects(()->finalLease.openFaces(p(16,0,0)));
        try(var limits=new ConductorRegistry(2,1)) {
            limits.put(p(Integer.MAX_VALUE,0,0),0,1<<5);limits.put(p(Integer.MIN_VALUE,0,0),0,1<<4);
            var snapshot=limits.snapshot();check(!snapshot.routesFrom(p(Integer.MAX_VALUE,0,0)).reaches(snapshot.vertex(p(Integer.MIN_VALUE,0,0))),"Masks never wrap extreme coordinates");
        }
    }
    private static void weightedOracleAfterEdits() {
        var random=new Random(1010916);var model=new LinkedHashMap<Position,Expected>();
        try(var r=new ConductorRegistry(18,4)) {
            for(int x=0;x<3;x++)for(int y=0;y<3;y++)for(int z=0;z<2;z++) {
                var at=p(x,y,z);var value=new Expected(1+random.nextInt(9),63);model.put(at,value);r.put(at,value.loss(),value.mask());
            }
            compareOracle(r,model);var positions=new ArrayList<>(model.keySet());
            for(int edit=0;edit<48;edit++) {
                var at=positions.get(random.nextInt(positions.size()));var before=model.get(at);
                var value=new Expected(before.loss(),random.nextInt(64));model.put(at,value);r.put(at,value.loss(),value.mask());
                compareOracle(r,model);
            }
        }
    }
    public static void main(String[] args) {
        exhaustiveAdjacentMasks();leasesAndDetours();weightedOracleAfterEdits();
        System.out.printf("SCEX_CONDUCTOR_DIRECTION assertions=%d PASS runtime_integration_NOT_RUN%n",assertions);
    }
}
