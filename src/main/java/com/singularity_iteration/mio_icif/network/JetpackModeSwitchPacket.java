// SPDX-License-Identifier: Apache-2.0
package com.singularity_iteration.mio_icif.network;

import com.singularity_iteration.mio_icif.api.item.IJetpackItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Explicit client request to toggle the worn jetpack's hover mode. */
public record JetpackModeSwitchPacket() implements CustomPacketPayload {
    public static final Type<JetpackModeSwitchPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("mio_icif", "jetpack_mode_switch"));
    public static final StreamCodec<FriendlyByteBuf, JetpackModeSwitchPacket> CODEC =
        StreamCodec.unit(new JetpackModeSwitchPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(JetpackModeSwitchPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ItemStack stack = player.getItemBySlot(EquipmentSlot.CHEST);
            if (!(stack.getItem() instanceof IJetpackItem jetpack)) return;
            IJetpackItem.JetpackMode next = jetpack.getMode(stack) == IJetpackItem.JetpackMode.HOVER
                ? IJetpackItem.JetpackMode.FLIGHT
                : IJetpackItem.JetpackMode.HOVER;
            jetpack.setMode(stack, next);
        });
    }
}
