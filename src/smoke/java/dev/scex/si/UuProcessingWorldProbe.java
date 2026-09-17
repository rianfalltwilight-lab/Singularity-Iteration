// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.singularity_iteration.mio_icif.recipe.mio_icif_PowderRecipe;
import com.singularity_iteration.mio_icif.recipe.compressor.mio_icif_CompressorRecipe;
import com.singularity_iteration.mio_icif.recipe.extractor.mio_icif_ExtractorRecipe;
import com.singularity_iteration.mio_icif.recipe.metal_former.cutting.mio_icif_CuttingRecipe;
import com.singularity_iteration.mio_icif.recipe.metal_former.extruding.mio_icif_ExtrudingRecipe;
import com.singularity_iteration.mio_icif.recipe.metal_former.rolling.mio_icif_RollingRecipe;
import dev.scex.si.processing.IndependentUuValueIndex;
import dev.scex.si.processing.UuPricingLifecycle;
import dev.scex.si.processing.UuProcessingPolicy;
import dev.scex.si.processing.UuProcessingRecipes;
import dev.scex.si.processing.UuQuoteBook;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.Recipe;

/** Read-only check of normal-loader recipes and the normal startup quote generation. */
public final class UuProcessingWorldProbe {
    private static final Path INPUT=Path.of("uu-processing-world.json");
    private static final Path OUTPUT=Path.of("uu-processing-world-result.json");
    private static final int MAX_BYTES=256*1024;
    private static final Set<String> FAMILIES=Set.of("POWDER","COMPRESSOR","EXTRACTOR","ROLLING","CUTTING","EXTRUDING");
    private boolean finished;

    private static void require(boolean condition,String message) {
        if(!condition)throw new IllegalStateException("R102 processing world: "+message);
    }
    private static boolean near(double actual,double expected) {
        return Double.isFinite(actual)&&Math.abs(actual-expected)<=Math.max(1.0e-12,Math.abs(expected)*1.0e-12);
    }
    private static byte[] bounded(java.io.InputStream input)throws Exception {
        byte[] bytes=input.readNBytes(MAX_BYTES+1);
        require(bytes.length<=MAX_BYTES,"bounded input");return bytes;
    }
    private static String sha(byte[] bytes)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static byte[] resource(ServerLevel world,String id)throws Exception {
        var found=world.getServer().getResourceManager().getResource(ResourceLocation.parse(id))
                .orElseThrow(()->new IllegalStateException("Missing active resource "+id));
        try(var input=found.open()){return bounded(input);}
    }
    private static boolean sameComponents(DataComponentMap a,DataComponentMap b) {
        if(!a.keySet().equals(b.keySet()))return false;
        for(var key:a.keySet())if(!Objects.equals(a.get(key),b.get(key)))return false;
        return true;
    }
    private static Map<String,Object> snapshot(ServerLevel world,ItemStack stack) {
        var ops=world.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var encoded=ItemStack.STRICT_CODEC.encodeStart(ops,stack).getOrThrow();
        var decoded=ItemStack.STRICT_CODEC.parse(ops,encoded).getOrThrow();
        require(ItemStack.isSameItemSameComponents(stack,decoded)&&stack.getCount()==decoded.getCount(),"strict stack roundtrip");
        var components=DataComponentMap.CODEC.encodeStart(ops,stack.getComponents()).getOrThrow();
        require(sameComponents(stack.getComponents(),DataComponentMap.CODEC.parse(ops,components).getOrThrow()),"all component values roundtrip");
        var result=new LinkedHashMap<String,Object>();
        result.put("stack_snbt",encoded.toString());result.put("all_components_snbt",components.toString());
        result.put("patch_snbt",DataComponentPatch.CODEC.encodeStart(ops,stack.getComponentsPatch()).getOrThrow().toString());
        result.put("strict_stack_roundtrip",true);result.put("full_component_roundtrip",true);
        return result;
    }
    private static String family(Recipe<?> recipe) {
        if(recipe.getClass()==mio_icif_PowderRecipe.class)return "POWDER";
        if(recipe.getClass()==mio_icif_CompressorRecipe.class)return "COMPRESSOR";
        if(recipe.getClass()==mio_icif_ExtractorRecipe.class)return "EXTRACTOR";
        if(recipe.getClass()==mio_icif_RollingRecipe.class)return "ROLLING";
        if(recipe.getClass()==mio_icif_CuttingRecipe.class)return "CUTTING";
        if(recipe.getClass()==mio_icif_ExtrudingRecipe.class)return "EXTRUDING";
        throw new IllegalStateException("Unexpected recipe class "+recipe.getClass().getName());
    }
    private static int inputCount(Recipe<?> recipe) {
        if(recipe instanceof mio_icif_CompressorRecipe r)return r.getIngredientCount();
        if(recipe instanceof mio_icif_RollingRecipe r)return r.getIngredientCount();
        if(recipe instanceof mio_icif_CuttingRecipe r)return r.getIngredientCount();
        if(recipe instanceof mio_icif_ExtrudingRecipe r)return r.getIngredientCount();
        require(recipe instanceof mio_icif_PowderRecipe||recipe instanceof mio_icif_ExtractorRecipe,"one-input family");
        return 1;
    }
    private static UuQuoteBook.Quote quote(ServerLevel world,ItemStack item,double expected,long generation,String reason) {
        var value=UuQuoteBook.quote(world.getServer(),item);
        require(value!=null&&value.generation()==generation&&near(value.buckets(),expected),reason);
        require(UuQuoteBook.classify(world.getServer(),item).disposition()==UuQuoteBook.Disposition.FINITE,reason+" finite disposition");
        return value;
    }

