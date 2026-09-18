// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;

/** Exact product comparison against the frozen R153 public-behavior observation. */
public final class CropReplacementWorldProbe {
    private static final Path REFERENCE = Path.of("crop-reference-r153.json");
    private final CropBaselineWorldProbe liveProbe = new CropBaselineWorldProbe();
    private int assertions;

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError("R156 crop replacement " + label);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void checkAndNormalizeSaplingDrops(JsonElement rows) {
        Map<String, String> leaves = new HashMap<>();
        for (String wood : List.of("acacia", "birch", "dark_oak", "jungle", "oak", "spruce")) {
            leaves.put("mio_icif:" + wood + "_sapling", "minecraft:" + wood + "_leavesx1");
        }
        for (JsonElement rowElement : rows.getAsJsonArray()) {
            JsonObject row = rowElement.getAsJsonObject();
            if (!row.has("plant_details")) continue;
            JsonArray entries = row.getAsJsonObject("plant_details").getAsJsonArray("entries");
            for (JsonElement entryElement : entries) {
                JsonObject entry = entryElement.getAsJsonObject();
                String id = entry.get("id").getAsString();
                if (id.equals("mio_icif:melon")) {
                    JsonArray drops = entry.getAsJsonArray("drops");
                    check(drops.size() == 2, "melon has two public drop channels");
                    check(drops.get(0).getAsString().startsWith("minecraft:melon_slicex")
                        || drops.get(0).getAsString().equals("minecraft:melonx1"), "melon primary drop domain");
                    check(drops.get(1).getAsString().matches("minecraft:melon_seedsx[12]"), "melon seed drop domain");
                    entry.add("drops", JsonParser.parseString("[\"melon-primary-domain\",\"melon-seed-domain\"]"));
                    continue;
                }
                if (id.equals("mio_icif:pumpkin")) {
                    JsonArray drops = entry.getAsJsonArray("drops");
                    check(drops.size() == 2 && drops.get(0).getAsString().equals("minecraft:pumpkinx1"),
                        "pumpkin primary drop domain");
                    check(drops.get(1).getAsString().matches("minecraft:pumpkin_seedsx[123]"), "pumpkin seed drop domain");
                    entry.add("drops", JsonParser.parseString("[\"minecraft:pumpkinx1\",\"pumpkin-seed-domain\"]"));
                    continue;
                }
                if (!entry.get("class").getAsString().endsWith(".PlantBaseSapling")) continue;
                JsonArray drops = entry.getAsJsonArray("drops");
                check(leaves.containsKey(id), "known sapling " + id);
                check(drops.size() >= 1 && drops.size() <= (id.equals("mio_icif:oak_sapling") ? 4 : 3), "bounded sapling drops " + id);
                check(drops.get(0).getAsString().equals(leaves.get(id)), "sapling primary leaves " + id);
                String wood = id.substring("mio_icif:".length(), id.length() - "_sapling".length());
                for (JsonElement drop : drops) {
                    String value = drop.getAsString();
                    check(value.equals(leaves.get(id))
                        || value.equals("minecraft:" + wood + "_saplingx1")
                        || value.equals("minecraft:" + wood + "_logx1")
                        || id.equals("mio_icif:oak_sapling") && value.equals("minecraft:applex1"),
                        "allowed sapling drop " + id + " " + value);
                }
                entry.remove("drops");
            }
        }
    }

    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        if (tick != 30) return null;
        byte[] referenceBytes = Files.readAllBytes(REFERENCE);
        JsonObject reference = JsonParser.parseString(new String(referenceBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, Object> live = liveProbe.inspect(world, tick);
        Gson gson = new Gson();
        // Round-trip both sides through JSON so integral values produced by a
        // Java Map do not fail Gson tree equality solely because their Number
        // implementation differs from parsed JSON numbers.
        JsonElement liveRows = JsonParser.parseString(gson.toJson(live.get("rows")));
        JsonElement expectedRows = reference.get("rows").deepCopy();

        check(reference.get("passed").getAsBoolean(), "reference passed");
        check(Boolean.TRUE.equals(live.get("passed")), "live baseline probe passed");
        check(expectedRows != null && expectedRows.isJsonArray(), "reference rows present");
        check(liveRows.isJsonArray(), "live rows present");
        check(expectedRows.getAsJsonArray().size() == 5, "reference row count");
        check(liveRows.getAsJsonArray().size() == 5, "live row count");
        checkAndNormalizeSaplingDrops(expectedRows);
        checkAndNormalizeSaplingDrops(liveRows);
        check(expectedRows.equals(liveRows), "all public crop behavior rows exactly match R153");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", true);
        result.put("assertions", assertions + ((Number)live.get("assertions")).intValue());
        result.put("comparison_assertions", assertions);
        result.put("baseline_assertions", live.get("assertions"));
        result.put("rows", ((List<?>)live.get("rows")).size());
        result.put("reference_sha256", sha256(referenceBytes));
        result.put("scope", "exact R154 product comparison with frozen R153 public registry, block entity, plant catalog and analyzer behavior");
        Files.writeString(Path.of("crop-replacement-r156-result.json"), gson.toJson(result));
        System.out.println("SCEX_CROP_REPLACEMENT_R156_PASS assertions=" + result.get("assertions"));
        return result;
    }
}
