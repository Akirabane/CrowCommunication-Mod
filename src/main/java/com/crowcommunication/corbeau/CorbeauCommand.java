package com.crowcommunication.corbeau;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class CorbeauCommand {

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("corbeau")
            .then(Commands.argument("destinataire", StringArgumentType.word())
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
                .executes(ctx -> {
                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
                        ctx.getSource().sendFailure(Component.literal("Cette commande doit être exécutée par un joueur."));
                        return 0;
                    }
                    String raw = StringArgumentType.getString(ctx, "destinataires");
                    Set<String> uniq = new LinkedHashSet<>();
                    for (String tok : raw.split("[ ,]+")) {
                        String t = tok.trim();
                        if (!t.isEmpty()) uniq.add(t);
                    }
                    List<String> names = new ArrayList<>(uniq);
                    if (names.isEmpty()) {
                        sp.sendSystemMessage(Component.literal("§c§oIndique au moins un destinataire."));
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
}
