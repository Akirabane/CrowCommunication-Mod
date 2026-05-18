package com.crowcommunication.client.web;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Signale au serveur la disponibilité MCEF du client à chaque connexion.
 *
 * <p>Si MCEF n'est pas encore initialisé au moment de la connexion,
 * {@link MCEFBootstrap} renverra le signal une fois l'initialisation terminée.</p>
 */
@OnlyIn(Dist.CLIENT)
public class ClientLoginHook {

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        MCEFBootstrap.notifyServerIfConnected();
    }
}
