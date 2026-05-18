package com.crowcommunication.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Packet serveur → client ordonnant l'ouverture de l'interface de composition.
 *
 * @param recipients liste de destinataires pré-remplie, séparée par des virgules (peut être vide)
 */
@SuppressWarnings("null")
public class PacketOpenCompose {

    private final String recipients;

    public PacketOpenCompose() { this(""); }
    public PacketOpenCompose(String recipients) { this.recipients = recipients == null ? "" : recipients; }
    public static void encode(PacketOpenCompose p, FriendlyByteBuf buf) { buf.writeUtf(p.recipients, 512); }
    public static PacketOpenCompose decode(FriendlyByteBuf buf) { return new PacketOpenCompose(buf.readUtf(512)); }

    public static void handle(PacketOpenCompose p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                com.crowcommunication.client.web.ComposeScreenOpener.open(p.recipients)
            )
        );
        ctx.get().setPacketHandled(true);
    }
}
