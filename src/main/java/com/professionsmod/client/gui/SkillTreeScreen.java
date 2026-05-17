package com.professionsmod.client.gui;

import com.professionsmod.capability.PlayerDataCapability;
import com.professionsmod.client.render.UIRenderer;
import com.professionsmod.data.PlayerProfessionData;
import com.professionsmod.event.ProfessionRegistry;
import com.professionsmod.network.NetworkHandler;
import com.professionsmod.network.PacketUnlockSkill;
import com.professionsmod.profession.Profession;
import com.professionsmod.profession.ProfessionType;
import com.professionsmod.skill.Skill;
import com.professionsmod.skill.SkillNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public class SkillTreeScreen extends Screen {

    private final ProfessionType professionType;
    private final Screen parent;
    private final Profession profession;

    private static final int TREE_W = 500;
    private static final int TREE_H = 300;
    private static final int NODE_RADIUS = 18;

    private SkillNode hoveredNode = null;
    private String tooltip = null;

    // Décalage pour le panneau principal
    private int panelX, panelY;
    private static final int PANEL_W = 560;
    private static final int PANEL_H = 400;

    public SkillTreeScreen(ProfessionType type, Screen parent) {
        super(Component.literal("Arbre de Compétences — " + type.getDisplayName()));
        this.professionType = type;
        this.parent = parent;
        this.profession = ProfessionRegistry.get(type);
    }

    @Override
    protected void init() {
        super.init();
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        UIRenderer.drawBackground(g, width, height);

        // Panneau
        UIRenderer.fillRect(g, panelX, panelY, PANEL_W, PANEL_H, UIRenderer.COLOR_BG_PANEL);
        UIRenderer.drawBorder(g, panelX, panelY, PANEL_W, PANEL_H, UIRenderer.COLOR_BORDER_GOLD);
        UIRenderer.drawBorder(g, panelX + 1, panelY + 1, PANEL_W - 2, PANEL_H - 2, UIRenderer.COLOR_BORDER);

        // En-tête
        String title = "✦  " + professionType.getDisplayName().toUpperCase() + "  ✦";
        int tw = font.width(title);
        g.drawString(font, title, panelX + (PANEL_W - tw) / 2, panelY + 10, UIRenderer.COLOR_ACCENT_LIGHT, false);
        UIRenderer.fillRect(g, panelX + 10, panelY + 22, PANEL_W - 20, 1, UIRenderer.COLOR_BORDER_GOLD);

        PlayerProfessionData data = getPlayerData();
        if (data == null) return;

        // Infos niveau + XP + points
        int infoY = panelY + 26;
        int level = data.getLevel(professionType);
        int xp = data.getXP(professionType);
        int maxXP = level < ProfessionType.MAX_LEVEL ? ProfessionType.XP_PER_LEVEL[level - 1] : 0;
        int pts = data.getSkillPoints(professionType);

        g.drawString(font, "Niveau: " + level, panelX + 15, infoY, UIRenderer.COLOR_TEXT_GOLD, false);
        float prog = maxXP > 0 ? (float) xp / maxXP : 1f;
        UIRenderer.drawProgressBar(g, panelX + 80, infoY + 1, 150, 7, prog,
            level >= ProfessionType.MAX_LEVEL ? UIRenderer.COLOR_XP_BAR_MAX : UIRenderer.COLOR_XP_BAR_FG);
        String xpStr = level >= ProfessionType.MAX_LEVEL ? "MAX" : xp + " / " + maxXP;
        g.drawString(font, xpStr, panelX + 238, infoY, UIRenderer.COLOR_TEXT_SECONDARY, false);

        if (pts > 0) {
            g.drawString(font, "Points disponibles: " + pts, panelX + 310, infoY, 0xFFFFAA00, false);
        }

        // Spécialité (niveau 10)
        if (professionType.hasSpecialty()) {
            int specY = panelY + PANEL_H - 50;
            UIRenderer.fillRect(g, panelX + 10, specY, PANEL_W - 20, 36, data.hasSpecialty(professionType) ? 0xFF2A1A2A : UIRenderer.COLOR_BG_DARK);
            UIRenderer.drawBorder(g, panelX + 10, specY, PANEL_W - 20, 36, data.hasSpecialty(professionType) ? 0xFF8822AA : UIRenderer.COLOR_BORDER);

            String specTitle = data.hasSpecialty(professionType)
                ? "✦ Spécialité: " + professionType.getSpecialtyName()
                : "🔒 Spécialité niveau 10: " + professionType.getSpecialtyName();
            int specColor = data.hasSpecialty(professionType) ? 0xFFDD44DD : UIRenderer.COLOR_TEXT_SECONDARY;
            g.drawString(font, specTitle, panelX + 20, specY + 6, specColor, false);
            g.drawString(font, professionType.getSpecialtyDescription(), panelX + 20, specY + 20, UIRenderer.COLOR_TEXT_SECONDARY, false);
        }

        // Zone de l'arbre de compétences
        int treeX = panelX + (PANEL_W - TREE_W) / 2;
        int treeY = panelY + 36;
        int treeH = professionType.hasSpecialty() ? PANEL_H - 100 : PANEL_H - 50;

        // Séparateurs de tiers
        drawTierSeparators(g, treeX, treeY, treeH, data);

        // Connexions entre nœuds
        Set<String> unlocked = data.getUnlockedSkills(professionType);
        List<SkillNode> nodes = profession.getSkillTree().getAllNodes();
        for (SkillNode node : nodes) {
            for (String prereqId : node.getPrerequisites()) {
                SkillNode prereq = profession.getSkillTree().getNode(prereqId);
                if (prereq == null) continue;
                int x1 = treeX + (int) (prereq.getPosX() * TREE_W);
                int y1 = treeY + (int) (prereq.getPosY() * treeH);
                int x2 = treeX + (int) (node.getPosX() * TREE_W);
                int y2 = treeY + (int) (node.getPosY() * treeH);
                boolean prereqUnlocked = unlocked.contains(prereqId);
                int lineColor = prereqUnlocked ? 0xFF335533 : 0xFF222233;
                drawThickLine(g, x1, y1, x2, y2, lineColor);
            }
        }

        // Nœuds
        hoveredNode = null;
        tooltip = null;
        for (SkillNode node : nodes) {
            int nx = treeX + (int) (node.getPosX() * TREE_W);
            int ny = treeY + (int) (node.getPosY() * treeH);
            boolean isUnlocked = unlocked.contains(node.getId());
            boolean canUnlock = profession.getSkillTree().canUnlock(node.getId(), unlocked) && pts > 0;
            boolean hovered = Math.abs(mouseX - nx) <= NODE_RADIUS && Math.abs(mouseY - ny) <= NODE_RADIUS;

            if (hovered) {
                hoveredNode = node;
                buildTooltip(node, isUnlocked, canUnlock, data);
            }

            int fillColor = isUnlocked ? UIRenderer.COLOR_SKILL_UNLOCKED
                : (canUnlock ? UIRenderer.COLOR_SKILL_AVAILABLE : UIRenderer.COLOR_SKILL_LOCKED);
            int borderColor = isUnlocked ? 0xFF44AA44
                : (canUnlock ? UIRenderer.COLOR_ACCENT_GOLD : UIRenderer.COLOR_BORDER);

            UIRenderer.drawSkillNode(g, nx, ny, NODE_RADIUS, fillColor, borderColor, hovered || (isUnlocked));

            // Icône / lettre dans le nœud
            String icon = node.getSkill().getName().substring(0, 1).toUpperCase();
            int iconColor = isUnlocked ? UIRenderer.COLOR_TEXT_PRIMARY
                : (canUnlock ? UIRenderer.COLOR_TEXT_GOLD : UIRenderer.COLOR_TEXT_SECONDARY);
            g.drawString(font, icon, nx - font.width(icon) / 2, ny - font.lineHeight / 2, iconColor, false);

            // Nom sous le nœud
            int nameW = font.width(node.getSkill().getName());
            int nameColor = isUnlocked ? UIRenderer.COLOR_TEXT_PRIMARY
                : (canUnlock ? UIRenderer.COLOR_TEXT_GOLD : UIRenderer.COLOR_TEXT_SECONDARY);
            g.drawString(font, node.getSkill().getName(), nx - nameW / 2, ny + NODE_RADIUS + 3, nameColor, false);
        }

        // Tooltip
        if (tooltip != null && hoveredNode != null) {
            drawSkillTooltip(g, mouseX, mouseY, hoveredNode, data);
        }

        // Bouton retour
        drawBackButton(g, mouseX, mouseY);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawTierSeparators(GuiGraphics g, int treeX, int treeY, int treeH, PlayerProfessionData data) {
        String[] tiers = {"Tier 1", "Tier 2", "Tier 3"};
        float[] yPositions = {0.33f, 0.66f};
        for (float yp : yPositions) {
            int lineY = treeY + (int) (yp * treeH);
            UIRenderer.fillRect(g, treeX, lineY, TREE_W, 1, 0xFF2A2A3A);
        }
        // Labels tiers
        for (int i = 0; i < tiers.length; i++) {
            float startY = i == 0 ? 0f : yPositions[i - 1];
            float endY = i < yPositions.length ? yPositions[i] : 1f;
            float midY = (startY + endY) / 2f;
            int ty = treeY + (int) (midY * treeH) - font.lineHeight / 2;
            g.drawString(font, tiers[i], treeX - 2, ty, UIRenderer.COLOR_TEXT_SECONDARY, false);
        }
    }

    private void drawThickLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        UIRenderer.drawLine(g, x1, y1, x2, y2, color);
        UIRenderer.drawLine(g, x1 + 1, y1, x2 + 1, y2, color);
        UIRenderer.drawLine(g, x1, y1 + 1, x2, y2 + 1, color);
    }

    private void buildTooltip(SkillNode node, boolean isUnlocked, boolean canUnlock, PlayerProfessionData data) {
        if (isUnlocked) tooltip = "§a✓ Débloqué";
        else if (canUnlock) tooltip = "§e→ Cliquer pour débloquer";
        else if (data.getSkillPoints(professionType) == 0) tooltip = "§cPas de points disponibles";
        else tooltip = "§cPrérequis manquants";
    }

    private void drawSkillTooltip(GuiGraphics g, int mouseX, int mouseY, SkillNode node, PlayerProfessionData data) {
        Skill skill = node.getSkill();
        int tipW = 200;
        int tipH = 60;
        int tipX = Math.min(mouseX + 12, width - tipW - 8);
        int tipY = Math.max(mouseY - tipH - 4, 8);

        UIRenderer.fillRect(g, tipX, tipY, tipW, tipH, 0xEE0D0D14);
        UIRenderer.drawBorder(g, tipX, tipY, tipW, tipH, UIRenderer.COLOR_BORDER_GOLD);

        g.drawString(font, skill.getName(), tipX + 6, tipY + 6, UIRenderer.COLOR_ACCENT_LIGHT, false);
        // Word-wrap basique
        String desc = skill.getDescription();
        if (font.width(desc) > tipW - 12) {
            int split = desc.indexOf(',');
            if (split < 0) split = desc.indexOf(' ', desc.length() / 2);
            if (split > 0) {
                g.drawString(font, desc.substring(0, split).trim(), tipX + 6, tipY + 18, UIRenderer.COLOR_TEXT_SECONDARY, false);
                g.drawString(font, desc.substring(split + 1).trim(), tipX + 6, tipY + 30, UIRenderer.COLOR_TEXT_SECONDARY, false);
            } else {
                g.drawString(font, desc, tipX + 6, tipY + 18, UIRenderer.COLOR_TEXT_SECONDARY, false);
            }
        } else {
            g.drawString(font, desc, tipX + 6, tipY + 18, UIRenderer.COLOR_TEXT_SECONDARY, false);
        }

        if (tooltip != null) {
            g.drawString(font, tooltip, tipX + 6, tipY + tipH - font.lineHeight - 4, UIRenderer.COLOR_TEXT_PRIMARY, false);
        }
    }

    private void drawBackButton(GuiGraphics g, int mouseX, int mouseY) {
        int btnW = 100, btnH = 18;
        int btnX = panelX + PANEL_W - btnW - 10;
        int btnY = panelY + PANEL_H - btnH - 8;
        boolean hovered = mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH;

        UIRenderer.fillRect(g, btnX, btnY, btnW, btnH, hovered ? UIRenderer.COLOR_ACCENT_GOLD : UIRenderer.COLOR_BG_DARK);
        UIRenderer.drawBorder(g, btnX, btnY, btnW, btnH, UIRenderer.COLOR_BORDER_GOLD);

        String txt = "← Retour";
        int tw = font.width(txt);
        g.drawString(font, txt, btnX + (btnW - tw) / 2, btnY + (btnH - font.lineHeight) / 2,
            hovered ? 0xFF111111 : UIRenderer.COLOR_TEXT_GOLD, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX, my = (int) mouseY;

        // Bouton retour
        int btnW = 100, btnH = 18;
        int btnX = panelX + PANEL_W - btnW - 10;
        int btnY = panelY + PANEL_H - btnH - 8;
        if (mx >= btnX && mx < btnX + btnW && my >= btnY && my < btnY + btnH) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }

        // Clic sur un nœud
        if (hoveredNode != null) {
            PlayerProfessionData data = getPlayerData();
            if (data != null) {
                String skillId = hoveredNode.getId();
                Set<String> unlocked = data.getUnlockedSkills(professionType);
                if (!unlocked.contains(skillId) && data.getSkillPoints(professionType) > 0
                    && profession.getSkillTree().canUnlock(skillId, unlocked)) {
                    NetworkHandler.sendToServer(new PacketUnlockSkill(professionType, skillId));
                    return true;
                }
            }
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