    private static Map<String,Object> inspectOne(ServerLevel world,JsonObject row,long generation,
            Set<IndependentUuValueIndex.Key> outputKeys)throws Exception {
        String caseId=row.get("case_id").getAsString();
        var result=new LinkedHashMap<String,Object>();result.put("case_id",caseId);
        result.put("family",row.get("family").getAsString());
        try {
            String recipeId=row.get("recipe_id").getAsString();
            var bytes=resource(world,row.get("resource_location").getAsString());
            require(sha(bytes).equals(row.get("resource_sha256").getAsString()),"active recipe resource SHA "+caseId);
            var ops=world.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            var document=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            // Dispatch through the actually registered serializer, then independently find the normal-loader holder.
            Recipe<?> decoded=Recipe.CODEC.parse(ops,document).getOrThrow();
            var holder=world.getServer().getRecipeManager().byKey(ResourceLocation.parse(recipeId))
                    .orElseThrow(()->new IllegalStateException("Normal loader omitted "+recipeId));
            Recipe<?> loaded=holder.value();
            require(loaded.getClass().getName().equals(row.get("recipe_class").getAsString()),"actual registered recipe class");
            require(family(loaded).equals(row.get("family").getAsString()),"family matches exact supported class");
            require(String.valueOf(BuiltInRegistries.RECIPE_SERIALIZER.getKey(loaded.getSerializer())).equals(row.get("serializer_id").getAsString()),"registered serializer identity");
            require(Recipe.CODEC.encodeStart(ops,loaded).getOrThrow().equals(Recipe.CODEC.encodeStart(ops,decoded).getOrThrow()),"normal loader agrees with registered full serializer");
            int count=inputCount(loaded),out=row.get("output_count").getAsInt();
            require(count==row.get("input_count").getAsInt(),"actual input count");
            require((count==1&&out==1)||((count==(Set.of("POWDER","EXTRACTOR").contains(family(loaded))?1:2))&&out==3),"frozen family quantity cases");
            require(loaded.getIngredients().size()==1,"one ingredient group");
            var examples=loaded.getIngredients().getFirst().getItems();
            require(examples.length==1&&examples[0].is(Items.STONE)&&examples[0].getCount()==1&&examples[0].isComponentsPatchEmpty(),"exact default stone ingredient");
            var stack=loaded.getResultItem(world.registryAccess()).copy();
            var expected=ItemStack.STRICT_CODEC.parse(ops,row.get("expected_result")).getOrThrow();
            var direct=decoded.getResultItem(world.registryAccess());
            require(stack.is(Items.PAPER)&&stack.getCount()==out&&ItemStack.isSameItemSameComponents(stack,expected)
                    &&stack.getCount()==expected.getCount()&&ItemStack.isSameItemSameComponents(stack,direct)&&direct.getCount()==out,"full result identity from real serializer");
            require(stack.has(DataComponents.CUSTOM_DATA)&&stack.get(DataComponents.CUSTOM_DATA).copyTag().getString("scex_r102_processing_case").equals(caseId),"unique output marker retained");
            var key=IndependentUuValueIndex.keyOf(stack);
            require(outputKeys.add(key),"distinct component keys for all twelve results");
            require(!key.equals(IndependentUuValueIndex.keyOf(new ItemStack(Items.PAPER))),"unmarked paper has different key");
            double raw=(15.0*count+14.0)/out;
            require(near(row.get("raw_input").getAsDouble(),15.0)&&near(row.get("raw_overhead").getAsDouble(),14.0)
                    &&near(row.get("expected_raw_per_item").getAsDouble(),raw),"independent raw arithmetic");
            var current=quote(world,stack,raw/100000.0,generation,"normal startup quote, overhead once before output division");
            quote(world,stack.copyWithCount(1),current.buckets(),generation,"quote is per item, independent of stack count");
            var unseen=stack.copyWithCount(1);
            var marker=unseen.get(DataComponents.CUSTOM_DATA).copyTag();marker.putString("scex_r102_processing_case",caseId+"_unseen");
            unseen.set(DataComponents.CUSTOM_DATA,CustomData.of(marker));
            require(!IndependentUuValueIndex.keyOf(unseen).equals(key)&&UuQuoteBook.quote(world.getServer(),unseen)==null,"unseen component variant is unquoted");
            result.put("recipe_id",recipeId);result.put("resource_sha256",sha(bytes));
            result.put("runtime_recipe_class",loaded.getClass().getName());
            result.put("serializer_id",String.valueOf(BuiltInRegistries.RECIPE_SERIALIZER.getKey(loaded.getSerializer())));
            result.put("actual_input_count",count);result.put("actual_output_count",stack.getCount());
            result.put("raw_overhead",14.0);result.put("expected_raw_per_item",raw);result.put("actual_raw_per_item",current.buckets()*100000.0);
            result.put("actual_buckets",current.buckets());result.put("generation",current.generation());
            result.put("result",snapshot(world,stack));result.put("unseen_variant",snapshot(world,unseen));
            result.put("unseen_variant_quoted",false);result.put("unmarked_paper_key_distinct",true);
            result.put("passed",true);
        } catch(Exception failure) {
            result.put("passed",false);result.put("failure_type",failure.getClass().getName());result.put("failure_message",String.valueOf(failure.getMessage()));
        }
        return result;
    }

