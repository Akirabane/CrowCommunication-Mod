package com.crowcommunication.corbeau;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Suivi serveur : pour chaque joueur, savoir si son client a fini d'initialiser MCEF.
 * Sans ça, /corbeau peut spawner un oiseau alors que l'UI n'est pas chargeable côté client.
 */
public class ClientReadyState {

    private static final Set<UUID> READY = ConcurrentHashMap.newKeySet();

    public static void setReady(ServerPlayer p)        { READY.add(p.getUUID()); }
    public static boolean isReady(ServerPlayer p)      { return READY.contains(p.getUUID()); }
    public static void clear(ServerPlayer p)           { READY.remove(p.getUUID()); }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) clear(sp);
    }
}
