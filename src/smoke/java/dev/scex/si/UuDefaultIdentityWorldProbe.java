// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.scex.si.processing.IndependentUuValueIndex;
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
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Observation-only candidate: no inventory/BE placement, filling, charging or quote installation. */
public final class UuDefaultIdentityWorldProbe {
    private static final Path INPUT = Path.of("uu-default-identities-r101.json");
    private static final Path OUTPUT = Path.of("uu-default-identities-r101-result.json");
    private static final String BASE = "data/mio_icif/uu/mapped_ic2.json";
    private static final int MAX_CASES = 132;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private boolean finished;

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException("R101 default identity: " + message);
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] bounded(java.io.InputStream input) throws Exception {
        var bytes = input.readNBytes(MAX_BYTES + 1);
        require(bytes.length <= MAX_BYTES, "input byte budget");
        return bytes;
    }

    private static String legacyIdentity(JsonObject row) throws Exception {
        String source = row.get("legacy_stack").getAsString();
        require(source.length() <= 8192, "legacy NBT budget");
        CompoundTag tag = TagParser.parseTag(source);
        require(tag.getAllKeys().equals(java.util.Set.of("id", "Count", "Damage")), "strict stateless legacy fields");
        require(tag.contains("id", Tag.TAG_STRING) && tag.contains("Count", Tag.TAG_BYTE)
                && tag.contains("Damage", Tag.TAG_SHORT), "legacy exact field types");
        require(tag.getByte("Count") == 1 && tag.getShort("Damage") >= 0, "legacy count/damage");
        var id = ResourceLocation.parse(tag.getString("id"));
        require(id.getNamespace().equals("ic2"), "legacy namespace");
        return id + "#" + tag.getShort("Damage");
    }

    private static boolean sameComponentValues(DataComponentMap first, DataComponentMap second) {
        if (!first.keySet().equals(second.keySet())) return false;
        for (var type : first.keySet()) {
            if (!java.util.Objects.equals(first.get(type), second.get(type))) return false;
        }
        return true;
    }

    private static Map<String, Object> snapshot(ServerLevel world, ItemStack stack) {
        var result = new LinkedHashMap<String, Object>();
        var ops = world.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var encoded = ItemStack.STRICT_SINGLE_ITEM_CODEC.encodeStart(ops, stack).getOrThrow();
        var decoded = ItemStack.STRICT_SINGLE_ITEM_CODEC.parse(ops, encoded).getOrThrow();
        require(ItemStack.isSameItemSameComponents(stack, decoded) && decoded.getCount() == 1,
                "strict full stack roundtrip");
        var components = DataComponentMap.CODEC.encodeStart(ops, stack.getComponents()).getOrThrow();
        var decodedComponents = DataComponentMap.CODEC.parse(ops, components).getOrThrow();
        require(sameComponentValues(decodedComponents, stack.getComponents()), "full component codec roundtrip");
        var patch = DataComponentPatch.CODEC.encodeStart(ops, stack.getComponentsPatch()).getOrThrow();
        var prototype = DataComponentMap.CODEC.encodeStart(ops, stack.getPrototype()).getOrThrow();
        var componentIds = stack.getComponents().keySet().stream()
                .map(type -> String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type))).sorted().toList();
        require(componentIds.stream().noneMatch("null"::equals), "registered component IDs");
        result.put("stack_snbt", encoded.toString());
        result.put("all_components_snbt", components.toString());
        result.put("prototype_components_snbt", prototype.toString());
        result.put("patch_snbt", patch.toString());
        result.put("patch_empty", stack.isComponentsPatchEmpty());
        result.put("component_ids", componentIds);
        result.put("components_hash", stack.getComponents().hashCode());
        result.put("damage", stack.getDamageValue());
        result.put("max_damage", stack.getMaxDamage());
        result.put("max_stack", stack.getMaxStackSize());
        result.put("strict_stack_roundtrip", true);
        result.put("full_component_roundtrip", true);
        return result;
    }

    private static Map<String, Object> capabilities(ServerLevel world, ItemStack stack) {
        var result = new LinkedHashMap<String, Object>();
        var energy = stack.getCapability(Capabilities.EnergyStorage.ITEM);
        result.put("standard_fe_present", energy != null);
        if (energy != null) {
            result.put("fe_stored", energy.getEnergyStored());
            result.put("fe_capacity", energy.getMaxEnergyStored());
            result.put("fe_can_receive", energy.canReceive());
            result.put("fe_can_extract", energy.canExtract());
        }
        var fluids = stack.getCapability(Capabilities.FluidHandler.ITEM);
        result.put("standard_fluid_present", fluids != null);
        if (fluids != null) {
            int count = fluids.getTanks();
            require(count >= 0 && count <= 64, "tank count bound");
            var tanks = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < count; i++) {
                var fluid = fluids.getFluidInTank(i);
                var tank = new LinkedHashMap<String, Object>();
                tank.put("index", i);
                tank.put("capacity", fluids.getTankCapacity(i));
                tank.put("amount", fluid.getAmount());
                tank.put("empty", fluid.isEmpty());
                tank.put("fluid_snbt", fluid.saveOptional(world.registryAccess()).toString());
                tanks.add(tank);
            }
            result.put("tanks", tanks);
            var container = fluids.getContainer();
            result.put("reported_container_same_components", !container.isEmpty()
                    && ItemStack.isSameItemSameComponents(stack, container));
        }
        var inventory = stack.getCapability(Capabilities.ItemHandler.ITEM);
        result.put("standard_item_inventory_present", inventory != null);
        if (inventory != null) {
            int count = inventory.getSlots();
            require(count >= 0 && count <= 256, "inventory slot bound");
            var slots = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < count; i++) {
                var stored = inventory.getStackInSlot(i);
                slots.add(Map.of("index", i, "empty", stored.isEmpty(),
                        "stack_snbt", stored.saveOptional(world.registryAccess()).toString()));
            }
            result.put("slots", slots);
        }
        result.put("si_eu_semantics", "NOT_INFERRED_FROM_FE_ABSENCE");
        return result;
    }

    private static Map<String, Object> inspectOne(ServerLevel world, JsonObject row) {
        var result = new LinkedHashMap<String, Object>();
        String target = row.get("target_item").getAsString();
        String group = row.get("group").getAsString();
        result.put("case_id", row.get("case_id").getAsString());
        result.put("target_item", target);
        result.put("legacy_stack", row.get("legacy_stack").getAsString());
        result.put("reference_kind", row.get("reference_kind").getAsString());
        result.put("raw_value", row.get("raw_value").getAsString());
        result.put("source_row", row.get("source_row").getAsInt());
        result.put("group", group);
        result.put("observed_inputs", row.get("observed_inputs"));
        result.put("quote_activation", "NONE");
        result.put("semantic_admission", "PENDING_INDEPENDENT_DEFAULT_SEMANTICS");
        try {
            var id = ResourceLocation.parse(target);
            require(id.getNamespace().equals("mio_icif") && id.toString().equals(target), "explicit target ID");
            var holder = world.registryAccess().lookupOrThrow(Registries.ITEM)
                    .get(ResourceKey.create(Registries.ITEM, id)).orElseThrow();
            var stack = new ItemStack(holder.value());
            require(!stack.isEmpty() && stack.getCount() == 1 && holder.value() != Items.AIR, "actual default stack");
            result.put("runtime_item_class", stack.getItem().getClass().getName());
            result.put("declared_item_constructor", row.get("declared_item_constructor").getAsString());
            var before = snapshot(world, stack);
            result.put("before_capability_queries", before);
            var keyBefore = IndependentUuValueIndex.keyOf(stack);
            var queryStack = stack.copy();
            var caps = capabilities(world, queryStack);
            result.put("read_only_capabilities", caps);
            var after = snapshot(world, queryStack);
            result.put("after_capability_queries", after);
            boolean queryUnchanged = before.get("stack_snbt").equals(after.get("stack_snbt"))
                    && before.get("all_components_snbt").equals(after.get("all_components_snbt"));
            result.put("capability_query_preserved_identity", queryUnchanged);
            var fresh = new ItemStack(holder.value());
            var freshSnapshot = snapshot(world, fresh);
            boolean deterministic = before.get("stack_snbt").equals(freshSnapshot.get("stack_snbt"))
                    && before.get("all_components_snbt").equals(freshSnapshot.get("all_components_snbt"));
            result.put("fresh_default_deterministic", deterministic);

            // Deliberately change a detached copy; never erase or normalize a user/default payload.
            var variant = fresh.copy();
            var marker = variant.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            marker.putString("scex_r101_probe_identity", "component-variant");
            variant.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
            boolean distinct = !keyBefore.equals(IndependentUuValueIndex.keyOf(variant));
            require(distinct, "changed CUSTOM_DATA key distinct");
            result.put("custom_data_variant_key_distinct", distinct);
            result.put("currently_quoted_default", UuQuoteBook.quote(world.getServer(), fresh) != null);
            result.put("currently_quoted_variant", UuQuoteBook.quote(world.getServer(), variant) != null);

            boolean plainClass = stack.getItem().getClass() == Item.class;
            boolean plainComponents = sameComponentValues(fresh.getComponents(), new ItemStack(Items.STICK).getComponents());
            boolean noCapabilities = Boolean.FALSE.equals(caps.get("standard_fe_present"))
                    && Boolean.FALSE.equals(caps.get("standard_fluid_present"))
                    && Boolean.FALSE.equals(caps.get("standard_item_inventory_present"));
            boolean eligible = group.equals("plain_static_component")
                    && (row.get("case_id").getAsString().equals("r99-25") || row.get("case_id").getAsString().equals("r99-68"))
                    && row.get("raw_value").getAsString().equals("Infinity")
                    && plainClass && plainComponents && fresh.isComponentsPatchEmpty()
                    && noCapabilities && queryUnchanged && deterministic && distinct
                    && Boolean.FALSE.equals(result.get("currently_quoted_default"))
                    && Boolean.FALSE.equals(result.get("currently_quoted_variant"));
            result.put("plain_class", plainClass);
            result.put("components_equal_registered_vanilla_stick_defaults", plainComponents);
            result.put("eligible_for_two_static_denials_candidate", eligible);
            if (eligible) result.put("semantic_admission", "STATIC_ROLE_AND_DEFAULT_CHECKS_MATCH_PENDING_REVIEW");
            result.put("status", queryUnchanged && deterministic ? "OBSERVED_STRICT_ROUNDTRIP" : "OBSERVED_MUTATION_OR_NONDETERMINISM_HELD");
        } catch (Exception failure) {
            result.put("status", "OBSERVATION_FAILED_HELD");
            result.put("failure_type", failure.getClass().getName());
            result.put("failure_message", String.valueOf(failure.getMessage()));
            result.put("eligible_for_two_static_denials_candidate", false);
        }
        return result;
    }

    /** Invoke from the existing server-tick smoke dispatcher; returns its one receipt at tick >= 20. */
    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        if (finished || tick < 20) return null;
        var receipt = new LinkedHashMap<String, Object>();
        byte[] inputBytes;
        try (var input = Files.newInputStream(INPUT)) { inputBytes = bounded(input); }
        var fixture = JsonParser.parseString(new String(inputBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        require(fixture.get("schema").getAsInt() == 1, "fixture schema");
        require(fixture.get("case_count").getAsInt() == MAX_CASES, "fixture case count");
        byte[] baseBytes;
        try (var input = UuDefaultIdentityWorldProbe.class.getClassLoader().getResourceAsStream(BASE)) {
            require(input != null, "base catalog resource exists");
            baseBytes = bounded(input);
        }
        require(sha(baseBytes).equals(fixture.get("base_sha256").getAsString()), "unchanged 219 base resource SHA");
        var base = JsonParser.parseString(new String(baseBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        require(base.getAsJsonArray("entries").size() == 219, "base entries");
        var targets = new HashSet<String>();
        var legacy = new HashSet<String>();
        var sources = new HashSet<Integer>();
        for (var raw : base.getAsJsonArray("entries")) {
            var row = raw.getAsJsonObject();
            require(targets.add(row.get("target_item").getAsString()), "duplicate base target");
            require(legacy.add(legacyIdentity(row)), "duplicate base legacy");
            require(sources.add(row.get("source_row").getAsInt()), "duplicate base source row");
        }
        var cases = fixture.getAsJsonArray("cases");
        require(cases.size() == MAX_CASES, "actual case count");
        for (var raw : cases) {
            var row = raw.getAsJsonObject();
            require(targets.add(row.get("target_item").getAsString()), "new/base target collision");
            require(legacy.add(legacyIdentity(row)), "new/base legacy collision");
            require(sources.add(row.get("source_row").getAsInt()), "new/base source-row collision");
        }
        var rows = new ArrayList<Map<String, Object>>();
        for (var raw : cases) rows.add(inspectOne(world, raw.getAsJsonObject()));
        long failed = rows.stream().filter(r -> r.get("status").equals("OBSERVATION_FAILED_HELD")).count();
        long mutated = rows.stream().filter(r -> r.get("status").equals("OBSERVED_MUTATION_OR_NONDETERMINISM_HELD")).count();
        long eligible = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("eligible_for_two_static_denials_candidate"))).count();
        receipt.put("status", failed == 0 && mutated == 0 ? "PASS_SCOPED_DEFAULT_IDENTITY_OBSERVATION" : "PARTIAL_DEFAULT_IDENTITY_OBSERVATION_HELD");
        receipt.put("fixture_sha256", sha(inputBytes));
        receipt.put("base_resource_sha256", sha(baseBytes));
        receipt.put("cases", rows.size());
        receipt.put("failed_observations", failed);
        receipt.put("mutated_or_nondeterministic_defaults", mutated);
        receipt.put("static_denial_candidates_eligible_for_review", eligible);
        receipt.put("new_finite_prices_approved", 0);
        receipt.put("quotes_activated", 0);
        receipt.put("world_blocks_or_inventories_modified", 0);
        receipt.put("rows", rows);
        receipt.put("semantic_limit", "Codec/default observations do not prove custom hidden charge/fluid/genetic/mode semantics; 130 custom/vanilla-tool proposals remain held.");
        receipt.put("full_mod_gate", "UNCHANGED_INCOMPLETE");
        Files.writeString(OUTPUT, new GsonBuilder().setPrettyPrinting().create().toJson(receipt) + "\n", StandardCharsets.UTF_8);
        finished = true;
        return receipt;
    }
}
