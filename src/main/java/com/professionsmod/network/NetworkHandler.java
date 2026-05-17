package com.professionsmod.network;

import com.professionsmod.ProfessionsMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkHandler {

    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(ProfessionsMod.MODID, "main"),
        () -> PROTOCOL,
        PROTOCOL::equals,
        PROTOCOL::equals
    );

    private static int id = 0;

    public static void init() {
        CHANNEL.registerMessage(id++, PacketSyncData.class, PacketSyncData::encode, PacketSyncData::decode, PacketSyncData::handle, java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, PacketUnlockSkill.class, PacketUnlockSkill::encode, PacketUnlockSkill::decode, PacketUnlockSkill::handle, java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, PacketSetProfession.class, PacketSetProfession::encode, PacketSetProfession::decode, PacketSetProfession::handle, java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    public static void sendToClient(Object packet, ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendToServer(Object packet) {
        CHANNEL.sendToServer(packet);
    }
}
