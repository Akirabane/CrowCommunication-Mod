package com.crowcommunication.client.web;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Ouvre l'écran de composition MCEF côté client.
 *
 * <p>Si MCEF n'est pas disponible, annule la session et renvoie un {@link PacketCancelLetter}
 * pour que le corbeau reparte immédiatement côté serveur.</p>
 */
@SuppressWarnings("null")
@OnlyIn(Dist.CLIENT)
public final class ComposeScreenOpener {

    /**
     * Ouvre l'interface de rédaction avec les destinataires pré-remplis.
     *
     * @param recipients liste de destinataires séparée par des virgules, ou chaîne vide
     */
    public static void open(String recipients) {
        Minecraft mc = Minecraft.getInstance();
        if (!MCEFBootstrap.isReady()) {
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c§oL'encrier du messager n'est pas prêt — impossible d'écrire pour l'instant."));
            com.crowcommunication.network.NetworkHandler.sendToServer(
                new com.crowcommunication.network.PacketCancelLetter());
            return;
        }
        WebBridge bridge = new WebBridge(reason -> {
            if (!"sent".equals(reason)) {
                com.crowcommunication.network.NetworkHandler.sendToServer(
                    new com.crowcommunication.network.PacketCancelLetter());
            }
            mc.setScreen(null);
        });
        String url = MCEFBootstrap.urlFor("compose.html");
        if (recipients != null && !recipients.isEmpty()) {
            url += "?to=" + URLEncoder.encode(recipients, StandardCharsets.UTF_8);
        }
        mc.setScreen(new WebMenuScreen(url, bridge));
    }
}
