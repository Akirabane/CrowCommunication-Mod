package com.crowcommunication.corbeau;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

/**
 * Commande interne {@code /corbeau-choice <msgId> <keep|destroy>}.
 *
 * <p>Invoquée exclusivement via les boutons cliquables du chat lors de la réception
 * d'une lettre. Non destinée à être tapée manuellement par un joueur.</p>
 */
public class CorbeauChoiceCommand {

    @SuppressWarnings("null")
    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("corbeau-choice")
                .then(Commands.argument("msgId", StringArgumentType.string())
                    .then(Commands.argument("choice", StringArgumentType.word())
                        .executes(ctx -> {
                            if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) return 0;
                            String idStr = StringArgumentType.getString(ctx, "msgId");
                            String choice = StringArgumentType.getString(ctx, "choice");
                            try {
                                UUID id = UUID.fromString(idStr);
                                CorbeauManager.onLetterDecision(sp, id, "keep".equalsIgnoreCase(choice));
                                return 1;
                            } catch (IllegalArgumentException e) {
                                return 0;
                            }
                        }))));
    }
}
