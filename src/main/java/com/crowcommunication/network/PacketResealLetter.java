package com.crowcommunication.network;

import com.crowcommunication.corbeau.ForgerySystem;
import com.crowcommunication.corbeau.LetterRenderer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Packet client → serveur demandant de remplacer le sceau de la lettre en main.
 *
 * <p>Le serveur revalide le pseudo cible via {@link ForgerySystem} (cooldown, ProfileCache,
 * tirage 30 %). L'item en main est réécrit avec le nouveau sender si la tentative réussit.</p>
 */
public class PacketResealLetter {

    private final String forgeName;
    private final int qteRounds;

    public PacketResealLetter(String forgeName) {
        this(forgeName, 3);
    }

    public PacketResealLetter(String forgeName, int qteRounds) {
        this.forgeName = forgeName == null ? "" : forgeName;
        this.qteRounds = Math.max(0, Math.min(3, qteRounds));
    }

    public static void encode(PacketResealLetter p, FriendlyByteBuf buf) {
        buf.writeUtf(p.forgeName, 32);
        buf.writeByte(p.qteRounds);
    }

    public static PacketResealLetter decode(FriendlyByteBuf buf) {
        return new PacketResealLetter(buf.readUtf(32), buf.readByte());
    }

    public static void handle(PacketResealLetter p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;
            ItemStack stack = sender.getMainHandItem();
            CompoundTag tag = stack.getTag();
            if (tag == null || !tag.contains("crow_sender")) return;

            String origSender  = tag.getString("crow_sender");
            String subject     = tag.getString("crow_subject");
            String body        = tag.getString("crow_body");

            // Résolution du nouveau sender via ForgerySystem (cooldown + score QTE)
            String displaySender = ForgerySystem.resolveDisplaySender(sender, p.forgeName, p.qteRounds);

            if (!displaySender.equalsIgnoreCase(origSender)) {
                // Réécriture de l'item en main
                ItemStack resealed = LetterRenderer.makeLetterItem(displaySender, subject, body);
                sender.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, resealed);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
