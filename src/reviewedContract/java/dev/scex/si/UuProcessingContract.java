// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.singularity_iteration.mio_icif.recipe.mio_icif_PowderRecipe;
import com.singularity_iteration.mio_icif.recipe.compressor.mio_icif_CompressorRecipe;
import com.singularity_iteration.mio_icif.recipe.extractor.mio_icif_ExtractorRecipe;
import com.singularity_iteration.mio_icif.recipe.metal_former.cutting.mio_icif_CuttingRecipe;
import com.singularity_iteration.mio_icif.recipe.metal_former.extruding.mio_icif_ExtrudingRecipe;
import com.singularity_iteration.mio_icif.recipe.metal_former.rolling.mio_icif_RollingRecipe;
import dev.scex.si.processing.IndependentUuValueIndex;
import dev.scex.si.processing.UuProcessingRecipes;
import dev.scex.si.processing.UuProcessingPolicy;
import dev.scex.si.processing.UuProcessingRecipes.Family;
import dev.scex.si.processing.UuProcessingRecipes.PricingPolicy;
import dev.scex.si.processing.UuRecipeSolver;
import java.nio.file.Path;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;

/** Actual adapter/recipe-object contracts. Explicit test policies are not reference-parity evidence. */
public final class UuProcessingContract {
    private static int assertions;
    private static HolderLookup.Provider registries;
    private UuProcessingContract() { }
    private static void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }
    private static void close(double actual, double expected, String label) {
        check(Math.abs(actual - expected) <= Math.max(Math.abs(expected), 1e-20) * 1e-12, label);
    }
    private static ItemStack stone() { return new ItemStack(Items.STONE); }
    private static RecipeHolder<?> holder(String name, Recipe<?> recipe) {
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath("scex_contract", name), recipe);
    }
    private static SmeltingRecipe smelt(Ingredient input, ItemStack output) {
        return new SmeltingRecipe("", CookingBookCategory.MISC, input, output, 0, 200);
    }
    private static PricingPolicy explicit(Family family, double overhead) {
        return new PricingPolicy(Map.of(family, overhead), false);
    }
    private static UuProcessingRecipes.Catalog read(Recipe<?> recipe, PricingPolicy policy) {
        return UuProcessingRecipes.read(List.of(holder("one", recipe)), registries, policy);
    }
    private static Recipe<?> processing(Family family, Ingredient input, int count, ItemStack output) {
        return switch (family) {
            case SMELTING -> smelt(input, output);
            case POWDER -> new mio_icif_PowderRecipe("", input, output, 100, 2);
            case COMPRESSOR -> new mio_icif_CompressorRecipe("", input, output, 100, 2, count);
            case EXTRACTOR -> new mio_icif_ExtractorRecipe("", input, output, 100, 2);
            case ROLLING -> new mio_icif_RollingRecipe("", input, output, 100, 2, count);
            case CUTTING -> new mio_icif_CuttingRecipe("", input, output, 100, 2, count);
            case EXTRUDING -> new mio_icif_ExtrudingRecipe("", input, output, 100, 2, count);
        };
    }
    private static void observedPolicy() {
        var sand = smelt(Ingredient.of(Items.SAND, Items.RED_SAND), new ItemStack(Items.GLASS));
        var catalog = read(sand, PricingPolicy.observedOnly());
        check(catalog.rules().size() == 1, "Observed sand route accepted within vanilla alternatives");
        var rule = catalog.rules().getFirst();
        check(rule.slots().size() == 1 && rule.slots().getFirst().size() == 1,
                "Unobserved red-sand alternative is not assigned an overhead");
        check(rule.slots().getFirst().getFirst().consumed().equals(
                IndependentUuValueIndex.keyOf(new ItemStack(Items.SAND))), "Retain exact sand identity");
        var solver = new UuRecipeSolver<IndependentUuValueIndex.Key>(64, 10000);
        var glass = IndependentUuValueIndex.keyOf(new ItemStack(Items.GLASS));
        var anchors = Map.of(IndependentUuValueIndex.keyOf(new ItemStack(Items.SAND)), 15.0 / 100000.0);
        var result = solver.solve(anchors, Set.of(), catalog.rules());
        check(result.complete(), "Observed transform solves completely");
        close(result.values().get(glass), 29.0 / 100000.0, "R93 sand/glass raw-to-bucket equation");
        var fixed = new java.util.HashMap<>(anchors);
        fixed.put(glass, 0.001);
        close(solver.solve(fixed, Set.of(), catalog.rules()).values().get(glass), 0.001,
                "Fixed reference output cannot be overwritten by processing rule");
        check(!solver.solve(anchors, Set.of(glass), catalog.rules()).values().containsKey(glass),
                "Denied reference output remains denied");
        check(read(sand, PricingPolicy.disabled()).policyDisabled() == 1, "Explicitly disabled means no default route");
        check(read(smelt(Ingredient.of(Items.RED_SAND), new ItemStack(Items.GLASS)),
                PricingPolicy.observedOnly()).policyDisabled() == 1, "No red-sand inference");
        check(read(smelt(Ingredient.of(Items.CLAY_BALL), new ItemStack(Items.BRICK)),
                PricingPolicy.observedOnly()).policyDisabled() == 1, "Ambiguous legacy clay identity stays disabled");
        check(read(smelt(Ingredient.of(Items.SAND), new ItemStack(Items.GLASS, 2)),
                PricingPolicy.observedOnly()).policyDisabled() == 1, "Observation cannot price altered output multiplicity");
        close(read(sand, explicit(Family.SMELTING, 0.003)).rules().getFirst().overhead(), 0.003,
                "Explicit policy has distinct, visible authority from default observation");
    }
    private static void quantityAndAlternatives() {
        for (Family family : Family.values()) {
            int count = switch (family) {
                case COMPRESSOR -> 9;
                case ROLLING -> 3;
                case CUTTING -> 2;
                case EXTRUDING -> 4;
                default -> 1;
            };
            var recipe = processing(family, Ingredient.of(Items.COBBLESTONE, Items.DIRT), count,
                    new ItemStack(Items.STONE, 3));
            check(read(recipe, PricingPolicy.observedOnly()).rules().isEmpty(), family + " no guessed family price");
            var catalog = read(recipe, explicit(family, 0.0002));
            check(catalog.rules().size() == 1 && catalog.unsupported() == 0 && catalog.policyDisabled() == 0,
                    family + " exact recipe class supported under explicit policy");
            var rule = catalog.rules().getFirst();
            check(rule.slots().size() == count && rule.outputCount() == 3, family + " full quantities preserved");
            var values = new UuRecipeSolver<IndependentUuValueIndex.Key>(64, 10000).solve(Map.of(
                    IndependentUuValueIndex.keyOf(new ItemStack(Items.COBBLESTONE)), 0.001,
                    IndependentUuValueIndex.keyOf(new ItemStack(Items.DIRT)), 0.007), Set.of(), catalog.rules());
            check(values.complete(), family + " bounded accounting completes");
            close(values.values().get(IndependentUuValueIndex.keyOf(stone())),
                    (count * 0.001 + 0.0002) / 3, family + " consume count, one overhead, divide by output count");
            check(rule.slots().stream().flatMap(List::stream).allMatch(choice -> choice.returned() == null),
                    family + " no invented container credit");
        }
    }
    private static void reject(Recipe<?> recipe, String label) {
        var all = new EnumMap<Family, Double>(Family.class);
        for (var family : Family.values()) all.put(family, 0.001);
        var catalog = read(recipe, new PricingPolicy(all, true));
        check(catalog.rules().isEmpty() && catalog.unsupported() == 1, label);
    }
    private static void conservativeBounds() {
        var craftingInputs = NonNullList.<Ingredient>create();
        craftingInputs.add(Ingredient.of(Items.COBBLESTONE));
        var crafting = read(new ShapelessRecipe("", CraftingBookCategory.MISC, stone(), craftingInputs),
                PricingPolicy.observedOnly());
        check(crafting.rules().isEmpty() && crafting.unsupported() == 0 && crafting.policyDisabled() == 0,
                "Crafting adapter owns crafting rejection statistics");
        for (Family family : List.of(Family.COMPRESSOR, Family.ROLLING, Family.CUTTING, Family.EXTRUDING)) {
            for (int count : List.of(-1, 0, 65))
                reject(processing(family, Ingredient.of(Items.COBBLESTONE), count, stone()), family + " invalid count " + count);
            reject(processing(family, Ingredient.of(Items.DIAMOND_SWORD), 2, stone()),
                    family + " input count cannot fit the single legal stack");
        }
        reject(smelt(Ingredient.EMPTY, stone()), "Empty input cannot invent a quote");
        reject(smelt(Ingredient.of(Items.COBBLESTONE), ItemStack.EMPTY), "Empty output rejected");
        reject(smelt(Ingredient.of(Items.COBBLESTONE), new ItemStack(Items.STONE, 65)), "Oversized output rejected");
        reject(smelt(Ingredient.of(Items.COBBLESTONE), new ItemStack(Items.DIAMOND_SWORD, 2)), "Unstackable output count rejected");
        reject(smelt(Ingredient.of(new ItemStack(Items.COBBLESTONE, 2)), stone()), "Ingredient example count is not consumption");
        var named = new ItemStack(Items.COBBLESTONE);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("input identity"));
        reject(smelt(Ingredient.of(named), stone()), "Vanilla ingredient ignores components; do not approximate them");
        reject(smelt(DataComponentIngredient.of(true, named), stone()), "Custom component predicate is not flattened");
        reject(smelt(Ingredient.of(Items.WATER_BUCKET), stone()), "Fluid input rejected");
        reject(smelt(Ingredient.of(Items.MILK_BUCKET), stone()), "Container-return input rejected");
        reject(smelt(Ingredient.of(Items.COBBLESTONE), new ItemStack(Items.WATER_BUCKET)), "Fluid output rejected");
        var nested = stone();
        nested.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND))));
        reject(smelt(Ingredient.of(Items.COBBLESTONE), nested), "Nested contents require explicit separate accounting");
        reject(new SmeltingRecipe("", CookingBookCategory.MISC, Ingredient.of(Items.COBBLESTONE), stone(), 0, 20) {
            @Override public ItemStack getResultItem(HolderLookup.Provider lookup) {
                throw new AssertionError("Unknown recipe subclass must not be inspected");
            }
        }, "Dynamic smelting subclass rejected before invoking its methods");
        reject(new mio_icif_PowderRecipe("", Ingredient.of(Items.COBBLESTONE), stone(), 100, 2) {
            @Override public ItemStack getResultItem(HolderLookup.Provider lookup) {
                throw new AssertionError("Unknown machine subclass must not be inspected");
            }
        }, "Dynamic SI subclass rejected before invoking its methods");
    }
    private static void componentIdentityAndImmutability() {
        var output = stone();
        output.set(DataComponents.CUSTOM_NAME, Component.literal("exact output"));
        var expected = IndependentUuValueIndex.keyOf(output);
        var result = read(smelt(Ingredient.of(Items.COBBLESTONE), output), explicit(Family.SMELTING, 0.001));
        check(result.rules().size() == 1, "Fixed component-bearing output remains representable");
        var rule = result.rules().getFirst();
        check(rule.output().equals(expected) && !rule.output().equals(IndependentUuValueIndex.keyOf(stone())),
                "Output components are part of its exact identity");
        output.set(DataComponents.CUSTOM_NAME, Component.literal("mutated later"));
        check(rule.output().equals(expected), "Recipe result mutation cannot alter frozen quote keys");
        try { result.rules().clear(); throw new AssertionError("Mutable catalog"); }
        catch (UnsupportedOperationException expectedFailure) { check(true, "Catalog immutable"); }
        try { rule.slots().getFirst().clear(); throw new AssertionError("Mutable ingredient choices"); }
        catch (UnsupportedOperationException expectedFailure) { check(true, "Rule choices immutable"); }
        var policySource = new EnumMap<Family, Double>(Family.class);
        policySource.put(Family.SMELTING, 0.001);
        var policy = new PricingPolicy(policySource, false);
        policySource.put(Family.SMELTING, 0.3);
        close(policy.explicitFamilyOverheads().get(Family.SMELTING), 0.001, "External policy map copied");
        for (double invalid : List.of(0.0, -0.001, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
            try { explicit(Family.SMELTING, invalid); throw new AssertionError("Invalid policy accepted"); }
            catch (IllegalArgumentException expectedFailure) { check(true, "Invalid overhead rejected " + invalid); }
        }
        var first = holder("a", smelt(Ingredient.of(Items.COBBLESTONE), stone()));
        var second = holder("z", smelt(Ingredient.of(Items.DIRT), new ItemStack(Items.GRANITE)));
        var forwards = UuProcessingRecipes.read(List.of(first, second), registries, policy);
        var backwards = UuProcessingRecipes.read(List.of(second, first), registries, policy);
        check(forwards.rules().equals(backwards.rules()), "Frozen catalog order is deterministic");
        var excessive = new ArrayList<RecipeHolder<?>>();
        for (int i = 0; i <= 32768; i++) excessive.add(first);
        try { UuProcessingRecipes.read(excessive, registries, policy); throw new AssertionError("Unbounded catalog"); }
        catch (IllegalArgumentException expectedFailure) { check(true, "Catalog work bounded before traversal"); }
    }
    private static void policyResource() throws Exception {
        String body = "{\"schema\":1,\"raw_overheads\":{\"SMELTING\":14},\"observed_sand\":true,\"basis\":\"Independent extension policy\"}";
        var policy = UuProcessingPolicy.read(new StringReader(body));
        close(policy.explicitFamilyOverheads().get(Family.SMELTING), 0.00014, "External raw cost converted once to buckets");
        check(policy.observedSandSmelting() && policy.explicitFamilyOverheads().size() == 1,
                "External policy enables only declared family and observation");
        check(UuProcessingPolicy.read(new StringReader("{\"schema\":1,\"raw_overheads\":{}}"))
                .equals(PricingPolicy.disabled()), "Omitted observation does not imply authorization");
        check(UuProcessingPolicy.read(new StringReader(body.replace("Independent extension policy", "Different explanation")))
                .equals(policy), "Basis metadata cannot change arithmetic");
        var clay = smelt(Ingredient.of(Items.CLAY_BALL), new ItemStack(Items.BRICK));
        check(read(clay, policy).rules().size() == 1, "Explicit resource policy extends whole fixed smelting class");
        for (String invalid : List.of(
                body.replace("\"schema\":1", "\"schema\":2"),
                body.replace("\"schema\":1", "\"schema\":1.00000000000000001"),
                body.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
                body.replace("\"SMELTING\":14", "\"SMELTING\":14,\"SMELTING\":15"),
                body.replace("\"schema\":1", "\"schema\":\"1\""),
                body.replace("\"SMELTING\"", "\"UNKNOWN\""),
                body.replace(":14", ":0"), body.replace(":14", ":-1"),
                body.replace(":14", ":1e309"), body.replace(":14", ":100000001"),
                body.replace(":14", ":\"14\""), body.replace(":14", ":null"),
                body.replace("\"observed_sand\":true", "\"observed_sand\":\"true\""),
                body.replace("\"basis\":\"Independent extension policy\"", "\"unknown\":1"),
                body.replace("\"Independent extension policy\"", "{}"),
                body.replace("Independent extension policy", "x".repeat(65536)),
                body.replace("Independent extension policy", "界".repeat(24000)))) {
            try { UuProcessingPolicy.read(new StringReader(invalid)); throw new AssertionError("Bad policy resource accepted"); }
            catch (IllegalArgumentException expectedFailure) { check(true, "Malformed or oversized policy fails closed"); }
        }
        close(UuProcessingPolicy.read(new StringReader(body.replace(":14", ":100000000")))
                .explicitFamilyOverheads().get(Family.SMELTING), 1000, "Maximum raw overhead accepted exactly");
    }
    public static void main(String[] args) throws Exception {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        for (Class<?> type : List.of(UuProcessingRecipes.class, UuProcessingPolicy.class, UuRecipeSolver.class)) {
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
                    .equals(Path.of(args[0]).toRealPath()), "Load actual compiled candidate class " + type.getName());
        }
        observedPolicy();
        quantityAndAlternatives();
        conservativeBounds();
        componentIdentityAndImmutability();
        policyResource();
        System.out.println("SCEX_UU_PROCESSING assertions=" + assertions + " PASS");
    }
}
