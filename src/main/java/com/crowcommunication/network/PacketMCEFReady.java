package com.crowcommunication.network;

import com.crowcommunication.corbeau.ClientReadyState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Packet client → serveur signalant que MCEF est initialisé pour ce joueur.
 *
 * <p>Envoyé à la connexion et après l'initialisation asynchrone de Chromium.
 * Permet au serveur de valider la disponibilité de l'interface avant de spawner un corbeau.</p>
 */
public class PacketMCEFReady {
    public PacketMCEFReady() {}
    public static void encode(PacketMCEFReady p, FriendlyByteBuf buf) {}
    public static PacketMCEFReady decode(FriendlyByteBuf buf) { return new PacketMCEFReady(); }

    public static void handle(PacketMCEFReady p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp != null) ClientReadyState.setReady(sp);
        });
        ctx.get().setPacketHandled(true);
    }
}
