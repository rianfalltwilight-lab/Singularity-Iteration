// SPDX-License-Identifier: Apache-2.0
package com.singularity_iteration.mio_icif.Items.Armor;

import com.singularity_iteration.mio_icif.api.item.ISolarHelmetItem;
import dev.scex.si.energy.SolarHelmetCharging;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** Independently implemented SI solar helmet from public ABI and ordinary gameplay observations. */
@SuppressWarnings({"null", "deprecation"})
public class mio_icif_solar_helmet extends mio_icif_armor_elc implements ISolarHelmetItem {
    public static final int SOLAR_HELMET_MAX_ENERGY = 8000;
    public static final int SOLAR_CHARGE_PER_TICK = 1;

    public mio_icif_solar_helmet(Holder<ArmorMaterial> material, Properties properties) {
        super(material, Type.HELMET, properties, SOLAR_HELMET_MAX_ENERGY, 0,
            "solar", SOLAR_CHARGE_PER_TICK, 0, 1);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean selected) {
        super.inventoryTick(stack, level, entity, slotId, selected);
        if (level.isClientSide || !(entity instanceof Player player)
                || player.getItemBySlot(EquipmentSlot.HEAD) != stack) return;
        BlockPos above = player.blockPosition().above();
        int generation = level.isDay() && level.canSeeSky(above) && !level.isRainingAt(above)
            ? SOLAR_CHARGE_PER_TICK : 0;
        SolarHelmetCharging.tickChestOnly(stack, this, level, player, generation, SOLAR_CHARGE_PER_TICK);
    }

    @Override
    public ItemStack getDefaultInstance() {
        return new ItemStack(this);
    }

    @Override
    @Nullable
    public ResourceLocation getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot,
            ArmorMaterial.Layer layer, boolean innerModel) {
        return ResourceLocation.fromNamespaceAndPath("mio_icif", "textures/armor/solar_1.png");
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.mio_icif.solar_helmet.solar", SOLAR_CHARGE_PER_TICK)
            .withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("tooltip.mio_icif.solar_helmet.distribute")
            .withStyle(ChatFormatting.GRAY));
    }

    @Override public long getGenerationRate() { return SOLAR_CHARGE_PER_TICK; }
    @Override public boolean requiresSky() { return true; }
    @Override public boolean isDayOnly() { return true; }
}
