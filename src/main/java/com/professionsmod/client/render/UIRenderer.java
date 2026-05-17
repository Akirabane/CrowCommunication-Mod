package com.professionsmod.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

public class UIRenderer {

    // Palette de couleurs — thème sombre/doré RPG
    public static final int COLOR_BG_DARK      = 0xFF0D0D14;
    public static final int COLOR_BG_PANEL     = 0xFF161620;
    public static final int COLOR_BG_CARD      = 0xFF1E1E2E;
    public static final int COLOR_ACCENT_GOLD  = 0xFFD4A017;
    public static final int COLOR_ACCENT_LIGHT = 0xFFFFD700;
    public static final int COLOR_BORDER       = 0xFF2A2A3A;
    public static final int COLOR_BORDER_GOLD  = 0xFF8B6914;
    public static final int COLOR_TEXT_PRIMARY  = 0xFFEEEEEE;
    public static final int COLOR_TEXT_SECONDARY = 0xFF9999AA;
    public static final int COLOR_TEXT_GOLD    = 0xFFD4A017;
    public static final int COLOR_XP_BAR_BG    = 0xFF1A1A2A;
    public static final int COLOR_XP_BAR_FG    = 0xFF4A90E2;
    public static final int COLOR_XP_BAR_MAX   = 0xFFD4A017;

    public static final int COLOR_SKILL_LOCKED     = 0xFF2A2A3A;
    public static final int COLOR_SKILL_AVAILABLE  = 0xFF2A4A6A;
    public static final int COLOR_SKILL_UNLOCKED   = 0xFF1A5C2A;
    public static final int COLOR_SKILL_SPECIALTY  = 0xFF6A1A6A;

    public static final int COLOR_MAIN_BADGE   = 0xFF8B1A1A;
    public static final int COLOR_SEC_BADGE    = 0xFF1A4A8B;

    /** Dessine un rectangle plein avec transparence. */
    public static void fillRect(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + h, color);
    }

    /** Dessine une bordure (1px) autour d'un rectangle. */
    public static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** Rectangle avec fond + bordure dorée. */
    public static void drawCard(GuiGraphics g, int x, int y, int w, int h) {
        fillRect(g, x, y, w, h, COLOR_BG_CARD);
        drawBorder(g, x, y, w, h, COLOR_BORDER);
        drawBorder(g, x + 1, y + 1, w - 2, h - 2, COLOR_BORDER_GOLD);
    }

    /** Barre de progression stylée. */
    public static void drawProgressBar(GuiGraphics g, int x, int y, int w, int h, float progress, int colorFg) {
        fillRect(g, x, y, w, h, COLOR_XP_BAR_BG);
        drawBorder(g, x, y, w, h, COLOR_BORDER);
        int filled = (int) (w * progress);
        if (filled > 2) {
            fillRect(g, x + 1, y + 1, filled - 2, h - 2, colorFg);
        }
    }

    /** Cercle rempli (approximation par rectangles). */
    public static void fillCircle(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.sqrt(r * r - dy * dy);
            g.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    /** Cercle vide (bordure seulement). */
    public static void drawCircle(GuiGraphics g, int cx, int cy, int r, int color) {
        int x = 0, y = r;
        int d = 3 - 2 * r;
        while (y >= x) {
            plotCirclePoints(g, cx, cy, x, y, color);
            if (d <= 0) { d += 4 * x + 6; }
            else { d += 4 * (x - y) + 10; y--; }
            x++;
        }
    }

    private static void plotCirclePoints(GuiGraphics g, int cx, int cy, int x, int y, int color) {
        g.fill(cx + x, cy + y, cx + x + 1, cy + y + 1, color);
        g.fill(cx - x, cy + y, cx - x + 1, cy + y + 1, color);
        g.fill(cx + x, cy - y, cx + x + 1, cy - y + 1, color);
        g.fill(cx - x, cy - y, cx - x + 1, cy - y + 1, color);
        g.fill(cx + y, cy + x, cx + y + 1, cy + x + 1, color);
        g.fill(cx - y, cy + x, cx - y + 1, cy + x + 1, color);
        g.fill(cx + y, cy - x, cx + y + 1, cy - x + 1, color);
        g.fill(cx - y, cy - x, cx - y + 1, cy - x + 1, color);
    }

    /** Ligne entre deux points. */
    public static void drawLine(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            g.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    /** En-tête séparateur avec titre centré. */
    public static void drawSectionHeader(GuiGraphics g, int x, int y, int w, String title, net.minecraft.client.gui.Font font) {
        int lineY = y + font.lineHeight / 2;
        int titleW = font.width(title);
        int gap = 8;
        g.fill(x, lineY, x + (w - titleW) / 2 - gap, lineY + 1, COLOR_BORDER_GOLD);
        g.fill(x + (w + titleW) / 2 + gap, lineY, x + w, lineY + 1, COLOR_BORDER_GOLD);
        g.drawString(font, title, x + (w - titleW) / 2, y, COLOR_TEXT_GOLD, false);
    }

    /** Badge coloré (ex: "PRINCIPAL" / "SECONDAIRE"). */
    public static void drawBadge(GuiGraphics g, net.minecraft.client.gui.Font font, int x, int y, String text, int bgColor) {
        int tw = font.width(text);
        int pad = 4;
        fillRect(g, x, y, tw + pad * 2, font.lineHeight + pad, bgColor);
        drawBorder(g, x, y, tw + pad * 2, font.lineHeight + pad, COLOR_BORDER);
        g.drawString(font, text, x + pad, y + pad / 2, COLOR_TEXT_PRIMARY, false);
    }

    /** Nœud de compétence circulaire. */
    public static void drawSkillNode(GuiGraphics g, int cx, int cy, int r, int fillColor, int borderColor, boolean glowing) {
        if (glowing) {
            fillCircle(g, cx, cy, r + 2, (fillColor & 0x00FFFFFF) | 0x40000000);
        }
        fillCircle(g, cx, cy, r, fillColor);
        drawCircle(g, cx, cy, r, borderColor);
        drawCircle(g, cx, cy, r + 1, (borderColor & 0x00FFFFFF) | 0x80000000);
    }

    /** Fond principal avec vignette. */
    public static void drawBackground(GuiGraphics g, int screenW, int screenH) {
        g.fill(0, 0, screenW, screenH, 0xCC000000);
        fillRect(g, 0, 0, screenW, screenH, COLOR_BG_DARK & 0x99FFFFFF);
    }
}
