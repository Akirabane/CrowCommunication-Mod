package com.professionsmod.network;

import com.professionsmod.capability.PlayerDataCapability;
import com.professionsmod.data.PlayerProfessionData;
import com.professionsmod.profession.ProfessionType;
import com.professionsmod.profession.Profession;
import com.professionsmod.event.ProfessionRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PacketUnlockSkill {

    private final String professionName;
    private final String skillId;

    public PacketUnlockSkill(ProfessionType type, String skillId) {
        this.professionName = type.name();
        this.skillId = skillId;
    }

    public static void encode(PacketUnlockSkill packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.professionName);
        buf.writeUtf(packet.skillId);
    }

    public static PacketUnlockSkill decode(FriendlyByteBuf buf) {
        return new PacketUnlockSkill(ProfessionType.valueOf(buf.readUtf()), buf.readUtf());
    }

    public static void handle(PacketUnlockSkill packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ProfessionType type = ProfessionType.valueOf(packet.professionName);
            PlayerProfessionData data = PlayerDataCapability.get(player);
            Profession profession = ProfessionRegistry.get(type);
            if (profession == null) return;

            if (profession.getSkillTree().canUnlock(packet.skillId, data.getUnlockedSkills(type))) {
                if (data.unlockSkill(type, packet.skillId)) {
                    NetworkHandler.sendToClient(PacketSyncData.from(data), player);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
