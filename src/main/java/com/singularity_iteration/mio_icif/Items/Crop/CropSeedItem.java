package com.singularity_iteration.mio_icif.Items.Crop;

import com.singularity_iteration.mio_icif.api.crop.IPlanter;
import com.singularity_iteration.mio_icif.api.internal.crop.PlantRegistry;
import com.singularity_iteration.mio_icif.api.crop.PlantType;
import com.singularity_iteration.mio_icif.api.item.ICropSeedItem;
import dev.scex.si.processing.CropSeedData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 作物种子物品
 * 用于在种植架上种植作�? * 植物类型通过NBT数据存储
 */
@SuppressWarnings("null")
public class CropSeedItem extends Item implements ICropSeedItem {

    public CropSeedItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if(player==null || stack.isEmpty())return InteractionResult.PASS;

        // 检查是否是种植架方法
   if (!isCropStick(state)) {
            return InteractionResult.PASS;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof IPlanter planter)) {
            return InteractionResult.PASS;
        }

        // 如果种子没有NBT数据（空种子袋），尝试收集种�
   PlantType currentPlant = getPlantType(stack);
        if (currentPlant == null) {
            if(!CropSeedData.isEmptyBag(stack))return InteractionResult.FAIL;
            return tryCollectSeed(level, planter, stack, player);
        }

        // 否则尝试种植
        return tryPlant(level, planter, stack, player);
    }

    /**
     * 尝试种植作物
     */
    private InteractionResult tryPlant(Level level, IPlanter planter, ItemStack stack, Player player) {
        // 检查种植架是否为空
        if (planter.getPlant() != null) {
            return InteractionResult.PASS;
        }

        // 从NBT获取植物类型
        PlantType plantType = getPlantType(stack);
        if (plantType == null) {
            return InteractionResult.PASS;
        }

        if (!level.isClientSide) {
            var traits=CropSeedData.traits(stack);

            // 种植作物
            planter.setPlant(plantType);
            planter.setGrowthStage(1);
            planter.setGrowthSpeed(traits.growth());
            planter.setYield(traits.yield());
            planter.setResilience(traits.resilience());
            planter.setScanLevel(traits.scan());
            planter.setProgress(0);
            planter.setHybridBase(false);
            planter.updateState();

            // 消耗种子并返还空种子袋
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
                // 返还空种子袋
                ItemStack emptyBag = new ItemStack(this);
                if (!player.getInventory().add(emptyBag)) {
                    // 如果背包满了，掉落在地上
                    player.drop(emptyBag, false);
                }
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * 尝试收集种子（空种子袋功能）
     */
    private InteractionResult tryCollectSeed(Level level, IPlanter planter, ItemStack stack, Player player) {
        if (player == null || !CropSeedData.isEmptyBag(stack)) {
            return InteractionResult.PASS;
        }

        PlantType plant = planter.getPlant();
        if (plant == null) {
            if(!level.isClientSide)player.sendSystemMessage(Component.translatable("message.mio_icif.crop_stick_empty"));
            return InteractionResult.FAIL;
        }

        // 检查作物是否成�
   if (planter.getGrowthStage() < plant.getMaxGrowthStage()) {
            if(!level.isClientSide)player.sendSystemMessage(Component.translatable("message.mio_icif.crop_not_mature"));
            return InteractionResult.FAIL;
        }

        if(level.isClientSide)return InteractionResult.SUCCESS;
        ItemStack filled=CropSeedData.fillOne(stack,plant.getModId(),plant.getTypeId(),
            planter.getGrowthSpeed(),planter.getYield(),planter.getResilience());
        if(filled.isEmpty())return InteractionResult.FAIL;
        if(stack.getCount()==1) {
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,filled.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA));
        } else {
            stack.shrink(1);
            if(!player.getInventory().add(filled))player.drop(filled,false);
        }

        player.sendSystemMessage(Component.translatable("message.mio_icif.seed_collected", Component.translatable(plant.getTranslationKey())));

        return InteractionResult.SUCCESS;
    }

    private boolean isCropStick(BlockState state) {
        return state.getBlock() instanceof com.singularity_iteration.mio_icif.Blocks.Crop.mio_icif_crop_stick ||
               state.getBlock() instanceof com.singularity_iteration.mio_icif.Blocks.Crop.mio_icif_crop_stick_upgraded;
    }

    /**
     * 从ItemStack的NBT中获取植物类�
*/
    public static PlantType getPlantType(ItemStack stack) {
        var customData = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (customData != null) {
            var tag = customData.copyTag();
            String plantModId = tag.getString("PlantModId");
            String plantId = tag.getString("PlantId");
            if (!plantModId.isEmpty() && !plantId.isEmpty()) {
                return PlantRegistry.instance.getPlant(plantModId, plantId);
            }
        }
        return null;
    }

    @Override
    public Component getName(ItemStack stack) {
        PlantType plant = getPlantType(stack);
        int scanLevel = getScanLevel(stack);
        if (plant != null) {
            if (scanLevel == 0) {
                return Component.translatable("ic2.crop.unknown");
            }
            return Component.translatable("item.mio_icif.crop_seed", Component.translatable(plant.getTranslationKey()));
        }
        return Component.translatable(CropSeedData.isEmptyBag(stack)?"item.mio_icif.empty_seed_bag":"ic2.crop.unknown");
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        PlantType plant = getPlantType(stack);
        int scanLevel = getScanLevel(stack);
        if (plant != null) {
            if (scanLevel == 0) {
                tooltipComponents.add(Component.translatable("ic2.crop.unknown").withStyle(net.minecraft.ChatFormatting.GRAY));
                tooltipComponents.add(Component.translatable("tooltip.mio_icif.crop.scan_level_0"));
            } else {
                if (scanLevel >= 1) {
                    tooltipComponents.add(Component.translatable(plant.getTranslationKey()).withStyle(net.minecraft.ChatFormatting.GREEN));
                }
                if (scanLevel >= 2) {
                    tooltipComponents.add(Component.translatable("tooltip.mio_icif.crop.level", plant.getStats().getLevel()));
                }
                if (scanLevel >= 4) {
                    var customData = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                    if (customData != null) {
                        var traits=CropSeedData.traits(stack);
                        int growthSpeed = traits.growth();
                        int yield = traits.yield();
                        int resilience = traits.resilience();

                        if (growthSpeed > 0 || yield > 0 || resilience > 0) {
                            tooltipComponents.add(Component.translatable("tooltip.mio_icif.crop.growth_speed", growthSpeed));
                            tooltipComponents.add(Component.translatable("tooltip.mio_icif.crop.yield", yield));
                            tooltipComponents.add(Component.translatable("tooltip.mio_icif.crop.resilience", resilience));
                        }
                    }
                }
                tooltipComponents.add(Component.translatable("tooltip.mio_icif.crop.scan_level", scanLevel));
            }
        } else if(CropSeedData.isEmptyBag(stack)) {
            tooltipComponents.add(Component.translatable("tooltip.mio_icif.empty_seed_bag"));
            tooltipComponents.add(Component.translatable("tooltip.mio_icif.empty_seed_bag.usage"));
        } else {
            tooltipComponents.add(Component.translatable("ic2.crop.unknown").withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    /**
     * 创建一个带有属性的种子
     */
    public static ItemStack createSeedStack(Item item, String plantModId, String plantId, int growthSpeed, int yield, int resilience) {
        return CropSeedData.fillOne(new ItemStack(item),plantModId,plantId,growthSpeed,yield,resilience);
    }

    public static int getScanLevel(ItemStack stack) {
        return CropSeedData.traits(stack).scan();
    }

    public static void setScanLevel(ItemStack stack, int level) {
        CropSeedData.setScan(stack,level);
    }

    public static void incrementScanLevel(ItemStack stack) {
        setScanLevel(stack, getScanLevel(stack) + 1);
    }

    public static int getGrowthFromStack(ItemStack stack) {
        return CropSeedData.traits(stack).growth();
    }

    public static int getGainFromStack(ItemStack stack) {
        return CropSeedData.traits(stack).yield();
    }

    public static int getResistanceFromStack(ItemStack stack) {
        return CropSeedData.traits(stack).resilience();
    }
}
