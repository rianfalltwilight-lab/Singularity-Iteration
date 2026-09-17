// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.processing;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Explicit reviewed item identities; never guesses a mod mapping from a display name. */
public final class UuMappedCatalog {
    public static final String RESOURCE_PATH = "data/mio_icif/uu/mapped_ic2.json";
    private static final int MAX_INPUT = 4 * 1024 * 1024;
    private static final int MAX_ROWS = 16384;
    private static final Set<String> ROOT_FIELDS = Set.of("schema", "data_version", "entries");
    private static final Set<String> REQUIRED_ROW_FIELDS = Set.of("legacy_stack", "target_item", "raw_value", "source_row");
    private static final Set<String> ROW_FIELDS = Set.of("legacy_stack", "target_item", "raw_value", "source_row", "target_stack");
    private record Row(String legacy, String target, String raw, int source, String stack) { }
    private record Legacy(ResourceLocation id, short damage) { }
    public record ReviewedCatalog(UuReferenceCatalog.Catalog catalog, Set<String> componentScopedItems) {
        public ReviewedCatalog { componentScopedItems = Set.copyOf(componentScopedItems); }
        /** Explicit component prototypes have only observed authority, not inferred variants. */
        public boolean allowsDerivedOutput(IndependentUuValueIndex.Key output) {
            return !componentScopedItems.contains(output.itemId());
        }
    }
    private UuMappedCatalog() { }

    /** The caller owns the Reader. A failed read publishes no partial catalog. */
    public static UuReferenceCatalog.Catalog read(Reader reader, HolderLookup.Provider registries) throws IOException {
        return readReviewed(reader, registries).catalog();
    }

