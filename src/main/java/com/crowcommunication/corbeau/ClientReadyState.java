package com.crowcommunication.corbeau;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registre serveur des joueurs dont le client a terminé l'initialisation de MCEF.
 *
 * <p>Sans ce suivi, {@code /corbeau} pourrait invoquer un oiseau alors que l'interface
 * de composition n'est pas encore disponible côté client.</p>
 */
public class ClientReadyState {

    private static final Set<UUID> READY = ConcurrentHashMap.newKeySet();

    /** Marque le joueur comme prêt à recevoir l'interface MCEF. */
    public static void setReady(ServerPlayer p)   { READY.add(p.getUUID()); }

    /** @return {@code true} si le client du joueur a signalé MCEF comme initialisé. */
    public static boolean isReady(ServerPlayer p) { return READY.contains(p.getUUID()); }

    /** Retire le joueur du registre (déconnexion). */
    public static void clear(ServerPlayer p)      { READY.remove(p.getUUID()); }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) clear(sp);
    }
}
