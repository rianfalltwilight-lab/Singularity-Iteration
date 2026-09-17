// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import dev.scex.si.processing.IndependentUuValueIndex;
import dev.scex.si.processing.UuReferenceCatalog;
import dev.scex.si.processing.UuRecipeSolver;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Real DFU/catalog plus independent accounting contracts, without a Minecraft world. */
public final class UuPricingContract {
    private static int assertions;
    private UuPricingContract() { }
    private static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    private static void close(double a, double b, String label) { check(Math.abs(a-b) <= Math.max(Math.abs(a),Math.abs(b))*1e-12,label); }
    private static UuRecipeSolver.Choice<String> choice(String item) { return new UuRecipeSolver.Choice<>(item,null); }
    private static UuRecipeSolver.Rule<String> rule(String out, int count, List<List<UuRecipeSolver.Choice<String>>> slots) {
        return new UuRecipeSolver.Rule<>(out,out,count,slots,1);
    }
    private static void solver() {
        var solve = new UuRecipeSolver<String>(128,10000);
        var rules = List.of(rule("planks",4,List.of(List.of(choice("log")))),
                rule("stick",4,List.of(List.of(choice("planks")),List.of(choice("planks")))),
                rule("log",1,List.of(List.of(choice("stick")))),
                rule("cake",1,List.of(List.of(new UuRecipeSolver.Choice<>("milk","bucket")),List.of(choice("sugar")))),
                rule("plate",1,List.of(List.of(choice("expensive"),choice("cheap")))),
                rule("denied",64,List.of(List.of(choice("cheap")))),
                rule("unknown",1,List.of(List.of(choice("missing")))),
                rule("cycleA",64,List.of(List.of(choice("cycleB")))),rule("cycleB",64,List.of(List.of(choice("cycleA")))));
        var result=solve.solve(Map.of("log",19.0,"milk",8.0,"bucket",6.0,"sugar",2.0,"cheap",3.0,"expensive",30.0),Set.of("denied"),rules);
        check(result.complete(),"Complete bounded graph");
        close(result.values().get("log"),19,"Reference anchor cannot be repriced through reverse recipe");
        close(result.values().get("planks"),5,"Output count applied once");
        close(result.values().get("stick"),2.75,"Repeated input slots counted");
        close(result.values().get("cake"),5,"Returned container credited once");
        close(result.values().get("plate"),4,"Cheapest legal ingredient alternative");
        check(!result.values().containsKey("denied")&&!result.values().containsKey("unknown"),"Denied and unknown stay unquoted");
        check(!result.values().containsKey("cycleA")&&!result.values().containsKey("cycleB"),"Unseeded cycles cannot invent value");
        var cycle=solve.solve(Map.of("seed",100.0),Set.of(),List.of(rule("a",1,List.of(List.of(choice("seed")))),rule("b",64,List.of(List.of(choice("a")))),rule("a",64,List.of(List.of(choice("b"))))));
        close(cycle.values().get("a"),101,"Profitable cycle must not recursively discount itself");
        check(!new UuRecipeSolver<String>(128,1).solve(Map.of("log",19.0),Set.of(),rules).complete(),"Work budget never reports partial results complete");
        check(!new UuRecipeSolver<String>(1,1000).solve(Map.of("log",19.0),Set.of(),List.of(rules.getFirst())).complete(),"Node budget is enforced");
        var negative=solve.solve(Map.of("milk",1.0,"bucket",10.0),Set.of(),List.of(rule("x",1,List.of(List.of(new UuRecipeSolver.Choice<>("milk","bucket"))))));
        check(!negative.values().containsKey("x"),"Negative remainder credit is not a free recipe");
        var overflow=solve.solve(Map.of("large",8e15),Set.of(),List.of(rule("x",1,List.of(List.of(choice("large")),List.of(choice("large"))))));
        check(!overflow.values().containsKey("x"),"Excessive total is not a legal machine quote");
        try {solve.solve(Map.of("bad",Double.NaN),Set.of(),List.of());throw new AssertionError("NaN accepted");}
        catch(IllegalArgumentException expected){check(true,"NaN rejected");}
        try {result.values().put("free",0.0);throw new AssertionError("Mutable result");}
        catch(UnsupportedOperationException expected){check(true,"Result immutable");}
        var swapped=new java.util.ArrayList<>(rules);java.util.Collections.reverse(swapped);
        check(solve.solve(Map.of("log",19.0,"milk",8.0,"bucket",6.0,"sugar",2.0,"cheap",3.0,"expensive",30.0),Set.of("denied"),swapped).values().equals(result.values()),"Ordinary DAG prices independent of rule order");
    }
    private static void catalog(Path path) throws Exception {
        var registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        for(var row:List.of(new String[]{"minecraft:stone","1","minecraft:granite"},new String[]{"minecraft:planks","5","minecraft:dark_oak_planks"},new String[]{"minecraft:wool","14","minecraft:red_wool"},new String[]{"minecraft:log","2","minecraft:birch_log"})) {
            var stack=UuReferenceCatalog.migrateVanilla("{id:\""+row[0]+"\",Count:1b,Damage:"+row[1]+"s}",registries);
            check(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(row[2]),"Official vanilla metadata flattening "+row[0]);
        }
        try {UuReferenceCatalog.migrateVanilla("{id:\"ic2:resource\",Count:1b,Damage:0s}",registries);throw new AssertionError("Guessed mod mapping");}
        catch(IllegalArgumentException expected){check(true,"No guessed IC2 to SI identity");}
        UuReferenceCatalog.Catalog reference;
        var cache=new UuReferenceCatalog.MigrationCache(registries);
        try(var reader=Files.newBufferedReader(path)){reference=UuReferenceCatalog.read(reader,cache);}
        long migrations=cache.migrations();
        try(var reader=Files.newBufferedReader(path)){
            var reloaded=UuReferenceCatalog.read(reader,cache);
            check(reloaded.prices().equals(reference.prices())&&reloaded.denied().equals(reference.denied()),"Cached identities preserve exact new catalog result");
            check(cache.migrations()==migrations,"Unchanged reload does not rerun per-item data fixing");
        }
        check(reference.prices().size()>100,"Load actual full selected vanilla observation set");
        close(reference.prices().get(IndependentUuValueIndex.keyOf(new ItemStack(Items.STONE))),0.00015,"Actual stone reference buckets");
        close(reference.prices().get(IndependentUuValueIndex.keyOf(new ItemStack(Items.IRON_INGOT))),0.0007463641798863822,"Actual iron reference buckets");
        for(var cost:reference.prices().values())check(Double.isFinite(cost)&&cost>0,"Finite positive observed price");
        for(var key:reference.denied())check(!reference.prices().containsKey(key),"Infinity not a zero or finite quote");
        String denied="{\"schema\":1,\"data_version\":1343,\"entries\":[{\"legacy_stack\":\"{id:'minecraft:stone',Count:1b,Damage:0s}\",\"raw_value\":\"Infinity\"}]}";
        var invalid=UuReferenceCatalog.read(new StringReader(denied),registries);
        check(invalid.prices().isEmpty()&&invalid.denied().size()==1,"Explicit Infinity blocks item");
        var changed=com.google.gson.JsonParser.parseString(denied.replace("Infinity","300")).getAsJsonObject();
        changed.getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("legacy_stack","{id:\"minecraft:stone\",Count:1b,Damage:0s}");
        var updated=UuReferenceCatalog.read(new StringReader(changed.toString()),cache);
        close(updated.prices().get(IndependentUuValueIndex.keyOf(new ItemStack(Items.STONE))),.003,"Identity cache never retains stale prices");
        check(cache.migrations()==migrations,"Price-only change reuses identity conversion");
        try {UuReferenceCatalog.read(new StringReader(denied.replace("Infinity","-1")),registries);throw new AssertionError("Negative price accepted");}
        catch(IllegalArgumentException expected){check(true,"Malformed numeric catalog rejected");}
        System.out.println("SCEX_UU_REFERENCE entries="+reference.entries()+" finite="+reference.prices().size()+" denied="+reference.denied().size()+" unmapped="+reference.unmapped());
    }
    public static void main(String[] args) throws Exception {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        for(var type:List.of(UuRecipeSolver.class,UuReferenceCatalog.class))
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
                .equals(Path.of(args[1]).toRealPath()),"Load actual newly compiled candidate class");
        solver();catalog(Path.of(args[0]));
        System.out.println("SCEX_UU_PRICING assertions="+assertions+" PASS");
    }
}
