// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import dev.scex.energy.minecraft.IndependentTransformerBlockEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Experimental public-platform interception, enabled only by a frozen fixture marker. */
public final class TransformerFactoryProbe {
    private record Tier(String entity, long low) { }
    private static final Map<String,Tier> TIERS=Map.of(
        "mio_icif:wiring/transformer_lv_mv",new Tier("mio_icif:transformer_lv_mv",32),
        "mio_icif:wiring/transformer_mv_hv",new Tier("mio_icif:transformer_mv_hv",128),
        "mio_icif:wiring/transformer_hv_ev",new Tier("mio_icif:transformer_hv_ev",512),
        "mio_icif:wiring/transformer_ev_sc",new Tier("mio_icif:transformer_ev_sc",2048));
    private static final boolean ENABLED=Files.exists(Path.of("transformer-factory.json"));
    private static final AtomicLong PLACED=new AtomicLong(), LOADED=new AtomicLong(), TICKERS=new AtomicLong();
    private TransformerFactoryProbe() { }
    private static BlockEntityType<?> type(BlockState state) {
        if (!ENABLED) return null;
        var tier=TIERS.get(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        if (tier==null) return null;
        var type=BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(ResourceLocation.parse(tier.entity())).orElseThrow();
        if (!type.isValid(state)) throw new IllegalStateException("Observed transformer type does not admit block");
        return type;
    }
    public static BlockEntity placed(BlockPos position, BlockState state) {
        var type=type(state);if(type==null)return null;
        PLACED.incrementAndGet();return create(type,position,state);
    }
    public static BlockEntity loaded(BlockEntityType<?> requested, BlockPos position, BlockState state) {
        var type=type(state);if(type==null || type!=requested)return null;
        LOADED.incrementAndGet();return create(type,position,state);
    }
    private static BlockEntity create(BlockEntityType<?> type,BlockPos position,BlockState state) {
        long low=TIERS.get(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()).low();
        return new IndependentTransformerBlockEntity(type,position,state,low,1);
    }
    public static boolean suppressTicker(BlockEntity entity) {
        if (!ENABLED || !(entity instanceof IndependentTransformerBlockEntity)) return false;
        TICKERS.incrementAndGet();return true;
    }
    public static Map<String,Object> metrics() {
        return Map.of("enabled",ENABLED,"placed",PLACED.get(),"loaded",LOADED.get(),"suppressed_tickers",TICKERS.get());
    }
}
