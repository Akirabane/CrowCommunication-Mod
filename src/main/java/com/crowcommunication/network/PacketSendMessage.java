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

public class PacketSendMessage {

    private final String target;
    private final String subject;
    private final String body;

    public PacketSendMessage(String target, String subject, String body) {
        this.target = target == null ? "" : target;
        this.subject = subject == null ? "" : subject;
        this.body = body == null ? "" : body;
    }

    public static void encode(PacketSendMessage p, FriendlyByteBuf buf) {
        buf.writeUtf(p.target, 32);
        buf.writeUtf(p.subject, 80);
        buf.writeUtf(p.body, 2000);
    }

    public static PacketSendMessage decode(FriendlyByteBuf buf) {
        return new PacketSendMessage(buf.readUtf(32), buf.readUtf(80), buf.readUtf(2000));
    }

    public static void handle(PacketSendMessage p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;
            MinecraftServer server = sender.getServer();
            if (server == null) return;

            // Soit on a une liste de destinataires (groupe), soit un seul depuis le formulaire
            List<String> targets = new ArrayList<>(CorbeauManager.takePendingGroup(sender));
            if (targets.isEmpty()) targets.add(p.target);

            List<ServerPlayer> validRecipients = new ArrayList<>();
            List<String> rejected = new ArrayList<>();
            List<String> lost = new ArrayList<>();
            for (String name : targets) {
                ServerPlayer r = CorbeauManager.findPlayer(server, sender, name);
                if (r == null) { rejected.add(name); continue; }
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

            for (ServerPlayer recipient : validRecipients) {
                long delayTicks = CorbeauManager.computeDeliveryDelayTicks(sender, recipient);
                long delaySec = delayTicks / 20L;
                CorbeauManager.scheduleDelivery(server, recipient,
                    sender.getGameProfile().getName(), p.subject, p.body, delayTicks);
                String prettyTime = delaySec < 60
                    ? delaySec + " s"
                    : (delaySec / 60) + " min " + (delaySec % 60) + " s";
                String to = (recipient == sender) ? "toi-même" : recipient.getGameProfile().getName();
                sender.sendSystemMessage(Component.literal(
                    "§8§oUn corbeau s'envole vers §f" + to + "§8 — livraison estimée dans §f" + prettyTime + "§8."));
            }

            CorbeauManager.markSent(sender);
            CorbeauManager.onMessageSent(sender, validRecipients.size());
        });
        ctx.get().setPacketHandled(true);
    }
}
