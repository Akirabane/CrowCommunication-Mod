package com.crowcommunication.client.web;

import com.cinemamod.mcef.MCEFBrowser;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;

import java.util.function.Consumer;

/**
 * Pont JS ↔ Java pour l'interface de composition du courrier.
 *
 * <p>Le JavaScript publie des messages via {@code console.log} selon le protocole :</p>
 * <ul>
 *   <li>{@code PMOD::action|arg1|arg2} — actions génériques (ready, close)</li>
 *   <li>{@code PMOD::sendMsg::target::subject::body} — envoi effectif (les {@code ::} préservent les {@code |} dans le corps)</li>
 * </ul>
 *
 * @author Akirabane
 */
@OnlyIn(Dist.CLIENT)
public class WebBridge {

    private Consumer<String> onClose;
    private boolean closeFired = false;
    /** Handler enregistré dans MCEF. L'API de cette version n'expose pas {@code removeDisplayHandler},
     *  donc on neutralise le handler via {@link Listener#detached} pour casser la ref vers ce {@code WebBridge}
     *  et permettre son GC. Le handler reste alloué mais devient un no-op. */
    private final Listener listener = new Listener();

    private final class Listener extends CefDisplayHandlerAdapter {
        volatile boolean detached = false;
        @Override
        public boolean onConsoleMessage(CefBrowser b, CefSettings.LogSeverity level, String msg, String src, int line) {
            if (detached || msg == null || !msg.startsWith("PMOD::")) return false;
            handleMessage(msg.substring(6));
            return true;
        }
    }

    public WebBridge(Consumer<String> onClose) {
        this.onClose = onClose;
    }

    /**
     * Attache le bridge au browser MCEF et installe le handler de console.
     *
     * @param b le browser MCEF nouvellement créé
     */
    public void attach(MCEFBrowser b) {
        com.cinemamod.mcef.MCEF.getClient().addDisplayHandler(listener);
    }

    /** Détache le bridge : neutralise le handler (no-op) et libère le callback de fermeture. */
    public void detach() {
        listener.detached = true;
        this.onClose = null;
    }

    /**
     * Déclenche le callback de fermeture une seule fois (idempotent).
     *
     * @param reason {@code "sent"}, {@code "close"} ou {@code "escape"}
     */
    public void fireClose(String reason) {
        if (closeFired) return;
        closeFired = true;
        if (onClose != null) onClose.accept(reason);
    }

    private void handleMessage(String rawPayload) {
        // Strip trailing numeric nonce "|N" added by JS to prevent CEF console-message deduplication
        int lastPipe = rawPayload.lastIndexOf('|');
        final String payload;
        if (lastPipe >= 0) {
            String tail = rawPayload.substring(lastPipe + 1);
            payload = (!tail.isEmpty() && tail.chars().allMatch(Character::isDigit))
                ? rawPayload.substring(0, lastPipe)
                : rawPayload;
        } else {
            payload = rawPayload;
        }
        Minecraft.getInstance().execute(() -> {
            if (payload.startsWith("sendMsg::")) {
                String rest = payload.substring("sendMsg::".length());
                String[] parts = rest.split("::", 5);
                if (parts.length >= 3) {
                    String forge = parts.length >= 4 ? parts[3] : "";
                    int qteRounds = 0;
                    if (parts.length >= 5) {
                        try { qteRounds = Integer.parseInt(parts[4].trim()); } catch (NumberFormatException ignored) {}
                    }
                    com.crowcommunication.network.NetworkHandler.sendToServer(
                        new com.crowcommunication.network.PacketSendMessage(parts[0], parts[1], parts[2], forge, qteRounds));
                    fireClose("sent");
                }
                return;
            }
            String[] parts = payload.split("\\|");
            switch (parts[0]) {
                case "ready" -> {}
                case "close" -> fireClose("close");
                // "reseal|newSender|qteRounds" — transmis tel quel au callback du viewer de lettre
                case "reseal" -> {
                    String target = parts.length > 1 ? parts[1] : "";
                    String qte    = parts.length > 2 ? parts[2] : "0";
                    fireClose("reseal|" + target + "|" + qte);
                }
                default -> {}
            }
        });
    }
}
