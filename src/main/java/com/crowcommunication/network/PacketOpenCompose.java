package com.crowcommunication.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Packet serveur → client ordonnant l'ouverture de l'interface de composition.
 *
 * <p>Transporte aussi le cooldown d'usurpation restant en secondes pour que l'UI puisse
 * désactiver le toggle d'usurpation au lieu d'envoyer le joueur dans un QTE inutile.</p>
 */
public class PacketOpenCompose {

    private final String recipients;
    private final int forgeCooldownSec;

    public PacketOpenCompose() { this("", 0); }
    public PacketOpenCompose(String recipients) { this(recipients, 0); }
    public PacketOpenCompose(String recipients, int forgeCooldownSec) {
        this.recipients = recipients == null ? "" : recipients;
        this.forgeCooldownSec = Math.max(0, forgeCooldownSec);
    }

    public static void encode(PacketOpenCompose p, FriendlyByteBuf buf) {
        buf.writeUtf(p.recipients, 512);
        buf.writeVarInt(p.forgeCooldownSec);
    }
    public static PacketOpenCompose decode(FriendlyByteBuf buf) {
        return new PacketOpenCompose(buf.readUtf(512), buf.readVarInt());
    }

    public static void handle(PacketOpenCompose p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                com.crowcommunication.client.web.ComposeScreenOpener.open(p.recipients, p.forgeCooldownSec)
            )
        );
        ctx.get().setPacketHandled(true);
    }
}
