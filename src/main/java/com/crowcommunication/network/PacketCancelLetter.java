package com.crowcommunication.network;

import com.crowcommunication.corbeau.CorbeauManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Packet client → serveur signalant l'annulation de la rédaction.
 *
 * <p>Déclenche le départ immédiat du corbeau d'envoi sans lettre.</p>
 */
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
                NetworkHandler.sendToClient(new PacketCloseCompose(), sender);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
