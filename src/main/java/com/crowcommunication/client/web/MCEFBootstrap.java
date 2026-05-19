package com.crowcommunication.client.web;

import com.cinemamod.mcef.MCEF;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.InputStream;
import java.nio.file.*;

/**
 * Initialise MCEF et extrait les assets web dans un répertoire temporaire.
 *
 * <p>Les fichiers {@code compose.html/css/js} sont copiés depuis le JAR vers un dossier
 * temporaire au démarrage, puis servis localement au browser Chromium embarqué.
 * La notification au serveur est envoyée qu'MCEF ait réussi ou non — l'échec est géré
 * à l'ouverture de l'écran de composition, pas au spawn du corbeau.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class MCEFBootstrap {

    private static volatile boolean ready = false;
    private static Path webRoot;

    /** Extrait les assets web et planifie l'initialisation de Chromium. */
    public static void init() {
        try {
            webRoot = Files.createTempDirectory("corbeau-web-");
            webRoot.toFile().deleteOnExit();
            for (String name : new String[]{"compose.html", "compose.css", "compose.js",
                                           "letter_view.html", "letter_view.js"}) {
                extractAsset("assets/crowcommunication/web/" + name, webRoot.resolve(name));
            }
        } catch (Exception e) {
            System.err.println("[Corbeau] Impossible d'extraire les assets web: " + e);
        }

        MCEF.scheduleForInit(success -> {
            ready = success;
            System.out.println("[Corbeau] MCEF init: " + (success ? "OK" : "ÉCHEC"));
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

    /**
     * @return {@code true} si Chromium est initialisé et MCEF prêt à créer un browser.
     *
     * <p>Ne s'appuie que sur le flag {@code ready} posé par le callback {@code scheduleForInit}.
     * Doubler la garde avec {@code MCEF.isInitialized()} provoquait des faux négatifs juste après
     * la fermeture d'un browser précédent (état transitoire entre deux instances).</p>
     */
    public static boolean isReady() { return ready; }

    /**
     * Envoie {@link com.crowcommunication.network.PacketMCEFReady} au serveur si une connexion est active.
     * Silencieux si appelé hors partie.
     */
    public static void notifyServerIfConnected() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.getConnection() != null) {
                com.crowcommunication.network.NetworkHandler.sendToServer(
                    new com.crowcommunication.network.PacketMCEFReady());
            }
        } catch (Throwable ignored) {}
    }

    /**
     * @param fileName nom de fichier dans le répertoire web temporaire
     * @return URL {@code file://} utilisable par le browser MCEF, ou {@code about:blank} si non initialisé
     */
    public static String urlFor(String fileName) {
        if (webRoot == null) return "about:blank";
        return webRoot.resolve(fileName).toUri().toString();
    }
}
