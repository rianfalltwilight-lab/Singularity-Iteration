// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.Environment.fluid.mio_icif_fluids;
import com.singularity_iteration.mio_icif.Blocks.Producer.mio_icif_block_replicator_elc;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_replicator_elc;
import com.singularity_iteration.mio_icif.Items.Resource.mio_icif_memory;
import dev.scex.si.processing.UuPricingLifecycle;
import dev.scex.si.processing.UuQuoteBook;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Registered identities, ordinary memory/replication and resource reloads; never installs test quotes. */
public final class UuCatalogWorldProbe {
    private static final int EXPECTED_ENTRIES=347, EXPECTED_FINITE=147, EXPECTED_DENIED=200;
    private static final String RESOURCE = "data/mio_icif/uu/mapped_ic2.json";
    private static final ResourceLocation RESOURCE_ID = ResourceLocation.fromNamespaceAndPath("mio_icif", "uu/mapped_ic2.json");
    private static final Path PACK = Path.of("world/datapacks/scex-uu-catalog-test");
    private static final double RATE = 0.0001;
    private static final long EU_PER_WORK_TICK = 512;
    private final List<String> groups = new ArrayList<>();
    private final List<UuPricingLifecycle.Report> reports = new ArrayList<>();
    private final List<Map<String, Object>> copies = new ArrayList<>();
    private JsonObject fixture;
    private String originalCatalog;
    private JsonObject changedCatalog;
    private mio_icif_memory memory;
    private Block replicatorBlock;
    private final ItemStack[] copiedItems = new ItemStack[2];
    private final double[] originalPrices = new double[2];
    private final long[] copyEu = new long[2];
    private BlockPos origin;
    private int assertions, finiteEntries, deniedEntries, originalUnmapped, initialFluid;
    private long initialEu, originalGeneration, changedGeneration, stoppedEu;
    private int stoppedFluid;
    private double stoppedCredit, stoppedProcessed;
    private long stoppedCompleted;
    private UuQuoteBook.Quote originalChangedQuote;

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError("R100 catalog: " + label);
    }
    private void near(double actual, double expected, String label) {
        check(Math.abs(actual - expected) < 1e-12, label + " actual=" + actual + " expected=" + expected);
    }
    private ItemStack item(String target) {
        var id = ResourceLocation.parse(target);
        check(BuiltInRegistries.ITEM.containsKey(id), "Registered item " + target);
        var value = new ItemStack(BuiltInRegistries.ITEM.get(id));
        check(!value.isEmpty() && value.getCount() == 1, "Nonempty default stack " + target);
        return value;
    }
    private BlockPos pos(int index) { return origin.offset(index * 4, 0, 0); }
    private mio_icif_replicator_elc machine(ServerLevel world, int index) {
        var entity = world.getBlockEntity(pos(index));
        check(entity instanceof mio_icif_replicator_elc, "Actual registered replicator entity " + index);
        return (mio_icif_replicator_elc) entity;
    }
    private UuPricingLifecycle.Report report(ServerLevel world) {
        var result = UuPricingLifecycle.report(world.getServer());
        check(result != null, "Normal lifecycle report exists");
        return result;
    }
    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    private void writeCatalog(JsonObject catalog) throws Exception {
        var path = PACK.resolve(RESOURCE);
        Files.createDirectories(path.getParent());
        Files.writeString(path, new Gson().toJson(catalog));
    }
    private JsonObject unknownRow(int offset, String target, String raw) {
        var row = new JsonObject();
        row.addProperty("legacy_stack", "{id:\"ic2:crafting\",Count:1b,Damage:"
                + (fixture.get("unknown_legacy_damage").getAsInt() - offset) + "s}");
        row.addProperty("target_item", target);
        row.addProperty("raw_value", raw);
        row.addProperty("source_row", fixture.get("unknown_source_row").getAsInt() + offset);
        return row;
    }
    private Map<String, Object> verifyCatalog(ServerLevel world, JsonObject expected, long generation, boolean allowUnknown) {
        int finite = 0, denied = 0, unknown = 0;
        var seen = new HashSet<String>();
        var rows = new ArrayList<Map<String, Object>>();
        for (var value : expected.getAsJsonArray("entries")) {
            var entry = value.getAsJsonObject();
            String target = entry.get("target_item").getAsString();
            check(seen.add(target), "Unique fixture target " + target);
            if (!BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(target))) {
                check(allowUnknown && target.equals(fixture.get("unknown_target").getAsString()),
                        "Only explicitly injected unknown target is unmapped");
                unknown++;
                continue;
            }
            ItemStack stack = entry.has("target_stack")
                    ? dev.scex.si.processing.UuMappedCatalog.explicitStack(entry.get("target_stack").getAsString(),target,world.registryAccess()) : item(target);
            var roundtrip = ItemStack.parse(world.registryAccess(), stack.save(world.registryAccess())).orElse(ItemStack.EMPTY);
            check(ItemStack.isSameItemSameComponents(stack, roundtrip), "Full default components survive serialization " + target);
            var quote = UuQuoteBook.quote(world.getServer(), stack);
            String raw = entry.get("raw_value").getAsString();
            if (raw.equals("Infinity")) {
                denied++;
                check(quote == null, "Explicit Infinity has no quote " + target);
            } else {
                finite++;
                double expectedPrice = Double.parseDouble(raw) / 100000.0;
                check(Double.isFinite(expectedPrice) && expectedPrice > 0, "Positive finite expected quote " + target);
                check(quote != null && quote.generation() == generation, "Current generation quotes exact target " + target);
                near(quote.buckets(), expectedPrice, "Exact mapped amount " + target);
                var named = stack.copy();
                named.set(DataComponents.CUSTOM_NAME, Component.literal("SCEX UU identity boundary " + target));
                check(!ItemStack.isSameItemSameComponents(named, stack)
                        && UuQuoteBook.quote(world.getServer(), named) == null,
                        "Reference authority does not erase additional components " + target);
            }
            var componentVariant = stack.copy();
            var marker = componentVariant.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            marker.putString("scex_r102_catalog_variant", target);
            componentVariant.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
            check(!ItemStack.isSameItemSameComponents(componentVariant, stack)
                    && UuQuoteBook.quote(world.getServer(), componentVariant)==null,
                    "Mapped finite/Infinity identities never quote a changed CUSTOM_DATA variant " + target);
            rows.add(Map.of("target_item", target, "raw_value", raw, "default_components_hash", stack.getComponents().hashCode(),
                    "quoted", quote != null, "generation", quote == null ? -1L : quote.generation()));
        }
        check(finite + denied + unknown == expected.getAsJsonArray("entries").size(), "Every resource row checked");
        return Map.of("finite", finite, "denied", denied, "unmapped", unknown, "rows", rows);
    }
    private void createCopy(ServerLevel world, int index) {
        check(world.setBlockAndUpdate(pos(index), replicatorBlock.defaultBlockState()), "Place real replicator " + index);
        var crystal = new ItemStack(memory);
        check(memory.tryStoreData(crystal, copiedItems[index], 99, 999999), "Prepare deliberately stale memory " + index);
        var tile = machine(world, index);
        tile.setItem(mio_icif_replicator_elc.MEMORY_SLOT, crystal);
        tile.getEnergyStorageInternal().setEnergy(initialEu);
        check(tile.getFluidHandlerCapability(Direction.UP).fill(new FluidStack(mio_icif_fluids.UUMATTER.get(), initialFluid),
                IFluidHandler.FluidAction.EXECUTE) == initialFluid, "Fill through public UU capability " + index);
        tile.generateOnce();
    }
    private void assertCopy(ServerLevel world, int index, int count) {
        var tile = machine(world, index);
        var output = tile.getItemHandler().getStackInSlot(mio_icif_replicator_elc.OUTPUT_SLOT);
        check(output.getCount() == count && ItemStack.isSameItemSameComponents(output, copiedItems[index]),
                "Single operation produces exact default item identity and count " + index);
        check(tile.getTotalProcessed() == count && tile.getWorkMode() == mio_icif_replicator_elc.WorkMode.STOPPED,
                "Completed single mode remains stopped " + index);
        check(!tile.hasHeldReplicationData(), "Known unambiguous copy not held " + index);
        check(tile.getEnergyStorageInternal().getAmount() == initialEu - copyEu[index] * count,
                "Exactly 512 EU per paid work tick " + index);
        near((initialFluid - tile.getUuMatterAmount()) / 1000.0 - tile.getUuCreditBuckets(),
                originalPrices[index] * count, "Exact UU debit including fractional credit " + index);
        near(tile.getProcessedUuBuckets(), 0, "Completed copy has no paid remainder " + index);
    }
    private void writeSmeltingExtension() throws Exception {
        var spec = fixture.getAsJsonObject("smelting_extension");
        var recipe = new JsonObject();
        recipe.addProperty("type", "minecraft:smelting");
        recipe.addProperty("category", "misc");
        var input = new JsonObject(); input.addProperty("item", spec.get("input_item").getAsString());
        var output = new JsonObject(); output.addProperty("id", spec.get("output_item").getAsString());
        output.addProperty("count", spec.get("output_count").getAsInt());
        recipe.add("ingredient", input); recipe.add("result", output);
        recipe.addProperty("experience", 0); recipe.addProperty("cookingtime", 200);
        var path = PACK.resolve("data/scex/recipe/uu_catalog_extension.json");
        Files.createDirectories(path.getParent()); Files.writeString(path, new Gson().toJson(recipe));
    }
    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        if (tick == 20) {
            fixture = JsonParser.parseString(Files.readString(Path.of("uu-catalog-world.json"))).getAsJsonObject();
            check(fixture.get("schema").getAsInt() == 1, "Known world fixture schema");
            var xyz = fixture.getAsJsonArray("machine_origin");
            check(xyz.size() == 3, "Three machine coordinates");
            origin = new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt());
            initialEu = fixture.get("initial_eu").getAsLong(); initialFluid = fixture.get("initial_uu_mb").getAsInt();
            check(initialEu > 0 && initialEu <= mio_icif_replicator_elc.DEFAULT_CAPACITY
                    && initialFluid > 0 && initialFluid <= mio_icif_replicator_elc.UUMATTER_CAPACITY, "Bounded machine fixture supply");
            var initial = report(world);
            check(initial.generation() >= 0 && UuQuoteBook.generation(world.getServer()) == initial.generation(),
                    "Normal startup owns a valid generation");
            originalGeneration = initial.generation(); originalUnmapped = initial.unmapped(); reports.add(initial);
            try (var input = UuPricingLifecycle.class.getResourceAsStream("/" + RESOURCE)) {
                check(input != null, "Mapped catalog is in the actual mod resource overlay");
                originalCatalog = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            check(hash(originalCatalog).equals(fixture.get("mapped_sha256").getAsString()), "Mapped catalog hash matches frozen source");
            var base = JsonParser.parseString(originalCatalog).getAsJsonObject();
            check(fixture.get("mapped_entries").getAsInt()==EXPECTED_ENTRIES
                    && base.getAsJsonArray("entries").size()==EXPECTED_ENTRIES,
                    "Frozen R109 mapped entry count is exactly 347");
            var audit = verifyCatalog(world, base, originalGeneration, false);
            finiteEntries = (Integer) audit.get("finite"); deniedEntries = (Integer) audit.get("denied");
            check(finiteEntries==EXPECTED_FINITE && deniedEntries==EXPECTED_DENIED
                    && (Integer)audit.get("unmapped")==0,
                    "All 347 R109 identities: 147 finite, 200 Infinity, zero unmapped");
            var eligibilityFile = "data/mio_icif/uu/scan_eligibility.json";
            String eligibilityText;
            try (var input = UuPricingLifecycle.class.getResourceAsStream("/" + eligibilityFile)) {
                check(input != null, "Actual eligibility resource exists");
                eligibilityText = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            check(hash(eligibilityText).equals(fixture.get("eligibility_sha256").getAsString()), "Exact R105 eligibility resource");
            var eligible = JsonParser.parseString(eligibilityText).getAsJsonObject().getAsJsonArray("denied_scan_items");
            check(eligible.size()==22, "Twenty-two observed canonical paid-denial identities");
            var eligibleNames = new HashSet<String>();
            for (var value : eligible) {
                var name=value.getAsString(); check(eligibleNames.add(name), "Unique denial admission " + name);
                var stack=item(name); var assessment=UuQuoteBook.classify(world.getServer(),stack);
                check(assessment.disposition()==UuQuoteBook.Disposition.KNOWN_DENIED && assessment.deniedScanEligible()
                        && assessment.finite()==null, "Normal loader admits exact paid-denial identity " + name);
                var marker=new net.minecraft.nbt.CompoundTag(); marker.putString("r105_unobserved",name);
                stack.set(DataComponents.CUSTOM_DATA,CustomData.of(marker));
                var variant=UuQuoteBook.classify(world.getServer(),stack);
                check(variant.disposition()==UuQuoteBook.Disposition.UNSUPPORTED && !variant.deniedScanEligible()
                        && variant.finite()==null, "Changed default components gain neither quote nor denial eligibility " + name);
            }
            Files.writeString(Path.of("uu-catalog-identities.json"), new Gson().toJson(audit));
            var memories = BuiltInRegistries.ITEM.stream().filter(value -> value instanceof mio_icif_memory).toList();
            var blocks = BuiltInRegistries.BLOCK.stream().filter(value -> value instanceof mio_icif_block_replicator_elc).toList();
            check(memories.size() == 1 && blocks.size() == 1, "Unique registered crystal and replicator");
            memory = (mio_icif_memory) memories.getFirst(); replicatorBlock = blocks.getFirst();
            var targets = fixture.getAsJsonArray("copy_targets");
            check(targets.size() == 2 && !targets.get(0).equals(targets.get(1)), "Two distinct mapped material cases");
            for (int index = 0; index < 2; index++) {
                String target = targets.get(index).getAsString();
                check(target.startsWith("mio_icif:"), "Copy representative mod material " + target);
                copiedItems[index] = item(target);
                var quote = UuQuoteBook.quote(world.getServer(), copiedItems[index]);
                check(quote != null && quote.buckets() <= 0.004, "Cheap positively quoted material fits observation window");
                originalPrices[index] = quote.buckets(); copyEu[index] = (long) Math.ceil(quote.buckets() / RATE) * EU_PER_WORK_TICK;
                check(initialEu >= copyEu[index] * 3 && initialFluid / 1000.0 > quote.buckets() * 3,
                        "Supply covers representative copies without refilling");
                createCopy(world, index);
            }
            check(fixture.get("changed_target").getAsString().equals(targets.get(0).getAsString()), "First material is reload target");
            originalChangedQuote = UuQuoteBook.quote(world.getServer(), copiedItems[0]);
            check(!BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(fixture.get("unknown_target").getAsString())),
                    "Unmapped fixture target does not accidentally exist");
            var extension = fixture.getAsJsonObject("smelting_extension");
            check(UuQuoteBook.quote(world.getServer(), item(extension.get("output_item").getAsString())) == null,
                    "Smelting extension output starts unquoted");
            groups.add("normal-startup-all-mapped-identities-components-finite-and-infinity");
        }
        if (tick == 90) {
            for (int index = 0; index < 2; index++) {
                assertCopy(world, index, 1);
                var crystal = machine(world, index).getItemHandler().getStackInSlot(mio_icif_replicator_elc.MEMORY_SLOT);
                near(memory.getUuMatterCost(crystal), originalPrices[index], "Stale99 actual inventory memory repriced " + index);
                check(ItemStack.isSameItemSameComponents(memory.getStoredItemStack(crystal), copiedItems[index]),
                        "Memory preserves full representative item identity " + index);
                copies.add(Map.of("target_item", BuiltInRegistries.ITEM.getKey(copiedItems[index].getItem()).toString(),
                        "buckets", originalPrices[index], "eu", copyEu[index], "count", 1));
            }
            groups.add("two-registered-material-single-copies-and-stale-memory-migration");
        }
        if (tick == 100) {
            changedCatalog = JsonParser.parseString(originalCatalog).getAsJsonObject();
            int edited = 0;
            for (var value : changedCatalog.getAsJsonArray("entries")) {
                var row = value.getAsJsonObject();
                if (row.get("target_item").getAsString().equals(fixture.get("changed_target").getAsString())) {
                    row.addProperty("raw_value", fixture.get("changed_raw_value").getAsString()); edited++;
                }
            }
            check(edited == 1, "Exactly one mapped source price changed");
            changedCatalog.getAsJsonArray("entries").add(unknownRow(0, fixture.get("unknown_target").getAsString(), "123"));
            writeCatalog(changedCatalog);
            Files.writeString(PACK.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":48,\"description\":\"Isolated UU catalog validation\"}}");
            writeSmeltingExtension();
        }
        if (tick == 200) {
            var changed = report(world); reports.add(changed);
            check(changed.generation() > originalGeneration, "Actual datapack enable publishes new generation");
            changedGeneration = changed.generation();
            check(changed.unmapped() == originalUnmapped + 1, "Unknown target is counted once without fabricating identity");
            verifyCatalog(world, changedCatalog, changedGeneration, true);
            var quote = UuQuoteBook.quote(world.getServer(), copiedItems[0]);
            near(quote.buckets(), Double.parseDouble(fixture.get("changed_raw_value").getAsString()) / 100000.0,
                    "Changed mapped price is active");
            check(quote.generation() != originalChangedQuote.generation() && quote.buckets() != originalChangedQuote.buckets(),
                    "Retained prior quote is no longer the server's authorized generation");
            try (var reader = world.getServer().getResourceManager().getResource(RESOURCE_ID).orElseThrow().openAsReader()) {
                check(JsonParser.parseReader(reader).equals(changedCatalog), "Actual resource manager resolves the enabled override");
            }
            near(memory.getUuMatterCost(machine(world, 0).getItemHandler().getStackInSlot(mio_icif_replicator_elc.MEMORY_SLOT)),
                    quote.buckets(), "Actual completed memory observes changed generation without recopying");
            check(machine(world, 0).getTotalProcessed() == 1 && machine(world, 1).getTotalProcessed() == 1,
                    "Reload alone cannot repeat completed single copies");
            var extension = fixture.getAsJsonObject("smelting_extension");
            var input = UuQuoteBook.quote(world.getServer(), item(extension.get("input_item").getAsString()));
            var output = UuQuoteBook.quote(world.getServer(), item(extension.get("output_item").getAsString()));
            check(input != null && output != null, "Formal policy evaluates real new smelting recipe");
            near(output.buckets(), (input.buckets() + extension.get("expected_raw_overhead").getAsDouble() / 100000.0)
                    / extension.get("output_count").getAsInt(), "Smelting extension cost and output multiplicity");
            groups.add("normal-reload-changed-mapped-price-unmapped-target-and-smelting-extension");
        }
        if (tick == 220) {
            var conflict = changedCatalog.deepCopy();
            conflict.getAsJsonArray("entries").add(unknownRow(1, fixture.get("changed_target").getAsString(), "Infinity"));
            writeCatalog(conflict);
        }
        if (tick == 290) {
            var failed = report(world); reports.add(failed);
            check(failed.status().equals("FAILED_NO_QUOTES") && UuQuoteBook.generation(world.getServer()) == -1,
                    "Finite/Infinity target conflict invalidates the whole generation");
            check(UuQuoteBook.quote(world.getServer(), copiedItems[0]) == null
                    && UuQuoteBook.quote(world.getServer(), copiedItems[1]) == null, "Both old prices are retired");
            var tile = machine(world, 0); tile.generateOnce();
            stoppedEu = tile.getEnergyStorageInternal().getAmount(); stoppedFluid = tile.getUuMatterAmount();
            stoppedCredit = tile.getUuCreditBuckets(); stoppedProcessed = tile.getProcessedUuBuckets();
            stoppedCompleted = tile.getTotalProcessed();
        }
        if (tick == 340) {
            var tile = machine(world, 0);
            check(tile.getEnergyStorageInternal().getAmount() == stoppedEu && tile.getUuMatterAmount() == stoppedFluid
                    && tile.getTotalProcessed() == stoppedCompleted, "Armed machine neither debits nor awards for 50 invalid-generation ticks");
            near(tile.getUuCreditBuckets(), stoppedCredit, "Fractional credit unchanged during invalid generation");
            near(tile.getProcessedUuBuckets(), stoppedProcessed, "Paid progress unchanged during invalid generation");
            writeCatalog(JsonParser.parseString(originalCatalog).getAsJsonObject());
            groups.add("conflicting-target-reload-invalidates-authority-and-pauses-without-debit");
        }
        if (tick == 440) {
            var recovered = report(world); reports.add(recovered);
            check(recovered.generation() > changedGeneration && recovered.unmapped() == originalUnmapped,
                    "Corrected catalog publishes fresh generation with original mapping scope");
            verifyCatalog(world, JsonParser.parseString(originalCatalog).getAsJsonObject(), recovered.generation(), false);
            assertCopy(world, 0, 2); assertCopy(world, 1, 1);
            near(memory.getUuMatterCost(machine(world, 0).getItemHandler().getStackInSlot(mio_icif_replicator_elc.MEMORY_SLOT)),
                    originalPrices[0], "Recovered memory returns to original authoritative amount");
            groups.add("normal-reload-recovery-resumes-one-pending-copy-with-conserved-payments");
        }
        if (tick == 460) {
            assertCopy(world, 0, 2); assertCopy(world, 1, 1);
            check(groups.size() == 5, "All five declared groups completed");
            var result = new LinkedHashMap<String, Object>();
            result.put("passed", true); result.put("assertions", assertions); result.put("groups", groups);
            result.put("mapped_entries", fixture.get("mapped_entries").getAsInt());
            result.put("mapped_finite", finiteEntries); result.put("mapped_denied", deniedEntries);
            result.put("mapped_sha256", hash(originalCatalog)); result.put("copies", copies); result.put("reports", reports);
            result.put("scope", "Registered mapped identities and complete default components, finite/Infinity authority, two actual material replicas, three normal resource reloads, explicit smelting policy extension, unknown target and conflicting generation behavior. No scanner duration/client/multiplayer/full-mod parity claim.");
            Files.writeString(Path.of("uu-catalog-world-result.json"), new Gson().toJson(result));
            return result;
        }
        return null;
    }
}
