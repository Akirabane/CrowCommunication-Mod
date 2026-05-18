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
 * Pont JS ↔ Java pour la composition du courrier.
 * Le JS publie via console.log :
 *   "PMOD::action|arg1|arg2"
 *   "PMOD::sendMsg::target::subject::body" (préserve les '|' dans le corps)
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

    public void attach(MCEFBrowser b) {
        this.browser = b;
        com.cinemamod.mcef.MCEF.getClient().addDisplayHandler(displayHandler);
    }

    public void detach() { this.browser = null; }

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
