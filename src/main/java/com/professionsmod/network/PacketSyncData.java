package com.professionsmod.network;

import com.professionsmod.capability.PlayerDataCapability;
import com.professionsmod.data.PlayerProfessionData;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PacketSyncData {

    private final CompoundTag data;

    public PacketSyncData(CompoundTag data) {
        this.data = data;
    }

    public static void encode(PacketSyncData packet, FriendlyByteBuf buf) {
        buf.writeNbt(packet.data);
    }

    public static PacketSyncData decode(FriendlyByteBuf buf) {
        return new PacketSyncData(buf.readNbt());
    }

    public static void handle(PacketSyncData packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                PlayerDataCapability.getOptional(player).ifPresent(data -> data.load(packet.data));
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public static PacketSyncData from(PlayerProfessionData data) {
        return new PacketSyncData(data.save());
    }
}
