// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.Crop.mio_icif_crop_stick;
import com.singularity_iteration.mio_icif.Blocks.entity.crop.mio_icif_crop_entity;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_matron_elc;
import com.singularity_iteration.mio_icif.Blocks.mio_icif_blocks;
import com.singularity_iteration.mio_icif.Items.Normal.mio_icif_normal;
import com.singularity_iteration.mio_icif.Items.Resource.mio_icif_resources;
import com.singularity_iteration.mio_icif.api.crop.PlantType;
import com.singularity_iteration.mio_icif.api.internal.crop.PlantHybridization;
import com.singularity_iteration.mio_icif.api.internal.crop.PlantRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** R174 normal-tick crop lifecycle, hybridization, tag reload and cold-JVM probe. */
public final class CropLifecycleWorldProbe {
    private static final Path MARKER = Path.of("crop-lifecycle-r174.json");
    private static final Path CHECKPOINT = Path.of("world/scex-crop-lifecycle-r174-checkpoint.json");
    private static final Path RESULT = Path.of("crop-lifecycle-r174-result.json");
    private static final Path FERTILIZER_TAG = Path.of(
        "world/datapacks/scex-crop-r174/data/c/tags/item/fertilizers.json");
    private static final TagKey<Item> FERTILIZERS = TagKey.create(Registries.ITEM,
        ResourceLocation.fromNamespaceAndPath("c", "fertilizers"));
    private static final TagKey<Item> SEEDS = TagKey.create(Registries.ITEM,
        ResourceLocation.fromNamespaceAndPath("c", "seeds"));

    private static final BlockPos WEED = new BlockPos(2600, 90, 0);
    private static final BlockPos SAPLING = new BlockPos(2602, 90, 0);
    private static final BlockPos WHEAT = new BlockPos(2604, 90, 0);
    private static final BlockPos BLAZEREED = new BlockPos(2606, 90, 0);
    private static final BlockPos FORCED = new BlockPos(2620, 90, 0);
    private static final BlockPos MATRON = new BlockPos(2680, 90, 0);
    private static final List<BlockPos> GROWTH = List.of(WEED, SAPLING, WHEAT, BLAZEREED);
    private static final List<BlockPos> NATURAL = naturalCenters();

    private final Gson gson = new Gson();
    private final JsonObject marker;
    private final String revision;
    private final String phase;
    private final List<String> groups = new ArrayList<>();
    private JsonObject checkpoint;
    private List<Map<String, Object>> restartBaseline;
    private int assertions;
    private int tagGeneration;
    private int initialNaturalSuccess;

    public CropLifecycleWorldProbe() throws Exception {
        marker = JsonParser.parseString(Files.readString(MARKER)).getAsJsonObject();
        revision = marker.get("revision").getAsString();
        phase = marker.get("phase").getAsString();
        check(revision.equals("R176"), "marker revision");
        check(phase.equals("initial") || phase.equals("restart"), "known phase");
        if (phase.equals("restart")) {
            checkpoint = JsonParser.parseString(Files.readString(CHECKPOINT)).getAsJsonObject();
            tagGeneration = checkpoint.get("tag_generation").getAsInt();
        }
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError("R176 crop lifecycle " + phase + " " + label);
    }

