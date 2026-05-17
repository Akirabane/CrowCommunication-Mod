package com.professionsmod.client.gui;

import com.professionsmod.capability.PlayerDataCapability;
import com.professionsmod.client.render.UIRenderer;
import com.professionsmod.data.PlayerProfessionData;
import com.professionsmod.event.ProfessionRegistry;
import com.professionsmod.network.NetworkHandler;
import com.professionsmod.network.PacketSetProfession;
import com.professionsmod.profession.Profession;
import com.professionsmod.profession.ProfessionType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public class ProfessionScreen extends Screen {

    private static final int PANEL_W = 520;
    private static final int PANEL_H = 360;
    private int panelX, panelY;

    // Tabs: 0 = aperçu, 1 = choisir principal, 2 = choisir secondaire
    private int activeTab = 0;

    // Hover/selection state
    private ProfessionType hoveredProfession = null;

    public ProfessionScreen() {
        super(Component.literal("Professions"));
    }

    @Override
    protected void init() {
        super.init();
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Fond
        UIRenderer.drawBackground(g, width, height);

        // Panneau principal
        UIRenderer.fillRect(g, panelX, panelY, PANEL_W, PANEL_H, UIRenderer.COLOR_BG_PANEL);
        UIRenderer.drawBorder(g, panelX, panelY, PANEL_W, PANEL_H, UIRenderer.COLOR_BORDER_GOLD);
        UIRenderer.drawBorder(g, panelX + 1, panelY + 1, PANEL_W - 2, PANEL_H - 2, UIRenderer.COLOR_BORDER);

        // Titre
        String title = "✦  PROFESSIONS  ✦";
        int titleW = font.width(title);
        g.drawString(font, title, panelX + (PANEL_W - titleW) / 2, panelY + 10, UIRenderer.COLOR_ACCENT_LIGHT, false);

        // Séparateur sous le titre
        UIRenderer.fillRect(g, panelX + 10, panelY + 22, PANEL_W - 20, 1, UIRenderer.COLOR_BORDER_GOLD);

        // Onglets
        drawTabs(g, mouseX, mouseY);

        // Contenu selon l'onglet actif
        PlayerProfessionData data = getPlayerData();
        if (data == null) return;

        switch (activeTab) {
            case 0 -> drawOverviewTab(g, data, mouseX, mouseY);
            case 1 -> drawChooseProfessionTab(g, data, mouseX, mouseY, true);
            case 2 -> drawChooseProfessionTab(g, data, mouseX, mouseY, false);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawTabs(GuiGraphics g, int mouseX, int mouseY) {
        String[] tabs = {"Aperçu", "Métier Principal", "Métier Secondaire"};
        int tabX = panelX + 10;
        int tabY = panelY + 28;
        int tabH = 18;

        for (int i = 0; i < tabs.length; i++) {
            int tw = font.width(tabs[i]) + 16;
            boolean active = (activeTab == i);
            boolean hovered = mouseX >= tabX && mouseX < tabX + tw && mouseY >= tabY && mouseY < tabY + tabH;

            int bg = active ? UIRenderer.COLOR_ACCENT_GOLD : (hovered ? UIRenderer.COLOR_BG_CARD : UIRenderer.COLOR_BG_DARK);
            int textColor = active ? 0xFF111111 : (hovered ? UIRenderer.COLOR_TEXT_PRIMARY : UIRenderer.COLOR_TEXT_SECONDARY);

            UIRenderer.fillRect(g, tabX, tabY, tw, tabH, bg);
            UIRenderer.drawBorder(g, tabX, tabY, tw, tabH, active ? UIRenderer.COLOR_ACCENT_LIGHT : UIRenderer.COLOR_BORDER);
            g.drawString(font, tabs[i], tabX + 8, tabY + (tabH - font.lineHeight) / 2, textColor, false);

            tabX += tw + 4;
        }
    }

    private void drawOverviewTab(GuiGraphics g, PlayerProfessionData data, int mouseX, int mouseY) {
        int contentY = panelY + 52;
        int contentX = panelX + 15;
        int cardW = (PANEL_W - 40) / 2;
        int cardH = 130;

        // Carte métier principal
        drawProfessionCard(g, data, contentX, contentY, cardW, cardH, data.getMainProfession(), true, mouseX, mouseY);
        // Carte métier secondaire
        drawProfessionCard(g, data, contentX + cardW + 10, contentY, cardW, cardH, data.getSecondaryProfession(), false, mouseX, mouseY);

        // Conseils / stats globaux
        int statsY = contentY + cardH + 15;
        UIRenderer.drawSectionHeader(g, contentX, statsY, PANEL_W - 30, "[ Progression Globale ]", font);
        statsY += font.lineHeight + 8;

        int col1X = contentX;
        int col2X = contentX + (PANEL_W - 30) / 2;
        int statH = font.lineHeight + 6;

        for (ProfessionType type : ProfessionType.values()) {
            if (!data.isActiveProfession(type)) continue;
            int level = data.getLevel(type);
            int currentXP = data.getXP(type);
            int maxXP = level < ProfessionType.MAX_LEVEL ? ProfessionType.XP_PER_LEVEL[level - 1] : 1;
            float progress = level >= ProfessionType.MAX_LEVEL ? 1f : (float) currentXP / maxXP;

            int drawX = (type.isMain()) ? col1X : col2X;
            int drawY = statsY;

            g.drawString(font, type.getDisplayName(), drawX, drawY, UIRenderer.COLOR_TEXT_PRIMARY, false);
            g.drawString(font, "Niv. " + level, drawX + 80, drawY, UIRenderer.COLOR_TEXT_GOLD, false);
            UIRenderer.drawProgressBar(g, drawX, drawY + font.lineHeight + 2, 140, 5,
                progress, level >= ProfessionType.MAX_LEVEL ? UIRenderer.COLOR_XP_BAR_MAX : UIRenderer.COLOR_XP_BAR_FG);

            if (data.hasSpecialty(type)) {
                g.drawString(font, "✦ " + type.getSpecialtyName(), drawX + 150, drawY, 0xFFDD44DD, false);
            }
        }

        // Hint touches
        String hint = "[ Appuyez sur ÉCHAP pour fermer · P pour rouvrir ]";
        int hintW = font.width(hint);
        g.drawString(font, hint, panelX + (PANEL_W - hintW) / 2, panelY + PANEL_H - 14, UIRenderer.COLOR_TEXT_SECONDARY, false);
    }

    private void drawProfessionCard(GuiGraphics g, PlayerProfessionData data, int x, int y, int w, int h,
                                     ProfessionType type, boolean isMain, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        UIRenderer.fillRect(g, x, y, w, h, hovered ? 0xFF222233 : UIRenderer.COLOR_BG_CARD);
        UIRenderer.drawBorder(g, x, y, w, h, UIRenderer.COLOR_BORDER);
        if (hovered) UIRenderer.drawBorder(g, x + 1, y + 1, w - 2, h - 2, UIRenderer.COLOR_BORDER_GOLD);

        String roleLabel = isMain ? "PRINCIPAL" : "SECONDAIRE";
        int badgeBg = isMain ? UIRenderer.COLOR_MAIN_BADGE : UIRenderer.COLOR_SEC_BADGE;
        UIRenderer.drawBadge(g, font, x + 6, y + 6, roleLabel, badgeBg);

        if (type == null) {
            String empty = "Aucun métier sélectionné";
            int tw = font.width(empty);
            g.drawString(font, empty, x + (w - tw) / 2, y + h / 2 - font.lineHeight / 2, UIRenderer.COLOR_TEXT_SECONDARY, false);
            String hint2 = "→ Onglet \"" + (isMain ? "Métier Principal" : "Métier Secondaire") + "\"";
            int tw2 = font.width(hint2);
            g.drawString(font, hint2, x + (w - tw2) / 2, y + h / 2 + font.lineHeight / 2 + 2, UIRenderer.COLOR_TEXT_SECONDARY, false);
            return;
        }

        // Nom du métier
        int nameY = y + 28;
        int nameW = font.width(type.getDisplayName());
        g.drawString(font, type.getDisplayName(), x + (w - nameW) / 2, nameY, UIRenderer.COLOR_ACCENT_LIGHT, false);

        // Niveau
        int level = data.getLevel(type);
        String levelStr = "Niveau " + level + " / " + ProfessionType.MAX_LEVEL;
        int lw = font.width(levelStr);
        g.drawString(font, levelStr, x + (w - lw) / 2, nameY + font.lineHeight + 4, UIRenderer.COLOR_TEXT_GOLD, false);

        // Barre XP
        int xpBarY = nameY + font.lineHeight * 2 + 8;
        int currentXP = data.getXP(type);
        int maxXP = level < ProfessionType.MAX_LEVEL ? ProfessionType.XP_PER_LEVEL[level - 1] : 1;
        float progress = level >= ProfessionType.MAX_LEVEL ? 1f : (float) currentXP / maxXP;
        int barColor = level >= ProfessionType.MAX_LEVEL ? UIRenderer.COLOR_XP_BAR_MAX : UIRenderer.COLOR_XP_BAR_FG;
        UIRenderer.drawProgressBar(g, x + 10, xpBarY, w - 20, 8, progress, barColor);

        String xpStr = level >= ProfessionType.MAX_LEVEL ? "MAX" : currentXP + " / " + maxXP + " XP";
        int xsw = font.width(xpStr);
        g.drawString(font, xpStr, x + (w - xsw) / 2, xpBarY + 10, UIRenderer.COLOR_TEXT_SECONDARY, false);

        // Points de compétence disponibles
        int pts = data.getSkillPoints(type);
        if (pts > 0) {
            String ptsStr = "✦ " + pts + " point(s) à distribuer";
            int psw = font.width(ptsStr);
            g.drawString(font, ptsStr, x + (w - psw) / 2, xpBarY + 24, 0xFFFFAA00, false);
        }

        // Spécialité
        if (data.hasSpecialty(type)) {
            String spec = "✦ " + type.getSpecialtyName();
            int sw = font.width(spec);
            g.drawString(font, spec, x + (w - sw) / 2, xpBarY + 38, 0xFFDD44DD, false);
        }

        // Bouton arbre de compétences
        int btnY = y + h - 24;
        boolean btnHovered = mouseX >= x + 10 && mouseX < x + w - 10 && mouseY >= btnY && mouseY < btnY + 16;
        UIRenderer.fillRect(g, x + 10, btnY, w - 20, 16, btnHovered ? UIRenderer.COLOR_ACCENT_GOLD : 0xFF2A2A1A);
        UIRenderer.drawBorder(g, x + 10, btnY, w - 20, 16, UIRenderer.COLOR_BORDER_GOLD);
        String btnTxt = "Arbre de Compétences";
        int btw = font.width(btnTxt);
        g.drawString(font, btnTxt, x + (w - btw) / 2, btnY + (16 - font.lineHeight) / 2,
            btnHovered ? 0xFF111111 : UIRenderer.COLOR_TEXT_GOLD, false);
    }

    private void drawChooseProfessionTab(GuiGraphics g, PlayerProfessionData data, int mouseX, int mouseY, boolean mainOnly) {
        int contentY = panelY + 52;
        int contentX = panelX + 15;

        String sectionTitle = mainOnly ? "Choisir un Métier Principal" : "Choisir un Métier Secondaire";
        UIRenderer.drawSectionHeader(g, contentX, contentY, PANEL_W - 30, sectionTitle, font);
        contentY += font.lineHeight + 12;

        int cardW = 140;
        int cardH = 95;
        int cols = 5;
        int gap = 6;
        int totalW = cols * cardW + (cols - 1) * gap;
        int startX = panelX + (PANEL_W - totalW) / 2;

        List<ProfessionType> professions = new ArrayList<>();
        for (ProfessionType type : ProfessionType.values()) {
            if (mainOnly && type.isMain()) professions.add(type);
            if (!mainOnly && type.isSecondary()) professions.add(type);
        }

        hoveredProfession = null;
        for (int i = 0; i < professions.size(); i++) {
            ProfessionType type = professions.get(i);
            int cx = startX + i * (cardW + gap);
            int cy = contentY;

            boolean isActive = mainOnly ? (type == data.getMainProfession()) : (type == data.getSecondaryProfession());
            boolean hovered = mouseX >= cx && mouseX < cx + cardW && mouseY >= cy && mouseY < cy + cardH;
            if (hovered) hoveredProfession = type;

            int bg = isActive ? 0xFF1A3A1A : (hovered ? UIRenderer.COLOR_BG_CARD : UIRenderer.COLOR_BG_DARK);
            UIRenderer.fillRect(g, cx, cy, cardW, cardH, bg);
            UIRenderer.drawBorder(g, cx, cy, cardW, cardH, isActive ? 0xFF2A8A2A : UIRenderer.COLOR_BORDER);
            if (hovered && !isActive) UIRenderer.drawBorder(g, cx + 1, cy + 1, cardW - 2, cardH - 2, UIRenderer.COLOR_BORDER_GOLD);

            // Icône (cercle coloré)
            int iconColor = isActive ? 0xFF22CC22 : (mainOnly ? UIRenderer.COLOR_MAIN_BADGE : UIRenderer.COLOR_SEC_BADGE);
            UIRenderer.fillCircle(g, cx + cardW / 2, cy + 22, 14, iconColor);
            UIRenderer.drawCircle(g, cx + cardW / 2, cy + 22, 14, UIRenderer.COLOR_ACCENT_GOLD);

            // Initiale du métier
            String initial = type.getDisplayName().substring(0, 1);
            g.drawString(font, initial, cx + cardW / 2 - font.width(initial) / 2, cy + 22 - font.lineHeight / 2, UIRenderer.COLOR_TEXT_PRIMARY, false);

            // Nom
            String name = type.getDisplayName();
            int nw = font.width(name);
            g.drawString(font, name, cx + (cardW - nw) / 2, cy + 40, UIRenderer.COLOR_TEXT_PRIMARY, false);

            // Niveau actuel
            int level = data.getLevel(type);
            String lvl = "Niv. " + level;
            int lw = font.width(lvl);
            g.drawString(font, lvl, cx + (cardW - lw) / 2, cy + 40 + font.lineHeight + 2, UIRenderer.COLOR_TEXT_GOLD, false);

            // XP bar mini
            int xp = data.getXP(type);
            int maxXP = level < ProfessionType.MAX_LEVEL ? ProfessionType.XP_PER_LEVEL[level - 1] : 1;
            float prog = level >= ProfessionType.MAX_LEVEL ? 1f : (float) xp / maxXP;
            UIRenderer.drawProgressBar(g, cx + 8, cy + 40 + font.lineHeight * 2 + 4, cardW - 16, 5, prog,
                level >= ProfessionType.MAX_LEVEL ? UIRenderer.COLOR_XP_BAR_MAX : UIRenderer.COLOR_XP_BAR_FG);

            // Sélectionné
            if (isActive) {
                String sel = "✓ Actif";
                int sw = font.width(sel);
                g.drawString(font, sel, cx + (cardW - sw) / 2, cy + cardH - font.lineHeight - 4, 0xFF22CC22, false);
            }
        }

        // Description au survol
        if (hoveredProfession != null) {
            int descY = contentY + cardH + 16;
            UIRenderer.fillRect(g, contentX, descY, PANEL_W - 30, 50, UIRenderer.COLOR_BG_CARD);
            UIRenderer.drawBorder(g, contentX, descY, PANEL_W - 30, 50, UIRenderer.COLOR_BORDER_GOLD);

            g.drawString(font, hoveredProfession.getDisplayName(), contentX + 10, descY + 6, UIRenderer.COLOR_ACCENT_LIGHT, false);

            if (hoveredProfession.hasSpecialty()) {
                String spec = "Spécialité Niveau 10: §d" + hoveredProfession.getSpecialtyName();
                g.drawString(font, "Spécialité Niveau 10: ", contentX + 10, descY + 20, UIRenderer.COLOR_TEXT_SECONDARY, false);
                g.drawString(font, hoveredProfession.getSpecialtyName(), contentX + 10 + font.width("Spécialité Niveau 10: "), descY + 20, 0xFFDD44DD, false);
                g.drawString(font, hoveredProfession.getSpecialtyDescription(), contentX + 10, descY + 34, UIRenderer.COLOR_TEXT_SECONDARY, false);
            } else {
                g.drawString(font, "Pas de spécialité — arbre de compétences uniquement", contentX + 10, descY + 20, UIRenderer.COLOR_TEXT_SECONDARY, false);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX, my = (int) mouseY;

        // Clic sur les onglets
        String[] tabs = {"Aperçu", "Métier Principal", "Métier Secondaire"};
        int tabX = panelX + 10;
        int tabY = panelY + 28;
        int tabH = 18;
        for (int i = 0; i < tabs.length; i++) {
            int tw = font.width(tabs[i]) + 16;
            if (mx >= tabX && mx < tabX + tw && my >= tabY && my < tabY + tabH) {
                activeTab = i;
                return true;
            }
            tabX += tw + 4;
        }

        PlayerProfessionData data = getPlayerData();
        if (data == null) return super.mouseClicked(mouseX, mouseY, button);

        // Clic sur les cartes de compétences (onglet 0)
        if (activeTab == 0) {
            int contentY = panelY + 52;
            int contentX = panelX + 15;
            int cardW = (PANEL_W - 40) / 2;
            int cardH = 130;
            int btnH = 16;

            // Bouton arbre métier principal
            if (data.getMainProfession() != null) {
                int btnY = contentY + cardH - 24;
                if (mx >= contentX + 10 && mx < contentX + cardW - 10 && my >= btnY && my < btnY + btnH) {
                    Minecraft.getInstance().setScreen(new SkillTreeScreen(data.getMainProfession(), this));
                    return true;
                }
            }
            // Bouton arbre métier secondaire
            if (data.getSecondaryProfession() != null) {
                int cx2 = contentX + cardW + 10;
                int btnY = contentY + cardH - 24;
                if (mx >= cx2 + 10 && mx < cx2 + cardW - 10 && my >= btnY && my < btnY + btnH) {
                    Minecraft.getInstance().setScreen(new SkillTreeScreen(data.getSecondaryProfession(), this));
                    return true;
                }
            }
        }

        // Sélection de métier
        if ((activeTab == 1 || activeTab == 2) && hoveredProfession != null) {
            boolean isMain = activeTab == 1;
            NetworkHandler.sendToServer(new PacketSetProfession(hoveredProfession, isMain));
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private PlayerProfessionData getPlayerData() {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        return PlayerDataCapability.getOptional(player).orElse(null);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
