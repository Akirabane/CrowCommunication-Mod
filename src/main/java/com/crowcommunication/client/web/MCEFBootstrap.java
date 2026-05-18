package com.crowcommunication.client.web;

import com.cinemamod.mcef.MCEF;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.InputStream;
import java.nio.file.*;

@OnlyIn(Dist.CLIENT)
public final class MCEFBootstrap {

    private static volatile boolean ready = false;
    private static Path webRoot;

    public static void init() {
        try {
            webRoot = Files.createTempDirectory("corbeau-web-");
            webRoot.toFile().deleteOnExit();
            for (String name : new String[]{
                    "compose.html", "compose.css", "compose.js"
            }) {
                extractAsset("assets/crowcommunication/web/" + name, webRoot.resolve(name));
            }
        } catch (Exception e) {
            System.err.println("[Corbeau] Impossible d'extraire les assets web: " + e);
        }

        MCEF.scheduleForInit(success -> {
            ready = success;
            System.out.println("[Corbeau] MCEF init: " + (success ? "OK" : "ÉCHEC"));
            // Notifier le serveur dans tous les cas — si MCEF a échoué, l'erreur sera gérée
            // à l'ouverture de l'écran de composition, pas au spawn du corbeau.
            notifyServerIfConnected();
        });
    }

    private static void extractAsset(String resourcePath, Path dest) {
        try (InputStream in = MCEFBootstrap.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                System.err.println("[Corbeau] Asset manquant: " + resourcePath);
                return;
            }
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("[Corbeau] Erreur extraction " + resourcePath + ": " + e);
        }
    }

    public static boolean isReady() { return ready && MCEF.isInitialized(); }

    /** Envoie au serveur l'info que MCEF est prêt (si on est en partie). */
    public static void notifyServerIfConnected() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.getConnection() != null) {
                com.crowcommunication.network.NetworkHandler.sendToServer(new com.crowcommunication.network.PacketMCEFReady());
            }
        } catch (Throwable ignored) {}
    }

    public static String urlFor(String fileName) {
        if (webRoot == null) return "about:blank";
        return webRoot.resolve(fileName).toUri().toString();
    }
}
