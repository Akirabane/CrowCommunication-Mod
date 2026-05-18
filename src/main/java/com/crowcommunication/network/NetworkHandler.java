package com.crowcommunication.network;

import com.crowcommunication.CrowCommunicationMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * Canal réseau Forge SimpleChannel du mod.
 *
 * <p>Centralise l'enregistrement de tous les packets et expose des helpers
 * d'envoi directionnels (serveur → client et client → serveur).</p>
 */
public class NetworkHandler {

    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(CrowCommunicationMod.MODID, "main"),
        () -> PROTOCOL,
        PROTOCOL::equals,
        PROTOCOL::equals
    );

    private static int id = 0;

    public static void init() {
        CHANNEL.registerMessage(id++, PacketOpenCompose.class, PacketOpenCompose::encode, PacketOpenCompose::decode, PacketOpenCompose::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, PacketSendMessage.class, PacketSendMessage::encode, PacketSendMessage::decode, PacketSendMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, PacketCancelLetter.class, PacketCancelLetter::encode, PacketCancelLetter::decode, PacketCancelLetter::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, PacketMCEFReady.class, PacketMCEFReady::encode, PacketMCEFReady::decode, PacketMCEFReady::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    /**
     * Envoie un packet depuis le serveur vers un client spécifique.
     *
     * @param packet le packet à envoyer
     * @param player le joueur destinataire
     */
    public static void sendToClient(Object packet, ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /**
     * Envoie un packet depuis le client vers le serveur.
     *
     * @param packet le packet à envoyer
     */
    public static void sendToServer(Object packet) {
        CHANNEL.sendToServer(packet);
    }
}