    private static List<BlockPos> naturalCenters() {
        List<BlockPos> result = new ArrayList<>();
        for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
            result.add(new BlockPos(2640 + x * 4, 90, z * 4));
        }
        return List.copyOf(result);
    }

    /** Every world position the fixture must list and keep loaded. */
    public static List<BlockPos> fixturePositions() {
        List<BlockPos> result = new ArrayList<>(GROWTH);
        result.add(FORCED);
        result.add(FORCED.west());
        result.add(FORCED.east());
        for (BlockPos center : NATURAL) {
            result.add(center);
            result.add(center.west());
            result.add(center.east());
        }
        result.add(MATRON);
        return List.copyOf(result);
    }

    private PlantType plant(String id) {
        PlantType result = PlantRegistry.instance.getPlant("mio_icif", id);
        check(result != null, "registered plant " + id);
        return result;
    }

    private mio_icif_crop_entity crop(ServerLevel world, BlockPos pos) {
        var blockEntity = world.getBlockEntity(pos);
        check(blockEntity instanceof mio_icif_crop_entity, "crop entity at " + pos.toShortString());
        return (mio_icif_crop_entity)blockEntity;
    }

    private mio_icif_crop_entity place(ServerLevel world, BlockPos pos, PlantType plant, int stage,
            int growth, int yield, int resilience, boolean hybridBase) {
        world.setBlockAndUpdate(pos.below(), Blocks.FARMLAND.defaultBlockState());
        world.setBlockAndUpdate(pos, mio_icif_blocks.CROP_STICK.get().defaultBlockState());
        mio_icif_crop_entity crop = crop(world, pos);
        crop.setPlant(plant);
        crop.setGrowthStage(stage);
        crop.setGrowthSpeed(growth);
        crop.setYield(yield);
        crop.setResilience(resilience);
        crop.setNutrients(100);
        crop.setWater(100);
        crop.setScanLevel(plant == null ? 0 : 2);
        crop.setProgress(0);
        crop.setHybridBase(hybridBase);
        crop.updateState();
        return crop;
    }

    private void placeHybridCluster(ServerLevel world, BlockPos center, boolean force) {
        place(world, center.west(), plant("wheat"), 6, 4, 8, 12, false);
        place(world, center.east(), plant("redwheat"), 6, 10, 14, 18, false);
        mio_icif_crop_entity target = place(world, center, null, 0, 0, 0, 0, true);
        if (force) {
            check(PlantHybridization.forceHybridize(target), "forced real-neighbor hybridization");
            checkHybridChild(target, "forced child");
            check(target.getGrowthStage() == 1, "forced child starts at stage one");
            check(target.getProgress() == 0, "forced child starts with zero progress");
        }
    }

    private static boolean sharesTrait(PlantType left, PlantType right) {
        List<String> traits = Arrays.asList(left.getTraits());
        return Arrays.stream(right.getTraits()).anyMatch(traits::contains);
    }

    private void checkHybridChild(mio_icif_crop_entity child, String label) {
        PlantType wheat = plant("wheat"), redwheat = plant("redwheat");
        check(child.getPlant() != null, label + " exists");
        check(child.getGrowthStage() >= 1, label + " stage is in post-hybrid natural domain");
        check(child.getScanLevel() == 0, label + " scan reset");
        check(!child.isHybridBase(), label + " base cleared");
        check(sharesTrait(child.getPlant(), wheat), label + " shares wheat trait");
        check(sharesTrait(child.getPlant(), redwheat), label + " shares redwheat trait");
        check(Math.abs(child.getGrowthSpeed() - 7) <= 1, label + " growth rounded mean mutation domain");
        check(Math.abs(child.getYield() - 11) <= 1, label + " yield rounded mean mutation domain");
        check(Math.abs(child.getResilience() - 15) <= 1, label + " resilience rounded mean mutation domain");
    }

    private void setupInitial(ServerLevel world) {
        place(world, WEED, plant("weed"), 1, 0, 3, 4, false);
        place(world, SAPLING, plant("oak_sapling"), 1, 0, 3, 4, false);
        place(world, WHEAT, plant("wheat"), 1, 15, 3, 4, false);
        place(world, BLAZEREED, plant("blazereed"), 1, 0, 3, 4, false);
        placeHybridCluster(world, FORCED, true);
        for (BlockPos center : NATURAL) placeHybridCluster(world, center, false);
        world.setBlockAndUpdate(MATRON, mio_icif_blocks.MATRON_ELC.get().defaultBlockState());
        check(world.getBlockEntity(MATRON) instanceof mio_icif_matron_elc, "real crop matron placed");
        validateCooldownRoundTrip(world);
        validateTags(world, Items.STICK, List.of(Items.STRING, Items.FEATHER), "startup stick generation");
        groups.add("normal-tick-growth-fixture");
        groups.add("real-neighbor-hybrid-fixture");
        groups.add("startup-cross-mod-tags");
    }

    private void validateCooldownRoundTrip(ServerLevel world) {
        mio_icif_crop_entity target = crop(world, WEED);
        target.setFertilizerCooldown(200);
        CompoundTag saved = target.saveWithoutMetadata(world.registryAccess());
        check(saved.contains("FertilizerCooldown") && saved.getInt("FertilizerCooldown") == 200,
            "fertilizer cooldown written exactly");
        target.setFertilizerCooldown(0);
        target.loadAdditional(saved, world.registryAccess());
        check(target.getFertilizerCooldown() == 200, "fertilizer cooldown ordinary save-load round trip");
        CompoundTag negative = saved.copy();
        negative.putInt("FertilizerCooldown", -7);
        target.loadAdditional(negative, world.registryAccess());
        check(target.getFertilizerCooldown() == 0, "negative fertilizer cooldown clamps to zero");
        target.loadAdditional(saved, world.registryAccess());
        target.setFertilizerCooldown(0);
        groups.add("fertilizer-cooldown-save-load-contract");
    }

    private void writeFertilizerTag(Item item) throws Exception {
        JsonObject tag = new JsonObject();
        tag.addProperty("replace", false);
        JsonArray values = new JsonArray();
        values.add(BuiltInRegistries.ITEM.getKey(item).toString());
        tag.add("values", values);
        Path temporary = FERTILIZER_TAG.resolveSibling(FERTILIZER_TAG.getFileName() + ".tmp");
        Files.writeString(temporary, gson.toJson(tag));
        Files.move(temporary, FERTILIZER_TAG, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
    }

    private void validateTags(ServerLevel world, Item expected, List<Item> excluded, String label) {
        check(new ItemStack(expected).is(FERTILIZERS), label + " expected datapack fertilizer tag");
        for (Item item : excluded) {
            check(!new ItemStack(item).is(FERTILIZERS), label + " old datapack member absent");
        }
        mio_icif_matron_elc matron = (mio_icif_matron_elc)world.getBlockEntity(MATRON);
        check(matron != null, label + " matron loaded");
        ItemStack tagged = new ItemStack(expected);
        check(matron.isItemValidForSlot(10, tagged), label + " tagged fertilizer first slot");
        check(matron.isItemValidForSlot(16, tagged), label + " tagged fertilizer last slot");
        check(!matron.isItemValidForSlot(9, tagged), label + " herbicide slot rejects fertilizer");
        check(!matron.isItemValidForSlot(2, tagged), label + " output slot rejects fertilizer");
        for (Item item : excluded) {
            check(!matron.isItemValidForSlot(10, new ItemStack(item)), label + " matron rejects old member");
        }
        check(!matron.isItemValidForSlot(10, new ItemStack(Items.DIRT)), label + " unrelated dirt rejected");
        check(matron.isItemValidForSlot(10, new ItemStack(Items.BONE_MEAL)), label + " bone meal accepted");
        check(matron.isItemValidForSlot(10, new ItemStack(mio_icif_resources.FERTILIZER.get())),
            label + " ordinary SI fertilizer accepted");
        check(matron.isItemValidForSlot(10, new ItemStack(mio_icif_resources.FERTILIZER_MATRON.get())),
            label + " matron SI fertilizer accepted");
        for (Item item : List.of(mio_icif_normal.SEED_IRON_RICH.get(), mio_icif_normal.SEED_COPPER_RICH.get(),
                mio_icif_normal.SEED_TIN_RICH.get(), mio_icif_normal.SEED_TITANIUM_RICH.get(),
                mio_icif_normal.SEED_LEAD_RICH.get(), mio_icif_normal.SEED_URANIUM_RICH.get())) {
            check(new ItemStack(item).is(SEEDS), label + " rich seed in c:seeds");
        }
        check(!new ItemStack(mio_icif_normal.CROP_SEED.get()).is(SEEDS), label + " ordinary crop bag excluded from c:seeds");
    }

    private int age(ServerLevel world, BlockPos pos) {
        return world.getBlockState(pos).getValue(mio_icif_crop_stick.AGE);
    }

    private void checkGrowthBoundary(ServerLevel world, BlockPos pos, int stage, int progress, String label) {
        mio_icif_crop_entity crop = crop(world, pos);
        check(crop.getGrowthStage() == stage, label + " stage");
        check(crop.getProgress() == progress, label + " progress");
        check(age(world, pos) == stage, label + " block age");
    }

    private Map<String, Object> snapshot(ServerLevel world, BlockPos pos) {
        mio_icif_crop_entity crop = crop(world, pos);
        CompoundTag saved = crop.saveWithoutMetadata(world.registryAccess());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("x", pos.getX()); row.put("y", pos.getY()); row.put("z", pos.getZ());
        row.put("plant", crop.getPlant() == null ? "" : crop.getPlant().getModId() + ":" + crop.getPlant().getTypeId());
        row.put("traits", crop.getPlant() == null ? List.of() : List.of(crop.getPlant().getTraits()));
        row.put("stage", crop.getGrowthStage()); row.put("progress", crop.getProgress());
        row.put("growth", crop.getGrowthSpeed()); row.put("yield", crop.getYield());
        row.put("resilience", crop.getResilience()); row.put("scan", crop.getScanLevel());
        row.put("fertilizer_cooldown", crop.getFertilizerCooldown());
        row.put("hybrid_base", crop.isHybridBase()); row.put("crop_ticker", saved.getInt("CropTicker"));
        row.put("age", age(world, pos));
        return row;
    }

    private List<Map<String, Object>> snapshots(ServerLevel world, List<BlockPos> positions) {
        return positions.stream().map(pos -> snapshot(world, pos)).toList();
    }

    private int naturalSuccess(ServerLevel world) {
        return (int)NATURAL.stream().filter(pos -> crop(world, pos).getPlant() != null).count();
    }

    private void checkNaturalDomains(ServerLevel world, String label) {
        for (int i = 0; i < NATURAL.size(); i++) {
            mio_icif_crop_entity target = crop(world, NATURAL.get(i));
            if (target.getPlant() == null) {
                check(target.isHybridBase(), label + " unresolved center retains hybrid base " + i);
                check(target.getGrowthStage() == 0 && target.getProgress() == 0,
                    label + " unresolved center remains empty " + i);
            } else {
                checkHybridChild(target, label + " natural child " + i);
            }
        }
    }

    private void writeCheckpoint(ServerLevel world) throws Exception {
        // Leave a value larger than the bounded normal-stop tail so the cold
        // process observes both persisted state and ordinary tick consumption.
        crop(world, WEED).setFertilizerCooldown(200);
        initialNaturalSuccess = naturalSuccess(world);
        check(initialNaturalSuccess >= 1, "at least one bounded natural hybrid success");
        checkNaturalDomains(world, "checkpoint");
        JsonObject out = new JsonObject();
        out.addProperty("revision", revision); out.addProperty("phase", "initial");
        out.addProperty("probe_tick", 3300); out.addProperty("game_time", world.getGameTime());
        out.addProperty("tag_generation", tagGeneration);
        out.add("growth", gson.toJsonTree(snapshots(world, GROWTH)));
        out.add("forced", gson.toJsonTree(snapshot(world, FORCED)));
        out.add("natural", gson.toJsonTree(snapshots(world, NATURAL)));
        out.addProperty("natural_success", initialNaturalSuccess);
        Files.writeString(CHECKPOINT, gson.toJson(out));
        groups.add("tick-3300-cold-checkpoint");
    }

    private Map<String, Object> finishInitial(ServerLevel world) throws Exception {
        checkGrowthBoundary(world, WHEAT, 4, 1907, "wheat after 3380 active ticks");
        checkGrowthBoundary(world, BLAZEREED, 3, 980, "blazereed after 3380 active ticks");
        checkGrowthBoundary(world, WEED, 5, 0, "weed remains mature");
        checkGrowthBoundary(world, SAPLING, 5, 0, "sapling remains mature");
        check(crop(world, WEED).getFertilizerCooldown() == 100,
            "fertilizer cooldown naturally consumes 100 ticks after checkpoint");
        int successes = naturalSuccess(world);
        check(successes >= initialNaturalSuccess, "natural successes monotonic after checkpoint");
        checkNaturalDomains(world, "final");
        JsonObject checkpointFile = JsonParser.parseString(Files.readString(CHECKPOINT)).getAsJsonObject();
        checkpointFile.addProperty("final_observed_tick", 3400);
        checkpointFile.addProperty("final_observed_game_time", world.getGameTime());
        checkpointFile.add("final_observed_growth", gson.toJsonTree(snapshots(world, GROWTH)));
        checkpointFile.add("final_observed_forced", gson.toJsonTree(snapshot(world, FORCED)));
        checkpointFile.add("final_observed_natural", gson.toJsonTree(snapshots(world, NATURAL)));
        checkpointFile.addProperty("final_observed_natural_success", successes);
        Files.writeString(CHECKPOINT, gson.toJson(checkpointFile));
        groups.add("natural-long-growth-boundaries");
        groups.add("bounded-natural-hybridization");
        return finish(world, Map.of("natural_success", successes, "checkpoint_tick", 3300,
            "final_observed_tick", 3400));
    }

    private JsonObject checkpointRow(String name) {
        JsonArray rows = checkpoint.getAsJsonArray(name);
        if (rows == null) throw new IllegalArgumentException("Missing checkpoint rows " + name);
        return rows.get(0).getAsJsonObject();
    }

    private static String string(JsonObject row, String key) { return row.get(key).getAsString(); }
    private static int integer(JsonObject row, String key) { return row.get(key).getAsInt(); }

    private void checkColdIdentity(Map<String, Object> actual, JsonObject expected, String label) {
        check(actual.get("plant").equals(string(expected, "plant")), label + " plant identity");
        check(gson.toJsonTree(actual.get("traits")).equals(expected.get("traits")), label + " traits");
        check(((Number)actual.get("growth")).intValue() == integer(expected, "growth"), label + " growth not rerolled");
        check(((Number)actual.get("yield")).intValue() == integer(expected, "yield"), label + " yield not rerolled");
        check(((Number)actual.get("resilience")).intValue() == integer(expected, "resilience"), label + " resilience not rerolled");
    }

    private int[] advance(String plant, int stage, int progress, int speed, long ticks) {
        int max = switch (plant) { case "mio_icif:wheat" -> 7; case "mio_icif:blazereed" -> 4; default -> stage; };
        int required = plant.equals("mio_icif:wheat") ? 17_391 : plant.equals("mio_icif:blazereed") ? 1_200 : 1;
        long next = progress;
        for (long i = 0; i < ticks && stage < max; i++) {
            next += Math.max(1, 1 + speed);
            if (next >= required) { next -= required; stage++; }
        }
        return new int[]{stage, (int)next};
    }

    private void startRestart(ServerLevel world) {
        long checkpointTime = checkpoint.get("game_time").getAsLong();
        long savedTime = checkpoint.get("final_observed_game_time").getAsLong();
        long elapsed = world.getGameTime() - savedTime;
        check(savedTime - checkpointTime == 100, "exact checkpoint-to-normal-stop tail");
        check(elapsed == 1, "exact saved game-time to first observation delta");
        List<Map<String, Object>> actualGrowth = snapshots(world, GROWTH);
        JsonArray expectedGrowth = checkpoint.getAsJsonArray("final_observed_growth");
        for (int i = 0; i < GROWTH.size(); i++) {
            JsonObject expected = expectedGrowth.get(i).getAsJsonObject();
            Map<String, Object> actual = actualGrowth.get(i);
            checkColdIdentity(actual, expected, "cold growth " + i);
            check(((Number)actual.get("stage")).intValue() == integer(expected, "stage"),
                "cold startup stage equals final saved snapshot " + i);
            check(((Number)actual.get("progress")).intValue() == integer(expected, "progress"),
                "cold startup progress equals final saved snapshot " + i);
            check(((Number)actual.get("fertilizer_cooldown")).intValue() == integer(expected, "fertilizer_cooldown"),
                "cold startup cooldown equals final saved snapshot " + i);
        }
        Map<String, Object> forced = snapshot(world, FORCED);
        checkColdIdentity(forced, checkpoint.getAsJsonObject("final_observed_forced"), "forced child cold");

        JsonArray expectedNatural = checkpoint.getAsJsonArray("final_observed_natural");
        for (int i = 0; i < NATURAL.size(); i++) {
            JsonObject expected = expectedNatural.get(i).getAsJsonObject();
            Map<String, Object> actual = snapshot(world, NATURAL.get(i));
            if (!string(expected, "plant").isEmpty()) {
                checkColdIdentity(actual, expected, "existing natural child " + i);
            } else {
                check(actual.get("plant").equals(""), "empty natural center remains empty before next attempt " + i);
                check(Boolean.TRUE.equals(actual.get("hybrid_base")), "empty natural hybrid base retained " + i);
                int expectedTicker = integer(expected, "crop_ticker");
                check(((Number)actual.get("crop_ticker")).intValue() == expectedTicker,
                    "empty natural crop ticker equals final saved snapshot " + i);
            }
        }
        validateTags(world, Items.STRING, List.of(Items.STICK, Items.FEATHER), "cold startup string generation");
        initialNaturalSuccess = naturalSuccess(world);
        restartBaseline = actualGrowth;
        marker.addProperty("observed_checkpoint_game_time", checkpointTime);
        marker.addProperty("observed_final_snapshot_game_time", savedTime);
        marker.addProperty("observed_cold_game_time", world.getGameTime());
        marker.addProperty("observed_tail_and_first_tick", elapsed);
        groups.add("cold-jvm-exact-final-snapshot-before-first-entity-tick");
        groups.add("cold-startup-cross-mod-tags");
    }

    private Map<String, Object> finishRestart(ServerLevel world) throws Exception {
        for (int i = 0; i < GROWTH.size(); i++) {
            Map<String, Object> before = restartBaseline.get(i), after = snapshot(world, GROWTH.get(i));
            // The first three restart observations occur before the re-added
            // forceload ticket makes the crop block-ticking.  The tick-1200
            // observation therefore contains 1197 entity ticks, not 1200
            // server ticks; assert the measured active interval explicitly.
            int[] expected = advance((String)before.get("plant"), ((Number)before.get("stage")).intValue(),
                ((Number)before.get("progress")).intValue(), ((Number)before.get("growth")).intValue(), 1197);
            check(((Number)after.get("stage")).intValue() == expected[0], "1197-active-tick cold stage " + i);
            check(((Number)after.get("progress")).intValue() == expected[1], "1197-active-tick cold progress " + i);
            check(((Number)after.get("age")).intValue() == expected[0], "1197-active-tick cold age " + i);
            int expectedCooldown = Math.max(0,
                ((Number)before.get("fertilizer_cooldown")).intValue() - 1200);
            check(((Number)after.get("fertilizer_cooldown")).intValue() == expectedCooldown,
                "1197-active-tick cold cooldown " + i);
        }
        JsonArray checkpointNatural = checkpoint.getAsJsonArray("final_observed_natural");
        for (int i = 0; i < NATURAL.size(); i++) {
            JsonObject prior = checkpointNatural.get(i).getAsJsonObject();
            if (!string(prior, "plant").isEmpty()) {
                check(snapshot(world, NATURAL.get(i)).get("plant").equals(string(prior, "plant")),
                    "existing natural identity stable after restart " + i);
            }
        }
        int successes = naturalSuccess(world);
        check(successes >= initialNaturalSuccess, "natural successes only increase after restart");
        checkNaturalDomains(world, "restart final");
        check(tagGeneration == checkpoint.get("tag_generation").getAsInt() + 1,
            "one observed restart tag reload generation");
        groups.add("cold-natural-1200-tick-continuation");
        groups.add("cold-natural-hybrid-monotonicity");
        return finish(world, Map.of(
            "natural_success", successes,
            "checkpoint_game_time", checkpoint.get("game_time").getAsLong(),
            "cold_start_game_time", marker.get("observed_cold_game_time").getAsLong(),
            "saved_game_time_to_first_observation", marker.get("observed_tail_and_first_tick").getAsLong(),
            "theoretical_no_tail_wheat", Map.of("from_progress", 1907, "to_stage", 5, "to_progress", 3716)));
    }

    private Map<String, Object> finish(ServerLevel world, Map<String, Object> details) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", true); result.put("revision", revision); result.put("phase", phase);
        result.put("assertions", assertions); result.put("groups", List.copyOf(groups));
        result.put("tag_generation", tagGeneration); result.put("details", details);
        result.put("fixture_positions", fixturePositions().stream()
            .map(pos -> List.of(pos.getX(), pos.getY(), pos.getZ())).toList());
        result.put("scope", "Normal server ticks, real crop block entities and neighbors, bounded random hybrid domains, ordinary tag reload, dirty-marked persisted fertilizer cooldown and cold saved world. No client, multiplayer, performance or full F06 closure claim.");
        Files.writeString(RESULT, gson.toJson(result));
        System.out.println("SCEX_CROP_LIFECYCLE_R176_PASS phase=" + phase + " assertions=" + assertions);
        return result;
    }

    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        if (phase.equals("initial")) {
            if (tick == 20) setupInitial(world);
            if (tick == 220) checkGrowthBoundary(world, WEED, 5, 0, "weed 200 active ticks");
            if (tick == 619) checkGrowthBoundary(world, SAPLING, 1, 599, "sapling first-stage pre-boundary");
            if (tick == 620) checkGrowthBoundary(world, SAPLING, 2, 0, "sapling first 600-tick boundary");
            if (tick == 1220) checkGrowthBoundary(world, SAPLING, 3, 0, "sapling second 600-tick boundary");
            if (tick == 1820) checkGrowthBoundary(world, SAPLING, 4, 0, "sapling third 600-tick boundary");
            if (tick == 1970) checkGrowthBoundary(world, SAPLING, 5, 0, "sapling final 150-tick boundary");
            if (tick == 980) writeFertilizerTag(Items.STRING);
            if (tick == 990) validateTags(world, Items.STICK, List.of(Items.STRING, Items.FEATHER),
                "pre-reload still stick generation");
            if (tick == 1020) {
                validateTags(world, Items.STRING, List.of(Items.STICK, Items.FEATHER),
                    "post-reload string generation");
                tagGeneration++;
                groups.add("ordinary-tag-content-reload");
            }
            if (tick == 3300) writeCheckpoint(world);
            if (tick == 3400) return finishInitial(world);
        } else {
            if (tick == 0) startRestart(world);
            // The restart fixture removes all forced chunks at tick 0 and adds
            // them back at the end of tick 1.  WorldScenarioProbe observes
            // before that tick's commands, so the first real block-entity tick
            // is observed at tick 3, not tick 1 or tick 2: the forceload
            // command is applied after the tick-1 observation and the chunk
            // ticket becomes block-ticking on the following server tick.
            if (tick == 3) {
                check(crop(world, WEED).getFertilizerCooldown() == 99,
                    "first loaded entity tick decrements persisted cooldown once");
                checkGrowthBoundary(world, WHEAT, 4, 1923,
                    "first loaded entity tick advances wheat once");
            }
            if (tick == 80) writeFertilizerTag(Items.FEATHER);
            if (tick == 90) validateTags(world, Items.STRING, List.of(Items.STICK, Items.FEATHER),
                "restart pre-reload still string generation");
            if (tick == 101) check(crop(world, WEED).getFertilizerCooldown() == 1,
                "cold fertilizer cooldown reaches one without underflow");
            if (tick == 102) check(crop(world, WEED).getFertilizerCooldown() == 0,
                "cold fertilizer cooldown reaches zero exactly");
            if (tick == 120) {
                validateTags(world, Items.FEATHER, List.of(Items.STICK, Items.STRING),
                    "restart post-reload feather generation");
                tagGeneration++;
                groups.add("restart-tag-content-reload");
            }
            if (tick == 1200) return finishRestart(world);
        }
        return null;
    }
}
