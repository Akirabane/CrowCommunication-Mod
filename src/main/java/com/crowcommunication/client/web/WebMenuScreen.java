package com.crowcommunication.client.web;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Écran Minecraft hébergeant le browser MCEF pour la composition d'une lettre.
 *
 * <p>Le rendu clippe le contenu Chromium via scissor plutôt que via la transparence CEF,
 * non fiable sur cette version de MCEF. La zone de clip reproduit exactement le layout CSS :
 * {@code width: min(680px, 92vw); margin: 5vh auto 0}.</p>
 */
@OnlyIn(Dist.CLIENT)
public class WebMenuScreen extends Screen {

    private MCEFBrowser browser;
    private final String url;
    private final WebBridge bridge;

    public WebMenuScreen(String url, WebBridge bridge) {
        super(Component.literal("Crow Communication"));
        this.url = url;
        this.bridge = bridge;
    }

    @Override
    protected void init() {
        super.init();
        if (!MCEFBootstrap.isReady()) return;
        if (browser == null) {
            browser = MCEF.createBrowser(url, true);
            bridge.attach(browser);
        }
        resizeBrowser();
        // setFocus indispensable : sans ça les champs HTML ne reçoivent aucune frappe clavier
        try { browser.setFocus(true); } catch (Throwable ignored) {}
    }

    @Override
    public void resize(Minecraft mc, int w, int h) {
        super.resize(mc, w, h);
        resizeBrowser();
    }

    private void resizeBrowser() {
        if (browser == null) return;
        double s = minecraft.getWindow().getGuiScale();
        int pw = (int)(this.width  * s);
        int ph = (int)(this.height * s);
        if (pw > 0 && ph > 0) browser.resize(pw, ph);
    }

    @Override
    public void onClose() {
        bridge.fireClose("escape");
        super.onClose(); // → setScreen(null) → removed() → browser.close(), puis grabMouse()
    }

    @Override
    public void removed() {
        if (browser != null) {
            bridge.detach();
            browser.close();
            browser = null;
        }
        super.removed();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        if (browser == null) {
            String msg = MCEFBootstrap.isReady() ? "Chargement..."
                : "Initialisation de MCEF (premier démarrage : téléchargement de Chromium)...";
            g.drawCenteredString(this.font, msg, this.width / 2, this.height / 2, 0xFFFFFFFF);
            return;
        }
        double s = minecraft.getWindow().getGuiScale();
        // Dimensions en pixels physiques (référentiel CEF)
        int bw = (int)(this.width  * s);
        int bh = (int)(this.height * s);
        // Reproduit le layout CSS : width: min(680px, 92vw) ; margin: 3vh auto 2vh ; max-height: 92vh
        int lw = Math.min(680, (int)(bw * 0.92));
        int ly = (int)(bh * 0.03);
        int lh = bh - ly - (int)(bh * 0.02);
        int lx = (bw - lw) / 2;
        // Repasser en unités GUI pour GuiGraphics.enableScissor
        int gx = (int)(lx / s);
        int gy = (int)(ly / s);
        int gw = (int)(lw / s);
        int gh = (int)(lh / s);
        g.enableScissor(gx, gy, gx + gw, gy + gh);
        drawBrowserTexture(browser.getRenderer().getTextureID());
        g.disableScissor();
    }

    private void drawBrowserTexture(int texId) {
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texId);

        Tesselator t = Tesselator.getInstance();
        BufferBuilder b = t.getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        // UVs V inversés (0↔1) : la texture CEF est rendue à l'envers verticalement
        b.vertex(0,           this.height, 0).uv(0, 1).color(255,255,255,255).endVertex();
        b.vertex(this.width,  this.height, 0).uv(1, 1).color(255,255,255,255).endVertex();
        b.vertex(this.width,  0,           0).uv(1, 0).color(255,255,255,255).endVertex();
        b.vertex(0,           0,           0).uv(0, 0).color(255,255,255,255).endVertex();
        t.end();

        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.enableDepthTest();
    }

    /** Convertit une coordonnée X GUI en pixels physiques pour CEF. */
    private int px(double mx) { return (int)(mx * this.minecraft.getWindow().getGuiScale()); }

    /** Convertit une coordonnée Y GUI en pixels physiques pour CEF. */
    private int py(double my) { return (int)(my * this.minecraft.getWindow().getGuiScale()); }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (browser != null) {
            try { browser.setFocus(true); } catch (Throwable ignored) {}
            browser.sendMouseMove(px(mx), py(my));
            browser.sendMousePress(px(mx), py(my), btn);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (browser != null) {
            browser.sendMouseMove(px(mx), py(my));
            browser.sendMouseRelease(px(mx), py(my), btn);
        }
        return true;
    }

    @Override
    public void mouseMoved(double mx, double my) {
        if (browser != null) browser.sendMouseMove(px(mx), py(my));
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (browser != null) browser.sendMouseMove(px(mx), py(my));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (browser != null) browser.sendMouseWheel(px(mx), py(my), delta * 100.0, 0);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256 /* ESCAPE */) { onClose(); return true; }
        if (browser != null) browser.sendKeyPress(key, scan, mods);
        return true;
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (browser != null) browser.sendKeyRelease(key, scan, mods);
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (browser != null) browser.sendKeyTyped(c, mods);
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
