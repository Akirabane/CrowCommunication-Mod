package com.crowcommunication.client.web;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@OnlyIn(Dist.CLIENT)
public final class ComposeScreenOpener {
    public static void open(String recipients) {
        Minecraft mc = Minecraft.getInstance();
        if (!MCEFBootstrap.isReady()) {
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c§oImpossible d'ouvrir l'écran de rédaction — MCEF n'est pas disponible sur ce client."));
            // Annuler côté serveur pour que le corbeau reparte immédiatement
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
