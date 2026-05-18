package com.crowcommunication.client.web;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@OnlyIn(Dist.CLIENT)
public class ClientLoginHook {

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        // Toujours signaler au serveur que le client est connecté et prêt à recevoir des corbeaux.
        // Si MCEF n'est pas disponible, l'erreur sera gérée à l'ouverture de l'écran de composition.
        MCEFBootstrap.notifyServerIfConnected();
    }
}
