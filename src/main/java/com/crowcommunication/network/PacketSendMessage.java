package com.crowcommunication.network;

import com.crowcommunication.corbeau.CorbeauManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Packet client → serveur transportant le contenu d'une lettre rédigée.
 *
 * <p>Le champ {@code target} est ignoré quand le serveur détecte un groupe en attente
 * via {@link com.crowcommunication.corbeau.CorbeauManager#takePendingGroup}.</p>
 */
public class PacketSendMessage {

    private final String target;
    private final String subject;
    private final String body;
    /** Pseudo à usurper si le QTE a été réussi côté client. Vide si pas d'usurpation. */
    private final String forgeName;

    public PacketSendMessage(String target, String subject, String body) {
        this(target, subject, body, "");
    }

    public PacketSendMessage(String target, String subject, String body, String forgeName) {
        this.target = target == null ? "" : target;
        this.subject = subject == null ? "" : subject;
        this.body = body == null ? "" : body;
        this.forgeName = forgeName == null ? "" : forgeName;
    }

    public static void encode(PacketSendMessage p, FriendlyByteBuf buf) {
        buf.writeUtf(p.target, 32);
        buf.writeUtf(p.subject, 80);
        buf.writeUtf(p.body, 2000);
        buf.writeUtf(p.forgeName, 32);
    }

    public static PacketSendMessage decode(FriendlyByteBuf buf) {
        return new PacketSendMessage(buf.readUtf(32), buf.readUtf(80), buf.readUtf(2000), buf.readUtf(32));
    }

    public static void handle(PacketSendMessage p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;
            MinecraftServer server = sender.getServer();
            if (server == null) return;

            List<String> targets = new ArrayList<>(CorbeauManager.takePendingGroup(sender));
            if (targets.isEmpty()) targets.add(p.target);

            List<ServerPlayer> validRecipients = new ArrayList<>();
            List<String> rejected = new ArrayList<>();
            List<String> lost = new ArrayList<>();
            for (String name : targets) {
                ServerPlayer r = CorbeauManager.findPlayer(server, sender, name);
                if (r == null) { rejected.add(name); continue; }
                if (r == sender) continue; // garde absolue contre l'auto-envoi
                if (CorbeauManager.senderDistance(sender, r) > CorbeauManager.MAX_DELIVERY_DISTANCE) {
                    lost.add(r.getGameProfile().getName()); continue;
                }
                validRecipients.add(r);
            }

            for (String n : rejected) {
                sender.sendSystemMessage(Component.literal(
                    "§c§oLe corbeau ne connaît personne sous le nom §f" + n + "§c."));
            }
            for (String n : lost) {
                sender.sendSystemMessage(Component.literal(
                    "§c§oTrop loin — §f" + n + "§c est hors de portée des corbeaux. Le messager s'est perdu."));
            }
            if (validRecipients.isEmpty()) {
                CorbeauManager.onLetterCancelled(sender);
                return;
            }

            // Tentative d'usurpation : résolue UNE fois ici, puis appliquée à toutes les livraisons.
            String displaySender = CorbeauManager.resolveDisplaySender(sender, p.forgeName);
            String realName = sender.getGameProfile().getName();
            boolean forgeAttempted = p.forgeName != null && !p.forgeName.isBlank()
                && !p.forgeName.trim().equalsIgnoreCase(realName);
            boolean forgeSucceeded = forgeAttempted && !displaySender.equalsIgnoreCase(realName);

            List<java.util.UUID> deliveryIds = new ArrayList<>();
            List<String> recipientNames = new ArrayList<>();
            List<Long> delays = new ArrayList<>();
            for (ServerPlayer recipient : validRecipients) {
                long delayTicks = CorbeauManager.computeDeliveryDelayTicks(sender, recipient);
                long delaySec = delayTicks / 20L;
                java.util.UUID msgId = CorbeauManager.scheduleDelivery(server, recipient,
                    sender.getGameProfile().getName(), displaySender, p.subject, p.body, delayTicks);
                deliveryIds.add(msgId);
                recipientNames.add(recipient.getGameProfile().getName());
                delays.add(delayTicks);
                String prettyTime = delaySec < 60
                    ? delaySec + " s"
                    : (delaySec / 60) + " min " + (delaySec % 60) + " s";
                sender.sendSystemMessage(Component.literal(
                    "§8§oUn corbeau s'envole vers §f" + recipient.getGameProfile().getName()
                    + "§8 — livraison estimée dans §f" + prettyTime + "§8."));
                CorbeauManager.recordHistory(sender.getUUID(), new CorbeauManager.HistoryEntry(
                    System.currentTimeMillis(), true,
                    recipient.getGameProfile().getName(), p.subject,
                    forgeAttempted, forgeSucceeded));
            }

            CorbeauManager.markSent(sender);
            CorbeauManager.onMessageSent(sender, validRecipients.size());
            CorbeauManager.assignOutgoingLetter(sender, p.subject, p.body, recipientNames, deliveryIds, delays, displaySender);
        });
        ctx.get().setPacketHandled(true);
    }
}
