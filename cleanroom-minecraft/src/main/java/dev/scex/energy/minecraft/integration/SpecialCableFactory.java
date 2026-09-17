// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy.minecraft.integration;

import dev.scex.energy.minecraft.IndependentSpecialCableBlockEntity;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Maintained opt-in special cable factory; only public Minecraft registry and entity APIs are used. */
public final class SpecialCableFactory {
    private static boolean enabled() { return dev.scex.energy.IndependentEnergyMode.feature("specialCables"); }
    private static final Map<String, String> TYPES = Map.of(
        "mio_icif:wiring/block_eu_detector_cable", "mio_icif:wire_detector",
        "mio_icif:wiring/block_eu_splitter_cable", "mio_icif:wire_splitter");
    private SpecialCableFactory() { }
    public static boolean controls(BlockState state) { return enabled() && TYPES.containsKey(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()); }
    public static boolean detector(BlockState state) { return controls(state) && BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().equals("wiring/block_eu_detector_cable"); }
    public static BlockEntity create(BlockEntityType<?> requested, BlockPos at, BlockState state) {
        if (!controls(state)) return null;
        var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(ResourceLocation.parse(TYPES.get(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()))).orElseThrow();
        if (requested != null && requested != type) return null;
        if (!type.isValid(state)) throw new IllegalStateException("Special cable type does not admit observed block");
        return new IndependentSpecialCableBlockEntity(type, at, state, detector(state));
    }
    public static boolean suppressTicker(BlockEntity tile) { return enabled() && tile instanceof IndependentSpecialCableBlockEntity; }
}