    public static ReviewedCatalog readReviewed(Reader reader, HolderLookup.Provider registries) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(registries, "registries");
        StringBuilder input = new StringBuilder();
        char[] buffer = new char[4096];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            if (input.length() + count > MAX_INPUT) throw new IOException("Mapped UU catalog exceeds 4 MiB");
            input.append(buffer, 0, count);
        }
        String text = input.toString();
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT)
            throw new IOException("Mapped UU catalog exceeds 4 MiB UTF-8");
        List<Row> rows = parse(text);
        var items = registries.lookupOrThrow(Registries.ITEM);
        Map<IndependentUuValueIndex.Key, Double> prices = new HashMap<>();
        Set<IndependentUuValueIndex.Key> denied = new HashSet<>();
        Set<Legacy> legacyIdentities = new HashSet<>();
        Set<ResourceLocation> targets = new HashSet<>();
        Set<Integer> sourceRows = new HashSet<>();
        Set<IndependentUuValueIndex.Key> identities = new HashSet<>();
        Set<String> componentScopedItems = new HashSet<>();
        int unmapped = 0;
        for (Row row : rows) {
            Legacy legacy = legacy(row.legacy());
            ResourceLocation target = explicitId(row.target(), "mio_icif");
            if (row.stack() != null) { savedStack(row.stack(), target); componentScopedItems.add(target.toString()); }
            boolean infinite = row.raw().equals("Infinity");
            double buckets = infinite ? 0 : price(row.raw());
            if (!legacyIdentities.add(legacy) || !targets.add(target) || !sourceRows.add(row.source()))
                throw new IllegalArgumentException("Duplicate mapped UU identity, target, or source row");
            var holder = items.get(ResourceKey.create(Registries.ITEM, target));
            if (holder.isEmpty()) { unmapped++; continue; }
            ItemStack item = row.stack() == null ? new ItemStack(holder.orElseThrow().value())
                    : explicitStack(row.stack(), row.target(), registries);
            if (item.isEmpty() || item.getCount() != 1 || item.getMaxStackSize() < 1) {
                unmapped++;
                continue;
            }
            if (ItemStack.STRICT_SINGLE_ITEM_CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), item).error().isPresent()) {
                unmapped++;
                continue;
            }
            var key = IndependentUuValueIndex.keyOf(item);
            if (!identities.add(key)) throw new IllegalArgumentException("Multiple targets resolve to one mapped UU item");
            if (infinite) denied.add(key);
            else prices.put(key, buckets);
        }
        return new ReviewedCatalog(new UuReferenceCatalog.Catalog(Map.copyOf(prices), Set.copyOf(denied), unmapped, rows.size()), componentScopedItems);
    }

    @SuppressWarnings("deprecation") // 2.10 and 2.11 Gson both support this strict-reader switch.
    private static List<Row> parse(String text) throws IOException {
        try (JsonReader json = new JsonReader(new StringReader(text))) {
            json.setLenient(false);
            Set<String> fields = new HashSet<>();
            int schema = -1;
            int version = -1;
            List<Row> rows = new ArrayList<>();
            json.beginObject();
            while (json.hasNext()) {
                String name = json.nextName();
                if (!ROOT_FIELDS.contains(name) || !fields.add(name))
                    throw new IllegalArgumentException("Unknown or duplicate mapped UU root field");
                switch (name) {
                    case "schema" -> schema = integer(json);
                    case "data_version" -> version = integer(json);
                    case "entries" -> {
                        json.beginArray();
                        while (json.hasNext()) {
                            if (rows.size() >= MAX_ROWS) throw new IllegalArgumentException("Mapped UU catalog exceeds row limit");
                            rows.add(row(json));
                        }
                        json.endArray();
                    }
                    default -> throw new IllegalArgumentException("Unknown mapped UU root field");
                }
            }
            json.endObject();
            if (!fields.equals(ROOT_FIELDS) || (schema != 1 && schema != 2) || version != 1343 || json.peek() != JsonToken.END_DOCUMENT
                    || schema == 1 && rows.stream().anyMatch(row -> row.stack() != null))
                throw new IllegalArgumentException("Unknown or incomplete mapped UU catalog schema");
            return rows;
        } catch (IllegalStateException malformed) {
            throw new IllegalArgumentException("Invalid mapped UU catalog structure", malformed);
        }
    }

    private static Row row(JsonReader json) throws IOException {
        Set<String> fields = new HashSet<>();
        String legacy = null;
        String target = null;
        String raw = null;
        String stack = null;
        int source = -1;
        json.beginObject();
        while (json.hasNext()) {
            String name = json.nextName();
            if (!ROW_FIELDS.contains(name) || !fields.add(name))
                throw new IllegalArgumentException("Unknown or duplicate mapped UU entry field");
            switch (name) {
                case "legacy_stack" -> legacy = string(json, 8192);
                case "target_item" -> target = string(json, 256);
                case "raw_value" -> raw = string(json, 128);
                case "source_row" -> source = integer(json);
                case "target_stack" -> stack = string(json, 8192);
                default -> throw new IllegalArgumentException("Unknown mapped UU entry field");
            }
        }
        json.endObject();
        if (!fields.containsAll(REQUIRED_ROW_FIELDS)) throw new IllegalArgumentException("Incomplete mapped UU entry");
        return new Row(legacy, target, raw, source, stack);
    }

    /** An explicit saved prototype retains the complete registered component identity. */
    public static ItemStack explicitStack(String saved, String target, HolderLookup.Provider registries) {
        var tag = savedStack(saved, explicitId(target, "mio_icif"));
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        try {
            var stack = ItemStack.STRICT_SINGLE_ITEM_CODEC.parse(ops, tag).getOrThrow();
            var roundtrip = ItemStack.STRICT_SINGLE_ITEM_CODEC.encodeStart(ops, stack).getOrThrow();
            var explicit = tag.copy();
            // SINGLE_ITEM_CODEC fixes count at one and intentionally omits it on encode.
            explicit.remove("count");
            // An explicitly observed registered default still restricts its whole
            // item family; the codec omits an empty component patch on encode.
            if (explicit.getCompound("components").isEmpty()) explicit.remove("components");
            if (stack.isEmpty() || stack.getCount() != 1 || !explicit.equals(roundtrip))
                throw new IllegalArgumentException("Explicit mapped stack loses or normalizes saved fields");
            return stack;
        } catch (IllegalStateException malformed) {
            throw new IllegalArgumentException("Invalid explicit mapped component identity", malformed);
        }
    }

    private static net.minecraft.nbt.CompoundTag savedStack(String saved, ResourceLocation target) {
        if (saved == null || saved.length() > 8192)
            throw new IllegalArgumentException("Explicit mapped stack exceeds limit");
        try {
            var tag = TagParser.parseTag(saved);
            if (!tag.getAllKeys().equals(Set.of("id", "count", "components"))
                    || !tag.contains("id", Tag.TAG_STRING) || !tag.getString("id").equals(target.toString())
                    || !tag.contains("count", Tag.TAG_INT) || tag.getInt("count") != 1
                    || !tag.contains("components", Tag.TAG_COMPOUND))
                throw new IllegalArgumentException("Explicit one-item prototype with exact ID and component patch required");
            return tag;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException malformed) {
            throw new IllegalArgumentException("Invalid explicit mapped stack", malformed);
        }
    }

    private static String string(JsonReader json, int limit) throws IOException {
        if (json.peek() != JsonToken.STRING) throw new IllegalArgumentException("Mapped UU field must be a string");
        String value = json.nextString();
        if (value.length() > limit) throw new IllegalArgumentException("Mapped UU string exceeds limit");
        return value;
    }

    private static int integer(JsonReader json) throws IOException {
        if (json.peek() != JsonToken.NUMBER) throw new IllegalArgumentException("Mapped UU field must be an integer");
        String value = json.nextString();
        if (value.length() > 10 || !value.matches("0|[1-9][0-9]*"))
            throw new IllegalArgumentException("Invalid mapped UU nonnegative integer");
        return Integer.parseInt(value);
    }

    private static ResourceLocation explicitId(String value, String namespace) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !id.getNamespace().equals(namespace) || !id.toString().equals(value))
            throw new IllegalArgumentException("Unexpected mapped UU item namespace or noncanonical ID");
        return id;
    }

    /** Deliberately accepts only flat, stateless legacy stacks; no NBT field can be ignored. */
    private static Legacy legacy(String saved) {
        if (saved.length() > 8192) throw new IllegalArgumentException("Mapped UU legacy NBT exceeds limit");
        String text = saved.trim();
        if (!text.startsWith("{") || !text.endsWith("}")) throw new IllegalArgumentException("Invalid mapped UU legacy NBT");
        String[] parts = text.substring(1, text.length() - 1).split(",", -1);
        if (parts.length < 3 || parts.length > 4) throw new IllegalArgumentException("Unsupported mapped UU legacy fields");
        Set<String> fields = new HashSet<>();
        ResourceLocation id = null;
        short damage = -1;
        for (String part : parts) {
            int colon = part.indexOf(':');
            if (colon <= 0) throw new IllegalArgumentException("Invalid mapped UU legacy field");
            String name = unquote(part.substring(0, colon).trim());
            String value = part.substring(colon + 1).trim();
            if (!fields.add(name)) throw new IllegalArgumentException("Duplicate mapped UU legacy field");
            switch (name) {
                case "id" -> id = explicitId(unquote(value), "ic2");
                case "Count" -> {
                    if (!value.equals("1b") && !value.equals("1B"))
                        throw new IllegalArgumentException("Mapped UU reference must contain exactly one byte-count item");
                }
                case "Damage" -> {
                    if (!value.matches("[0-9]{1,5}[sS]")) throw new IllegalArgumentException("Mapped UU metadata must be a nonnegative short");
                    int number = Integer.parseInt(value.substring(0, value.length() - 1));
                    if (number > Short.MAX_VALUE) throw new IllegalArgumentException("Mapped UU metadata exceeds short range");
                    damage = (short) number;
                }
                case "tag" -> {
                    if (!value.matches("\\{\\s*\\}")) throw new IllegalArgumentException("Mapped UU legacy item has unresolved NBT state");
                }
                default -> throw new IllegalArgumentException("Unknown mapped UU legacy field");
            }
        }
        if (!fields.containsAll(Set.of("id", "Count", "Damage")) || id == null || damage < 0)
            throw new IllegalArgumentException("Incomplete mapped UU legacy identity");
        try {
            var parsed = TagParser.parseTag(saved);
            if (!parsed.contains("id", Tag.TAG_STRING) || !parsed.contains("Count", Tag.TAG_BYTE)
                    || !parsed.contains("Damage", Tag.TAG_SHORT) || !parsed.getString("id").equals(id.toString())
                    || parsed.getByte("Count") != 1 || parsed.getShort("Damage") != damage)
                throw new IllegalArgumentException("Mapped UU legacy grammar does not describe the parsed item");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException malformed) {
            throw new IllegalArgumentException("Invalid mapped UU saved-item SNBT", malformed);
        }
        return new Legacy(id, damage);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'')))
            return value.substring(1, value.length() - 1);
        return value;
    }

    private static double price(String raw) {
        if (!raw.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))
            throw new IllegalArgumentException("Invalid mapped UU reference price");
        double buckets = Double.parseDouble(raw) / 100000.0;
        if (!StoredPattern.validCosts(buckets, 0)) throw new IllegalArgumentException("Mapped UU price is not finite and positive");
        return buckets;
    }
}
