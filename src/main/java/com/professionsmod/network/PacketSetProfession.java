package com.professionsmod.network;

import com.professionsmod.capability.PlayerDataCapability;
import com.professionsmod.data.PlayerProfessionData;
import com.professionsmod.profession.ProfessionType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PacketSetProfession {

    private final String professionName;
    private final boolean isMain;

    public PacketSetProfession(ProfessionType type, boolean isMain) {
        this.professionName = type != null ? type.name() : "";
        this.isMain = isMain;
    }

    public static void encode(PacketSetProfession packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.professionName);
        buf.writeBoolean(packet.isMain);
    }

    public static PacketSetProfession decode(FriendlyByteBuf buf) {
        String name = buf.readUtf();
        boolean isMain = buf.readBoolean();
        ProfessionType type = name.isEmpty() ? null : ProfessionType.valueOf(name);
        return new PacketSetProfession(type, isMain);
    }

    public static void handle(PacketSetProfession packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ProfessionType type = packet.professionName.isEmpty() ? null : ProfessionType.valueOf(packet.professionName);
            PlayerProfessionData data = PlayerDataCapability.get(player);
            if (packet.isMain) {
                data.setMainProfession(type);
            } else {
                data.setSecondaryProfession(type);
            }
            NetworkHandler.sendToClient(PacketSyncData.from(data), player);
        });
        ctx.get().setPacketHandled(true);
    }
}
