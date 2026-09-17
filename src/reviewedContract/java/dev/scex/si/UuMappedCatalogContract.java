// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.scex.si.processing.IndependentUuValueIndex;
import dev.scex.si.processing.UuMappedCatalog;
import dev.scex.si.processing.UuReferenceCatalog;
import dev.scex.si.processing.UuScanEligibility;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Real registered item/default-component contracts. No Minecraft world is created. */
public final class UuMappedCatalogContract {
    private static final int EXPECTED_ENTRIES=347, EXPECTED_FINITE=147, EXPECTED_DENIED=200;
    private static final String PLAIN = "mio_icif:r100_contract_plain";
    private static final String DEFAULTS = "mio_icif:r100_contract_defaults";
    private static final String OTHER = "mio_icif:r100_contract_other";
    private static int assertions;
    private static RegistryAccess registries;
    private UuMappedCatalogContract() { }
    private static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    private static JsonObject row(String legacy, String target, String raw, int source) {
        var value = new JsonObject(); value.addProperty("legacy_stack", legacy); value.addProperty("target_item", target);
        value.addProperty("raw_value", raw); value.addProperty("source_row", source); return value;
    }
    private static JsonObject finite() { return row("{id:\"ic2:dust\",Count:1b,Damage:15s}", PLAIN, "15.0", 865); }
    private static JsonObject root(JsonObject... rows) {
        var value = new JsonObject(); value.addProperty("schema", 1); value.addProperty("data_version", 1343);
        var array = new JsonArray(); for (var row : rows) array.add(row); value.add("entries", array); return value;
    }
    private static UuReferenceCatalog.Catalog read(JsonObject value) throws IOException {
        return UuMappedCatalog.read(new StringReader(value.toString()), registries);
    }
    private static void reject(String json, String label) throws IOException {
        try { UuMappedCatalog.read(new StringReader(json), registries); throw new AssertionError("Accepted " + label); }
        catch (IllegalArgumentException | IOException expected) { check(true, label); }
    }
    private static void reject(JsonObject value, String label) throws IOException { reject(value.toString(), label); }

