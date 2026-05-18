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
 */
@OnlyIn(Dist.CLIENT)
public class WebBridge {

    private final Consumer<String> onClose;
    private MCEFBrowser browser;
    private boolean closeFired = false;

    private final org.cef.handler.CefDisplayHandler displayHandler = new CefDisplayHandlerAdapter() {
        @Override
        public boolean onConsoleMessage(CefBrowser b, CefSettings.LogSeverity level, String msg, String src, int line) {
            if (msg != null && msg.startsWith("PMOD::")) {
                handleMessage(msg.substring(6));
                return true;
            }
            return false;
        }
    };

    public WebBridge(Consumer<String> onClose) {
        this.onClose = onClose;
    }

    /**
     * Attache le bridge au browser MCEF et installe le handler de console.
     *
     * @param b le browser MCEF nouvellement créé
     */
    public void attach(MCEFBrowser b) {
        this.browser = b;
        com.cinemamod.mcef.MCEF.getClient().addDisplayHandler(displayHandler);
    }

    /** Détache le bridge sans fermer le browser. */
    public void detach() { this.browser = null; }

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

    private void handleMessage(String payload) {
        Minecraft.getInstance().execute(() -> {
            if (payload.startsWith("sendMsg::")) {
                String rest = payload.substring("sendMsg::".length());
                String[] parts = rest.split("::", 3);
                if (parts.length == 3) {
                    com.crowcommunication.network.NetworkHandler.sendToServer(
                        new com.crowcommunication.network.PacketSendMessage(parts[0], parts[1], parts[2]));
                    fireClose("sent");
                }
                return;
            }
            String[] parts = payload.split("\\|");
            switch (parts[0]) {
                case "ready" -> {}
                case "close" -> fireClose("close");
                default -> {}
            }
        });
    }
}
