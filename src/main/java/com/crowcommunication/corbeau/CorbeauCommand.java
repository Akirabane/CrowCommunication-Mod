package com.crowcommunication.corbeau;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Enregistre les commandes {@code /corbeau} et {@code /corbeau-groupe}.
 *
 * <p>{@code /corbeau <pseudo>} envoie une lettre à un joueur.
 * {@code /corbeau-groupe <pseudo1> <pseudo2> ...} envoie à plusieurs joueurs (max 8),
 * chacun recevant un corbeau individuel.</p>
 *
 * @author Akirabane
 */
public class CorbeauCommand {

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    /**
     * Enregistre les deux littéraux de commande dans le dispatcher Brigadier.
     *
     * @param dispatcher le dispatcher Brigadier du serveur
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("corbeau")
            .then(Commands.argument("destinataire", StringArgumentType.word())
                .suggests(CorbeauCommand::suggestSinglePlayer)
                .executes(ctx -> {
                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
                        ctx.getSource().sendFailure(Component.literal("Cette commande doit être exécutée par un joueur."));
                        return 0;
                    }
                    String name = StringArgumentType.getString(ctx, "destinataire").trim();
                    if (name.isEmpty()) {
                        sp.sendSystemMessage(Component.literal("§c§oIndique un destinataire : /corbeau <pseudo>"));
                        return 0;
                    }
                    if (name.equalsIgnoreCase(sp.getGameProfile().getName())) {
                        sp.sendSystemMessage(Component.literal("§c§oUn corbeau ne peut pas porter une lettre à son propre expéditeur."));
                        return 0;
                    }
                    CorbeauManager.setPendingGroup(sp, java.util.List.of(name));
                    CorbeauManager.summonForPlayer(sp);
                    return 1;
                }))
            .executes(ctx -> {
                ctx.getSource().sendFailure(Component.literal(
                    "§c§oUsage : /corbeau <pseudo>"));
                return 0;
            }));

        dispatcher.register(Commands.literal("corbeau-groupe")
            .then(Commands.argument("destinataires", StringArgumentType.greedyString())
                .suggests(CorbeauCommand::suggestGroupPlayer)
                .executes(ctx -> {
                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
                        ctx.getSource().sendFailure(Component.literal("Cette commande doit être exécutée par un joueur."));
                        return 0;
                    }
                    String raw = StringArgumentType.getString(ctx, "destinataires");
                    String selfName = sp.getGameProfile().getName();
                    Set<String> uniq = new LinkedHashSet<>();
                    boolean selfFiltered = false;
                    for (String tok : raw.split("[ ,]+")) {
                        String t = tok.trim();
                        if (t.isEmpty()) continue;
                        if (t.equalsIgnoreCase(selfName)) { selfFiltered = true; continue; }
                        uniq.add(t);
                    }
                    if (selfFiltered) {
                        sp.sendSystemMessage(Component.literal("§c§oTu ne peux pas t'envoyer une lettre à toi-même — ton nom a été retiré de la liste."));
                    }
                    List<String> names = new ArrayList<>(uniq);
                    if (names.isEmpty()) {
                        sp.sendSystemMessage(Component.literal("§c§oIndique au moins un destinataire valide."));
                        return 0;
                    }
                    if (names.size() > 8) {
                        sp.sendSystemMessage(Component.literal("§c§oTrop de destinataires (max 8)."));
                        return 0;
                    }
                    CorbeauManager.setPendingGroup(sp, names);
                    sp.sendSystemMessage(Component.literal(
                        "§8§oTu prépares §f" + names.size() + "§8 corbeaux pour : §f" + String.join(", ", names)));
                    CorbeauManager.summonForPlayer(sp);
                    return 1;
                })));
    }

    /** Suggère les pseudos en ligne (hors expéditeur) pour {@code /corbeau <pseudo>}. */
    private static CompletableFuture<Suggestions> suggestSinglePlayer(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        String selfName = (ctx.getSource().getEntity() instanceof ServerPlayer sp)
            ? sp.getGameProfile().getName() : null;
        if (ctx.getSource().getServer() != null) {
            for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                String name = p.getGameProfile().getName();
                if (selfName != null && name.equalsIgnoreCase(selfName)) continue;
                if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    /** Suggère le pseudo en cours dans la liste greedy de {@code /corbeau-groupe}. */
    private static CompletableFuture<Suggestions> suggestGroupPlayer(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        int lastSep = -1;
        for (int i = 0; i < remaining.length(); i++) {
            char c = remaining.charAt(i);
            if (c == ' ' || c == ',') lastSep = i;
        }
        String prefix = remaining.substring(lastSep + 1).toLowerCase(Locale.ROOT);
        String alreadyTyped = remaining.substring(0, lastSep + 1).toLowerCase(Locale.ROOT);
        String selfName = (ctx.getSource().getEntity() instanceof ServerPlayer sp)
            ? sp.getGameProfile().getName() : null;
        SuggestionsBuilder offset = builder.createOffset(builder.getStart() + lastSep + 1);
        if (ctx.getSource().getServer() != null) {
            for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                String name = p.getGameProfile().getName();
                if (selfName != null && name.equalsIgnoreCase(selfName)) continue;
                if (alreadyTyped.contains(name.toLowerCase(Locale.ROOT))) continue;
                if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) offset.suggest(name);
            }
        }
        return offset.buildFuture();
    }
}