    private static void successful(Item plain, Item defaults, Item other) throws IOException {
        var result = read(root(finite(),
                row("{Damage:0s,tag:{ },Count:1b,id:'ic2:resource'}", DEFAULTS, "3.25E2", 2000),
                row("{id:'ic2:crafting',Count:1b,Damage:12s}", OTHER, "Infinity", 2001),
                row("{id:'ic2:te',Count:1b,Damage:32000s}", "mio_icif:r100_missing", "1.0", 2002)));
        var plainKey = IndependentUuValueIndex.keyOf(new ItemStack(plain));
        var defaultStack = new ItemStack(defaults);
        var defaultKey = IndependentUuValueIndex.keyOf(defaultStack);
        check(result.entries() == 4 && result.unmapped() == 1, "Unknown explicit target is accounted and remains unquoted");
        check(result.prices().size() == 2 && result.denied().size() == 1, "Finite and Infinity rows stay separate");
        check(result.prices().get(plainKey) == .00015, "Observed raw units convert to buckets once");
        check(result.prices().get(defaultKey) == .00325, "Scientific notation converts without metadata guessing");
        check(result.denied().contains(IndependentUuValueIndex.keyOf(new ItemStack(other))), "Infinity denies the registered exact item identity");
        check(defaultStack.get(DataComponents.CUSTOM_DATA).copyTag().getString("default_marker").equals("retained"), "Registered default components survive mapping");
        var changed = defaultStack.copy(); changed.set(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        check(!result.prices().containsKey(IndependentUuValueIndex.keyOf(changed)), "Quote identity does not erase default components");
        check(result.prices().containsKey(IndependentUuValueIndex.keyOf(new ItemStack(defaults))), "External stack mutation cannot alter retained keys");
        try { result.prices().put(plainKey, 0.0); throw new AssertionError("Mutable price map"); }
        catch (UnsupportedOperationException expected) { check(true, "Price map is immutable"); }
        try { result.denied().clear(); throw new AssertionError("Mutable denied set"); }
        catch (UnsupportedOperationException expected) { check(true, "Denied set is immutable"); }
        check(read(root()).entries() == 0, "Explicit empty catalog is legal");
    }

    private static void invalidIdentities() throws IOException {
        for (String legacy : List.of(
                "{id:'minecraft:stone',Count:1b,Damage:0s}", "{id:'ic2:dust',Count:2b,Damage:0s}",
                "{id:'ic2:dust',Count:0b,Damage:0s}", "{id:'ic2:dust',Count:1,Damage:0s}",
                "{id:'ic2:dust',Count:1b,Damage:0}", "{id:'ic2:dust',Count:1b,Damage:-1s}",
                "{id:'ic2:dust',Count:1b,Damage:32768s}", "{id:'ic2:dust',Count:1b,Damage:0s,tag:{charge:0d}}",
                "{id:'ic2:dust',Count:1b,Damage:0s,tag:[]}", "{id:'ic2:dust',Count:1b,Damage:0s,unknown:0}",
                "{id:'ic2:dust',Count:1b,Damage:0s,Damage:1s}", "{id:'ic2:dust',Count:1b,Damage:0s,tag:{charge:1d},tag:{}}",
                "{id:'ic2:dust',Count:1b}", "{id:'ic2:dust',Count:1b,Damage:0s},{}", "{id:'ic2:dust',Count:1b,Damage:0s,}",
                "{id:ic2:dust,Count:1b,Damage:0s}")) {
            reject(root(row(legacy, PLAIN, "15", 1)), "Malformed or stateful legacy identity " + legacy);
        }
        for (String target : List.of("minecraft:stone", "r100_contract_plain", "mio_icif:BAD", "ic2:dust", " mio_icif:r100_contract_plain"))
            reject(root(row("{id:'ic2:dust',Count:1b,Damage:0s}", target, "15", 1)), "Noncanonical or foreign target namespace");
        var original = finite(); var duplicate = finite(); duplicate.addProperty("raw_value", "15.0");
        reject(root(original, duplicate), "Exact duplicate is rejected even at the same price");
        duplicate = row("{id:'ic2:resource',Count:1b,Damage:1s}", PLAIN, "Infinity", 1);
        reject(root(original, duplicate), "Finite and denied target conflict");
        reject(root(duplicate, original), "Denied and finite target conflict is order independent");
        duplicate = row("{tag:{},Damage:15s,Count:1b,id:'ic2:dust'}", OTHER, "3", 1);
        reject(root(original, duplicate), "Whitespace, key ordering and empty tag cannot bypass duplicate legacy identity");
        duplicate = row("{id:'ic2:dust',Count:1b,Damage:1s}", OTHER, "3", 865);
        reject(root(original, duplicate), "Duplicate provenance row is rejected");
        duplicate = row("{id:'ic2:dust',Count:1b,Damage:1s}", "mio_icif:r100_missing", "3", 1);
        var second = row("{id:'ic2:dust',Count:1b,Damage:2s}", "mio_icif:r100_missing", "Infinity", 2);
        reject(root(duplicate, second), "Unknown target cannot hide a duplicate target conflict");
    }

    private static void invalidSchemaAndPrices() throws IOException {
        for (String raw : List.of("0", "-1", "NaN", "-Infinity", "+Infinity", "1e309", "1e-400", "9.3e20", " 15", "0x1p2", "1.0f", ""))
            reject(root(row("{id:'ic2:dust',Count:1b,Damage:0s}", "mio_icif:r100_missing", raw, 1)), "Invalid cost is rejected before unknown-target handling: " + raw);
        for (String field : List.of("schema", "data_version", "entries")) {
            var bad = root(finite()); bad.remove(field); reject(bad, "Missing root field " + field);
        }
        for (String field : List.of("legacy_stack", "target_item", "raw_value", "source_row")) {
            var bad = finite(); bad.remove(field); reject(root(bad), "Missing entry field " + field);
        }
        var bad = root(finite()); bad.addProperty("schema", 3); reject(bad, "Future schema");
        bad = root(finite()); bad.addProperty("data_version", 1342); reject(bad, "Unexpected legacy data version");
        bad = root(finite()); bad.addProperty("schema", "1"); reject(bad, "String schema is not an integer");
        bad = root(finite()); bad.addProperty("schema", 1.0); reject(bad, "Fractional syntax is not an integer schema");
        bad = root(finite()); bad.addProperty("unknown", true); reject(bad, "Unknown root field");
        var entry = finite(); entry.addProperty("future_state", "held"); reject(root(entry), "Unknown entry field");
        entry = finite(); entry.addProperty("raw_value", 15); reject(root(entry), "Numeric JSON cost must not replace exact observation string");
        entry = finite(); entry.addProperty("source_row", -1); reject(root(entry), "Negative source row");
        entry = finite(); entry.addProperty("source_row", 2147483648L); reject(root(entry), "Overflow source row");
        entry = finite(); entry.addProperty("source_row", 1.5); reject(root(entry), "Fractional source row");
        reject(root(finite()).toString().replace("\"schema\":1", "\"schema\":1,\"schema\":2"), "Duplicate JSON root field");
        reject(root(finite()).toString().replace("\"raw_value\":\"15.0\"", "\"raw_value\":\"15.0\",\"raw_value\":\"Infinity\""), "Duplicate JSON entry field");
        reject(root(finite()).toString() + "{}", "Trailing JSON object");
        reject("{schema:1,data_version:1343,entries:[]}", "Lenient JSON is not accepted");
        reject("null", "Null root");
        reject("{\"schema\":1,\"data_version\":1343,\"entries\":[null]}", "Null entry");
    }

    private static void bounds() throws IOException {
        var entry = finite(); entry.addProperty("legacy_stack", " ".repeat(8193)); reject(root(entry), "Legacy NBT length budget");
        entry = finite(); entry.addProperty("target_item", "mio_icif:" + "a".repeat(257)); reject(root(entry), "Target ID length budget");
        entry = finite(); entry.addProperty("raw_value", "1".repeat(129)); reject(root(entry), "Cost string length budget");
        var many = root(); var rows = many.getAsJsonArray("entries");
        for (int i = 0; i <= 16384; i++) rows.add(finite());
        reject(many, "Row count budget before publishing any result");
        reject(" ".repeat(4 * 1024 * 1024 + 1), "4 MiB character bound");
        reject("\"" + "界".repeat(1_400_000) + "\"", "4 MiB UTF-8 byte bound with fewer characters");
    }

    private static void explicitComponents(Item defaults) throws IOException {
        String saved="{id:'"+DEFAULTS+"',count:1,components:{'minecraft:custom_data':{r108:1}}}";
        var entry=row("{id:'ic2:scanner',Count:1b,Damage:0s}",DEFAULTS,"184996.01285682071",2000056);
        entry.addProperty("target_stack",saved);var document=root(entry);document.addProperty("schema",2);
        var resolved=read(document);var expected=new ItemStack(defaults);
        var reviewed=UuMappedCatalog.readReviewed(new StringReader(document.toString()),registries);
        check(reviewed.componentScopedItems().equals(java.util.Set.of(DEFAULTS)),"Only explicit state families restrict derivation");
        check(!reviewed.allowsDerivedOutput(IndependentUuValueIndex.keyOf(expected)),"A charged/default recipe output cannot bypass observed component scope");
        var plainKey=IndependentUuValueIndex.keyOf(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(PLAIN))));
        check(reviewed.allowsDerivedOutput(plainKey),"Other recipe families remain derivable");
        var emptyPatch=document.deepCopy();emptyPatch.getAsJsonArray("entries").get(0).getAsJsonObject()
                .addProperty("target_stack","{id:'"+DEFAULTS+"',count:1,components:{}}");
        var defaultScope=UuMappedCatalog.readReviewed(new StringReader(emptyPatch.toString()),registries);
        check(defaultScope.catalog().prices().containsKey(IndependentUuValueIndex.keyOf(expected))
                &&!defaultScope.allowsDerivedOutput(IndependentUuValueIndex.keyOf(expected)),
                "Explicit empty patch preserves all registered defaults and restricts inferred variants");
        var data=new CompoundTag();data.putInt("r108",1);expected.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
        check(resolved.prices().get(IndependentUuValueIndex.keyOf(expected))==184996.01285682071/100000.0,
                "Explicit complete components receive measured bucket quote");
        check(!resolved.prices().containsKey(IndependentUuValueIndex.keyOf(new ItemStack(defaults))),
                "Registered default is not silently normalized to explicit prototype");
        var changed=expected.copy();data.putInt("r108",2);changed.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
        check(!resolved.prices().containsKey(IndependentUuValueIndex.keyOf(changed)),"Another component value remains unquoted");
        reject(root(entry),"Schema1 cannot silently acquire state semantics");
        for(String bad:List.of(saved.replace("count:1","count:2"),saved.replace("count:1","count:1b"),
                saved.replace(DEFAULTS,OTHER),saved.replace("count:1","count:1,ignored:0"),
                saved.replace("minecraft:custom_data","mio_icif:unregistered_component"),
                saved.replace("{r108:1}","[]"),saved.replace("{r108:1}","{default_marker:'retained'}"))){
            var value=document.deepCopy();value.getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("target_stack",bad);
            reject(value,"Malformed or lossy explicit prototype "+bad);
        }
        entry.addProperty("raw_value","Infinity");document=root(entry);document.addProperty("schema",2);
        var denied=read(document).denied();check(denied.contains(IndependentUuValueIndex.keyOf(expected)),"Denial is exact explicit identity");
        var eligibility=new JsonObject();eligibility.addProperty("schema",2);eligibility.add("denied_scan_items",new JsonArray());
        var stacks=new JsonArray();var state=new JsonObject();state.addProperty("target_item",DEFAULTS);state.addProperty("target_stack",saved);
        stacks.add(state);eligibility.add("denied_scan_stacks",stacks);
        check(UuScanEligibility.read(new StringReader(eligibility.toString()),registries,denied).equals(denied),
                "Explicit observed scan eligibility preserves component key");
        for(int variant=0;variant<4;variant++){
            var bad=eligibility.deepCopy();
            if(variant==0)bad.addProperty("schema",1);
            if(variant==1)bad.getAsJsonArray("denied_scan_stacks").add(state);
            if(variant==2)bad.getAsJsonArray("denied_scan_stacks").get(0).getAsJsonObject().addProperty("target_stack",saved.replace("r108:1","r108:2"));
            if(variant==3)bad.getAsJsonArray("denied_scan_stacks").get(0).getAsJsonObject().addProperty("ignored",true);
            try{UuScanEligibility.read(new StringReader(bad.toString()),registries,denied);throw new AssertionError("Invalid explicit eligibility accepted");}
            catch(IllegalArgumentException expectedFailure){check(true,"Invalid explicit eligibility "+variant);}
        }
    }

    public static void main(String[] args) throws Exception {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        net.neoforged.neoforge.registries.GameData.unfreezeData();
        var plain = Registry.register(BuiltInRegistries.ITEM, ResourceLocation.parse(PLAIN), new Item(new Item.Properties()));
        var marker = new CompoundTag(); marker.putString("default_marker", "retained");
        var defaults = Registry.register(BuiltInRegistries.ITEM, ResourceLocation.parse(DEFAULTS),
                new Item(new Item.Properties().component(DataComponents.CUSTOM_DATA, CustomData.of(marker))));
        var other = Registry.register(BuiltInRegistries.ITEM, ResourceLocation.parse(OTHER), new Item(new Item.Properties()));
        for (var registry : BuiltInRegistries.REGISTRY) registry.freeze();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        check(Path.of(UuMappedCatalog.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
                .equals(Path.of(args[0]).toRealPath()), "Use actual newly compiled mapped catalog class");
        successful(plain, defaults, other); invalidIdentities(); invalidSchemaAndPrices(); bounds(); explicitComponents(defaults);
        if (args.length > 1) {
            var resource = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
            int finiteRows=0, deniedRows=0;
            for (var element : resource.getAsJsonArray("entries")) {
                if (element.getAsJsonObject().get("raw_value").getAsString().equals("Infinity")) deniedRows++;
                else finiteRows++;
            }
            check(finiteRows==EXPECTED_FINITE && deniedRows==EXPECTED_DENIED,
                    "Frozen R109 resource has exactly 147 finite and 200 Infinity rows");
            try (var reader = Files.newBufferedReader(Path.of(args[1]))) {
                var result = UuMappedCatalog.read(reader, registries);
                check(result.entries() == EXPECTED_ENTRIES && result.unmapped() == result.entries(), "Actual reviewed resource parses; test registry does not pretend to contain SI items");
                check(result.prices().isEmpty() && result.denied().isEmpty(), "Absent actual SI registrations publish no fabricated quotes");
            }
        }
        System.out.println("SCEX_UU_MAPPED_CATALOG assertions=" + assertions + " PASS scope=registered_items_and_strict_catalog_no_world");
    }
}
