package com.crowcommunication.client.web;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Ouvre l'écran de lecture de lettre (MCEF), avec possibilité de rejouer le QTE pour changer le sceau.
 */
@OnlyIn(Dist.CLIENT)
public final class LetterViewOpener {

    public static void open(String sender, String subject, String body) {
        Minecraft mc = Minecraft.getInstance();
        if (!MCEFBootstrap.isReady()) return;

        String url = MCEFBootstrap.urlFor("letter_view.html") + "?"
            + "from="    + enc(sender)
            + "&subject=" + enc(subject)
            + "&body="    + enc(body);

        WebBridge bridge = new WebBridge(reason -> {
            if (reason.startsWith("reseal|")) {
                // Format : reseal|<newSender>|<qteRounds>
                String rest = reason.substring("reseal|".length());
                String[] parts = rest.split("\\|", 2);
                String newSender = parts[0].trim();
                int qteRounds = 0;
                if (parts.length > 1) {
                    try { qteRounds = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException ignored) {}
                }
                if (!newSender.isEmpty()) {
                    com.crowcommunication.network.NetworkHandler.sendToServer(
                        new com.crowcommunication.network.PacketResealLetter(newSender, qteRounds));
                }
            }
            mc.setScreen(null);
        });
        mc.setScreen(new WebMenuScreen(url, bridge));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