    /** Call once from the normal smoke tick dispatcher before scanner reload/failure phases. */
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception {
        if(finished||tick<20)return null;
        finished=true;
        var receipt=new LinkedHashMap<String,Object>();
        var rows=new ArrayList<Map<String,Object>>();receipt.put("rows",rows);
        receipt.put("tick",tick);receipt.put("quotes_installed_by_probe",0);receipt.put("reloads_requested_by_probe",0);
        receipt.put("world_blocks_or_inventories_modified",0);receipt.put("full_mod_gate","UNCHANGED_INCOMPLETE");
        try {
            require(world.getServer().isSameThread(),"server thread");
            byte[] fixtureBytes;try(var input=Files.newInputStream(INPUT)){fixtureBytes=bounded(input);}
            var fixture=JsonParser.parseString(new String(fixtureBytes,StandardCharsets.UTF_8)).getAsJsonObject();
            receipt.put("fixture_sha256",sha(fixtureBytes));
            require(fixture.get("schema").getAsInt()==1&&fixture.get("case_count").getAsInt()==12&&fixture.get("families").getAsInt()==6,"fixture schema and scope");
            require(near(fixture.get("expected_input_raw").getAsDouble(),15.0)
                    &&near(fixture.get("expected_plain_paper_raw").getAsDouble(),194426.14814814812),"frozen reference anchors");
            var observedBytes=resource(world,"mio_icif:uu/observed_1122.json");
            require(sha(observedBytes).equals(fixture.get("observed_catalog_sha256").getAsString()),"active observed reference source SHA");
            receipt.put("observed_catalog_sha256",sha(observedBytes));
            var policyBytes=resource(world,"mio_icif:uu/processing_policy.json");
            var policy=UuProcessingPolicy.read(new StringReader(new String(policyBytes,StandardCharsets.UTF_8)));
            for(String family:FAMILIES){
                Double overhead=policy.explicitFamilyOverheads().get(UuProcessingRecipes.Family.valueOf(family));
                require(overhead!=null&&near(overhead,14.0/100000.0),"active 14 raw policy for "+family);
            }
            receipt.put("processing_policy_sha256",sha(policyBytes));
            var lifecycle=UuPricingLifecycle.report(world.getServer());
            long generation=UuQuoteBook.generation(world.getServer());
            require(lifecycle!=null&&generation>0&&lifecycle.generation()==generation
                    &&lifecycle.status().equals("LOADED_SCOPED_REFERENCE_AND_RECIPES"),"normal startup generation loaded");
            receipt.put("normal_generation",generation);receipt.put("lifecycle_status",lifecycle.status());
            quote(world,new ItemStack(Items.STONE),15.0/100000.0,generation,"ordinary stone retains frozen raw 15 reference");
            var plainPaper=new ItemStack(Items.PAPER);
            var plain=quote(world,plainPaper,194426.14814814812/100000.0,generation,"unmarked paper retains independent frozen reference quote");
            receipt.put("plain_paper",snapshot(world,plainPaper));receipt.put("plain_paper_raw",plain.buckets()*100000.0);
            var cases=fixture.getAsJsonArray("cases");require(cases.size()==12,"exactly twelve fixtures");
            var ids=new HashSet<String>();var familyCounts=new LinkedHashMap<String,Integer>();
            var keys=new HashSet<IndependentUuValueIndex.Key>();
            for(var element:cases){
                JsonObject row=element.getAsJsonObject();String family=row.get("family").getAsString();
                require(ids.add(row.get("case_id").getAsString())&&FAMILIES.contains(family),"unique cases and expected families");
                familyCounts.merge(family,1,Integer::sum);rows.add(inspectOne(world,row,generation,keys));
            }
            require(familyCounts.keySet().equals(FAMILIES)&&familyCounts.values().stream().allMatch(v->v==2),"two cases per six families");
            require(rows.stream().allMatch(r->Boolean.TRUE.equals(r.get("passed"))),"all twelve normal-loader quotes");
            require(UuQuoteBook.generation(world.getServer())==generation,"probe preserves startup generation");
            quote(world,plainPaper,plain.buckets(),generation,"unmarked paper remains independent after all queries");
            receipt.put("families",familyCounts);receipt.put("passed",true);receipt.put("status","PASS_SCOPED_SIX_PROCESSING_FAMILY_QUOTES");
        } catch(Exception failure) {
            receipt.put("passed",false);receipt.put("status","FAIL_SCOPED_PROCESSING_QUOTES");
            receipt.put("failure_type",failure.getClass().getName());receipt.put("failure_message",String.valueOf(failure.getMessage()));
        }
        receipt.put("cases",rows.size());receipt.put("passed_cases",rows.stream().filter(r->Boolean.TRUE.equals(r.get("passed"))).count());
        receipt.put("scope","Normal loader plus startup quote generation only; no machine execution, recipe-manager injection, price install or reload.");
        Files.writeString(OUTPUT,new GsonBuilder().setPrettyPrinting().create().toJson(receipt)+"\n",StandardCharsets.UTF_8);
        require(Boolean.TRUE.equals(receipt.get("passed")),"receipt failed; inspect "+OUTPUT);
        return receipt;
    }
}
