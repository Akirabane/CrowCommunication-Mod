package com.crowcommunication.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Packet serveur → client forçant la fermeture de l'écran de composition.
 *
 * <p>Filet de sécurité : le bridge JS ↔ Java via console.log de CEF est parfois muet,
 * laissant le menu MCEF ouvert après l'envoi/annulation. Le serveur garantit la fermeture.</p>
 */
public class PacketCloseCompose {
    public PacketCloseCompose() {}
    public static void encode(PacketCloseCompose p, FriendlyByteBuf buf) {}
    public static PacketCloseCompose decode(FriendlyByteBuf buf) { return new PacketCloseCompose(); }

    public static void handle(PacketCloseCompose p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof com.crowcommunication.client.web.WebMenuScreen) {
                    mc.setScreen(null);
                }
            })
        );
        ctx.get().setPacketHandled(true);
    }
}
