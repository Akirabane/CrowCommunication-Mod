package com.crowcommunication.corbeau;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.mojang.brigadier.Command;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Enregistre la commande {@code /corbeau-historique} qui affiche les 10 dernières
 * lettres envoyées ou reçues par le joueur (entrée la plus récente en haut).
 */
public class CorbeauHistoryCommand {

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("corbeau-historique").executes(ctx -> {
                if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
                    ctx.getSource().sendFailure(Component.literal("Cette commande doit être exécutée par un joueur."));
                    return 0;
                }
                List<CorbeauManager.HistoryEntry> hist = CorbeauManager.getHistory(sp.getUUID());
                if (hist.isEmpty()) {
                    sp.sendSystemMessage(Component.literal("§8§oTu n'as ni envoyé ni reçu de corbeau récemment."));
                    return 1;
                }
                sp.sendSystemMessage(Component.literal("§6───────── §e§l✉ §6§lHistorique des corbeaux §6─────────"));
                long now = System.currentTimeMillis();
                for (CorbeauManager.HistoryEntry e : hist) {
                    String ago = prettyAgo(now - e.worldTimeMillis);
                    String arrow = e.outgoing ? "§e→ §7vers" : "§b← §7de";
                    String forgeTag;
                    if (!e.outgoing) {
                        forgeTag = ""; // le destinataire ignore tout d'une éventuelle usurpation
                    } else if (e.forgeryAttempted && e.forgerySucceeded) {
                        forgeTag = " §6§o(sceau imité)";
                    } else if (e.forgeryAttempted) {
                        forgeTag = " §8§o(usurpation ratée)";
                    } else {
                        forgeTag = "";
                    }
                    sp.sendSystemMessage(Component.literal(
                        "§8• §f" + ago + " §8· " + arrow + " §f" + e.otherParty
                        + "§8 — §7§o«" + e.subject + "»" + forgeTag));
                }
                sp.sendSystemMessage(Component.literal("§6─────────────────────────────"));
                return Command.SINGLE_SUCCESS;
            })
        );
    }

    /** "il y a 3 min", "il y a 2 h", "à l'instant"... */
    private static String prettyAgo(long elapsedMs) {
        Duration d = Duration.between(Instant.ofEpochMilli(System.currentTimeMillis() - elapsedMs), Instant.now());
        long sec = d.getSeconds();
        if (sec < 10) return "à l'instant";
        if (sec < 60) return "il y a " + sec + " s";
        long min = sec / 60;
        if (min < 60) return "il y a " + min + " min";
        long hr = min / 60;
        if (hr < 24) return "il y a " + hr + " h";
        long days = hr / 24;
        return "il y a " + days + " j";
    }
}
