package com.crowcommunication.corbeau;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Rendu visuel des lettres : item papier nommé, lore mis en forme, couleur de sceau stable.
 *
 * <p>Extraction conservative depuis {@link CorbeauManager} (v1.0.8). Logique 100 %
 * fonctionnelle et sans état — pas de race condition à gérer.</p>
 *
 * @author Akirabane
 */
public final class LetterRenderer {

    private LetterRenderer() {}

    /** 12 couleurs de sceau ; index déterministe via hash stable du nom (case-insensitive). */
    private static final ChatFormatting[] SEAL_COLORS = new ChatFormatting[] {
        ChatFormatting.RED, ChatFormatting.GOLD, ChatFormatting.YELLOW, ChatFormatting.GREEN,
        ChatFormatting.AQUA, ChatFormatting.BLUE, ChatFormatting.LIGHT_PURPLE, ChatFormatting.DARK_RED,
        ChatFormatting.DARK_GREEN, ChatFormatting.DARK_AQUA, ChatFormatting.DARK_PURPLE, ChatFormatting.DARK_BLUE
    };

    /**
     * @param name nom de l'expéditeur (potentiellement usurpé)
     * @return couleur de sceau déterministe associée à ce nom
     */
    public static ChatFormatting sealColorFor(String name) {
        if (name == null || name.isEmpty()) return ChatFormatting.GRAY;
        int h = name.toLowerCase(Locale.ROOT).hashCode();
        return SEAL_COLORS[Math.floorMod(h, SEAL_COLORS.length)];
    }

    /** Donne au joueur un item lettre (essaye l'inventaire, sinon drop devant lui). */
    public static void giveLetterItem(ServerPlayer player, String sender, String subject, String body) {
        ItemStack stack = makeLetterItem(sender, subject, body);
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }

    /** Construit un item papier représentant la lettre, avec sceau coloré dans le lore. */
    public static ItemStack makeLetterItem(String sender, String subject, String body) {
        ItemStack stack = new ItemStack(Items.PAPER);
        Component title = Component.literal("✉ " + subject).withStyle(s ->
            s.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(false));
        stack.setHoverName(title);
        // Tags NBT custom pour la lecture client-side (right-click viewer)
        CompoundTag crow = stack.getOrCreateTag();
        crow.putString("crow_sender",  sender  != null ? sender  : "");
        crow.putString("crow_subject", subject != null ? subject : "");
        crow.putString("crow_body",    body    != null ? body    : "");

        CompoundTag display = stack.getOrCreateTagElement("display");
        ListTag lore = new ListTag();
        ChatFormatting seal = sealColorFor(sender);
        lore.add(StringTag.valueOf(Component.Serializer.toJson(
            Component.literal("● ").withStyle(seal)
                .append(Component.literal("Sceau de " + sender).withStyle(ChatFormatting.GRAY)))));
        lore.add(StringTag.valueOf(Component.Serializer.toJson(
            Component.literal("De : " + sender).withStyle(ChatFormatting.GRAY))));
        lore.add(StringTag.valueOf(Component.Serializer.toJson(
            Component.literal(""))));
        for (String line : wrap(body, 38)) {
            lore.add(StringTag.valueOf(Component.Serializer.toJson(
                Component.literal(line)
                    .withStyle(s -> s.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(false)))));
        }
        display.put("Lore", lore);
        return stack;
    }

    /** Wrap simple par mot pour faire tenir un texte dans le lore d'un item. */
    private static List<String> wrap(String body, int width) {
        List<String> out = new ArrayList<>();
        if (body == null) return out;
        for (String paragraph : body.split("\n")) {
            if (paragraph.isEmpty()) { out.add(""); continue; }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (line.length() == 0) line.append(word);
                else if (line.length() + 1 + word.length() <= width) line.append(' ').append(word);
                else { out.add(line.toString()); line.setLength(0); line.append(word); }
            }
            if (line.length() > 0) out.add(line.toString());
        }
        return out;
    }
}
