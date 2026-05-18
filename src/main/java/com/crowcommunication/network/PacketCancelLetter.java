package com.crowcommunication.network;

import com.crowcommunication.corbeau.CorbeauManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PacketCancelLetter {
    public PacketCancelLetter() {}
    public static void encode(PacketCancelLetter p, FriendlyByteBuf buf) {}
    public static PacketCancelLetter decode(FriendlyByteBuf buf) { return new PacketCancelLetter(); }

    public static void handle(PacketCancelLetter p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null) {
                CorbeauManager.takePendingGroup(sender);
                CorbeauManager.onLetterCancelled(sender);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
