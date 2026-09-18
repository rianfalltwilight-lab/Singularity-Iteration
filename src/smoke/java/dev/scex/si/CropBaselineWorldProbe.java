// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.singularity_iteration.mio_icif.Blocks.entity.crop.mio_icif_crop_entity;
import com.singularity_iteration.mio_icif.Blocks.mio_icif_blocks;
import com.singularity_iteration.mio_icif.Items.Tools.CropAnalyzerItem;
import com.singularity_iteration.mio_icif.api.crop.PlantStats;
import com.singularity_iteration.mio_icif.api.crop.PlantType;
import com.singularity_iteration.mio_icif.api.internal.crop.PlantHybridization;
import com.singularity_iteration.mio_icif.api.internal.crop.PlantRegistry;
import com.singularity_iteration.mio_icif.crop.PlantEatingPlant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Public-method and ordinary-world baseline for the opaque R151 crop group. */
public final class CropBaselineWorldProbe {
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private int assertions;

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError("R151 crop baseline " + label);
    }

    private Map<String, Object> stats() {
        PlantStats stats = new PlantStats(1, 2, 3, 4, 5, 6);
        int[] values = new int[6];
        for (int i = 0; i < values.length; i++) values[i] = stats.stat(i);
        return Map.of(
            "getters", List.of(stats.getLevel(), stats.getChemistry(), stats.getNutrition(),
                stats.getColor(), stats.getMedicinal(), stats.getDanger()),
            "stat_0_to_5", Arrays.stream(values).boxed().toList(),
            "text", stats.toString());
    }

    private Map<String, Object> registry() {
        List<PlantType> plants = PlantRegistry.instance.getAllPlants().stream()
            .sorted(Comparator.comparing(p -> p.getModId() + ":" + p.getTypeId()))
            .toList();
        check(!plants.isEmpty(), "plant registry populated");
        List<Map<String, Object>> sample = new ArrayList<>();
        for (PlantType plant : plants) {
            sample.add(Map.of(
                "id", plant.getModId() + ":" + plant.getTypeId(),
                "class", plant.getClass().getName(),
                "max_stage", plant.getMaxGrowthStage(),
                "harvest_stage", plant.getHarvestStage(),
                "traits", List.of(plant.getTraits())));
        }
        List<Map<String, Object>> baseSeeds = new ArrayList<>();
        for (var seed : PlantRegistry.instance.getAllBaseSeeds()) {
            baseSeeds.add(Map.ofEntries(
                Map.entry("item", BuiltInRegistries.ITEM.getKey(seed.seed.getItem()).toString()),
                Map.entry("plant", seed.plantType.getModId() + ":" + seed.plantType.getTypeId()),
                Map.entry("stage", seed.stage),
                Map.entry("growth", seed.growthSpeed),
                Map.entry("yield", seed.yield),
                Map.entry("resilience", seed.resilience),
                Map.entry("weed_resistance", seed.weedResistance)));
        }
        return Map.of(
            "plants", plants.size(),
            "base_seeds", baseSeeds,
            "entries", sample);
    }

    private Map<String, Object> eatingPlant(mio_icif_crop_entity planter) {
        PlantEatingPlant plant = new PlantEatingPlant();
        planter.setPlant(plant);
        planter.setGrowthStage(plant.getMaxGrowthStage());
        planter.setGrowthSpeed(3);
        planter.setYield(4);
        planter.setResilience(5);
        planter.setNutrients(80);
        planter.setWater(80);
        planter.setWeedControl(20);
        List<String> textures = new ArrayList<>();
        for (int i = 0; i <= plant.getMaxGrowthStage(); i++) textures.add(plant.getTexture(i));
        return Map.ofEntries(
            Map.entry("id", plant.getModId() + ":" + plant.getTypeId()),
            Map.entry("found_by", plant.getFoundBy()),
            Map.entry("translation", plant.getTranslationKey()),
            Map.entry("traits", List.of(plant.getTraits())),
            Map.entry("stats", plant.getStats().toString()),
            Map.entry("max_stage", plant.getMaxGrowthStage()),
            Map.entry("harvest_stage", plant.getHarvestStage()),
            Map.entry("optimal_stage", plant.getOptimalHarvestStage()),
            Map.entry("after_harvest", plant.getStageAfterHarvest()),
            Map.entry("growth_time", plant.getGrowthTime(planter)),
            Map.entry("can_grow", plant.canGrow(planter)),
            Map.entry("harvestable", plant.isHarvestable(planter)),
            Map.entry("drop_gain_chance", plant.dropGainChance()),
            Map.entry("seed_chance", plant.dropSeedChance(planter)),
            Map.entry("drop_count", plant.calculateDropCount(planter)),
            Map.entry("root_depth", plant.getRootDepth(planter)),
            Map.entry("textures", textures),
            Map.entry("extra", plant.getExtraInfo()),
            Map.entry("harvest", stacks(plant.getHarvest(planter))));
    }

    private Map<String, Object> planter(ServerLevel world) {
        BlockPos pos = new BlockPos(1800, 90, 0);
        world.setBlockAndUpdate(pos.below(), Blocks.FARMLAND.defaultBlockState());
        world.setBlockAndUpdate(pos, mio_icif_blocks.CROP_STICK.get().defaultBlockState());
        check(world.getBlockEntity(pos) instanceof mio_icif_crop_entity, "crop block entity created");
        mio_icif_crop_entity planter = (mio_icif_crop_entity)world.getBlockEntity(pos);
        var state = world.getBlockState(pos);
        check(state.getBlock() == mio_icif_blocks.CROP_STICK.get(), "crop stick placed");

        planter.setGrowthStage(2);
        planter.setGrowthSpeed(3);
        planter.setYield(4);
        planter.setResilience(5);
        planter.setNutrients(60);
        planter.setWater(70);
        planter.setWeedControl(8);
        planter.setFertilizerCooldown(9);
        planter.setProgress(10);
        planter.setScanLevel(2);
        planter.setHybridBase(true);
        planter.getCustomData().putString("R151", "baseline");
        planter.updateState();

        CompoundTag saved = planter.saveWithoutMetadata(world.registryAccess());
        ItemStack seed = planter.makeSeeds(new PlantEatingPlant(), 3, 4, 5, 2);
        Map<String, Object> beforePlant = Map.ofEntries(
            Map.entry("age", state.getValue(com.singularity_iteration.mio_icif.Blocks.Crop.mio_icif_crop_stick.AGE)),
            Map.entry("max_age", ((com.singularity_iteration.mio_icif.Blocks.Crop.mio_icif_crop_stick)state.getBlock()).getMaxAge()),
            Map.entry("shape", state.getShape(world, pos).bounds().toString()),
            Map.entry("survives", state.canSurvive(world, pos)),
            Map.entry("growth", planter.getGrowthSpeed()),
            Map.entry("yield", planter.getYield()),
            Map.entry("resilience", planter.getResilience()),
            Map.entry("nutrients", planter.getNutrients()),
            Map.entry("water", planter.getWater()),
            Map.entry("weed_control", planter.getWeedControl()),
            Map.entry("humidity", planter.getHumidity()),
            Map.entry("soil_nutrients", planter.getSoilNutrients()),
            Map.entry("air_quality", planter.getAirQuality()),
            Map.entry("light", planter.getLightLevel()),
            Map.entry("saved", saved.toString()),
            Map.entry("seed", seed.save(world.registryAccess()).toString()),
            Map.entry("empty_hybridize", PlantHybridization.tryHybridize(planter)));

        Map<String, Object> plant = eatingPlant(planter);
        List<String> harvestDrops = planter.getHarvestDrops().stream().map(this::stack).toList();
        return Map.of("block_entity", beforePlant, "eating_plant", plant, "harvest_drops", harvestDrops);
    }

    private Map<String, Object> analyzer() {
        CropAnalyzerItem analyzer = null;
        for (var item : BuiltInRegistries.ITEM) {
            if (item instanceof CropAnalyzerItem found) {
                analyzer = found;
                break;
            }
        }
        check(analyzer != null, "crop analyzer registered");
        var costs = new LinkedHashMap<String, Integer>();
        for (int level = -1; level <= 5; level++) {
            costs.put(Integer.toString(level), CropAnalyzerItem.energyForLevel(level));
        }
        return Map.of(
            "id", BuiltInRegistries.ITEM.getKey(analyzer).toString(),
            "costs", costs,
            "attributes", analyzer.getDefaultAttributeModifiers().toString());
    }

    private Map<String, Object> plantDetails(ServerLevel world) {
        BlockPos pos = new BlockPos(1820, 90, 0);
        world.setBlockAndUpdate(pos.below(), Blocks.FARMLAND.defaultBlockState());
        world.setBlockAndUpdate(pos, mio_icif_blocks.CROP_STICK.get().defaultBlockState());
        check(world.getBlockEntity(pos) instanceof mio_icif_crop_entity, "detail planter created");
        mio_icif_crop_entity planter = (mio_icif_crop_entity)world.getBlockEntity(pos);
        planter.setGrowthSpeed(1);
        planter.setYield(1);
        planter.setResilience(1);
        planter.setNutrients(100);
        planter.setWater(100);
        planter.setWeedControl(10);
        List<Map<String, Object>> details = new ArrayList<>();
        for (PlantType plant : PlantRegistry.instance.getAllPlants().stream()
                .sorted(Comparator.comparing(p -> p.getModId() + ":" + p.getTypeId())).toList()) {
            planter.setPlant(plant);
            planter.setGrowthStage(0);
            boolean growsAtZero = plant.canGrow(planter);
            planter.setGrowthStage(plant.getHarvestStage());
            List<String> textures = new ArrayList<>();
            for (int stage = 0; stage <= plant.getMaxGrowthStage(); stage++) textures.add(plant.getTexture(stage));
            details.add(Map.ofEntries(
                Map.entry("id", plant.getModId() + ":" + plant.getTypeId()),
                Map.entry("class", plant.getClass().getName()),
                Map.entry("found_by", plant.getFoundBy()),
                Map.entry("traits", List.of(plant.getTraits())),
                Map.entry("stats", List.of(plant.getStats().getLevel(), plant.getStats().getChemistry(),
                    plant.getStats().getNutrition(), plant.getStats().getColor(),
                    plant.getStats().getMedicinal(), plant.getStats().getDanger())),
                Map.entry("max", plant.getMaxGrowthStage()),
                Map.entry("harvest", plant.getHarvestStage()),
                Map.entry("optimal", plant.getOptimalHarvestStage()),
                Map.entry("after", plant.getStageAfterHarvest()),
                Map.entry("growth_time", plant.getGrowthTime(planter)),
                Map.entry("grows_at_zero", growsAtZero),
                Map.entry("harvestable", plant.isHarvestable(planter)),
                Map.entry("drops", stacks(plant.getHarvest(planter))),
                Map.entry("seed", stack(plant.getSeedItem(planter))),
                Map.entry("textures", textures)));
        }
        return Map.of("entries", details);
    }

    private List<String> stacks(ItemStack[] stacks) {
        return Arrays.stream(stacks).map(this::stack).toList();
    }

    private String stack(ItemStack stack) {
        return stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount();
    }

    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        if (tick != 30) return Map.of("assertions", assertions, "rows", rows.size());
        rows.add(Map.of("plant_stats", stats()));
        rows.add(Map.of("registry", registry()));
        rows.add(Map.of("planter", planter(world)));
        rows.add(Map.of("analyzer", analyzer()));
        rows.add(Map.of("plant_details", plantDetails(world)));
        Map<String, Object> result = Map.of(
            "passed", true,
            "assertions", assertions,
            "rows", rows,
            "scope", "opaque SI candidate public crop methods, ordinary registry/block entity state and save; no source or bytecode inspection");
        Files.writeString(Path.of("crop-baseline-r151-result.json"), new com.google.gson.Gson().toJson(result));
        System.out.println("SCEX_CROP_BASELINE_R151_PASS rows=" + rows.size() + " assertions=" + assertions);
        return result;
    }
}
