package com.crowcommunication.corbeau;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Système d'usurpation de sceau.
 *
 * <p>Gère le cooldown 30 min, la validation server-side du pseudo cible via le ProfileCache,
 * le tirage 30 % de réussite, et les retours visuels/sonores à l'expéditeur.
 * Le destinataire n'a jamais aucune information sur une tentative.</p>
 *
 * <p>Extraction conservative depuis {@link CorbeauManager} (v1.0.8). CorbeauManager garde
 * des méthodes statiques de façade qui délèguent ici pour préserver la compatibilité.</p>
 *
 * @author Akirabane
 */
public final class ForgerySystem {

    private ForgerySystem() {}

    /** Cooldown spécifique aux tentatives d'usurpation : 30 min. */
    public static final long FORGE_COOLDOWN_TICKS = 20L * 60L * 30L;
    /** Probabilité serveur d'usurpation effective une fois le QTE réussi côté client. */
    public static final double FORGE_SUCCESS_CHANCE = 0.30;

    private static final Map<UUID, Long> LAST_FORGE_ATTEMPT = new ConcurrentHashMap<>();

    /** Cooldown d'usurpation restant en ticks pour ce joueur (0 si disponible). */
    public static long cooldownRemainingTicks(ServerPlayer p) {
        Long last = LAST_FORGE_ATTEMPT.get(p.getUUID());
        MinecraftServer srv = p.getServer();
        if (last == null || srv == null) return 0;
        long left = FORGE_COOLDOWN_TICKS - (srv.getTickCount() - last);
        return Math.max(0, left);
    }

    /**
     * Résout le nom à afficher au destinataire :
     * <ul>
     *   <li>{@code forgeName} vide → renvoie le vrai pseudo (pas de tentative)</li>
     *   <li>{@code forgeName} = pseudo de l'expéditeur → renvoie le vrai pseudo (no-op)</li>
     *   <li>Pseudo inconnu (ni en ligne, ni en ProfileCache) → tentative silencieuse, sans cooldown</li>
     *   <li>Cooldown 30 min actif → renvoie le vrai pseudo + message d'avertissement, sans cooldown supplémentaire</li>
     *   <li>Sinon → tirage 30 % ; le cooldown est consommé succès ou échec</li>
     * </ul>
     */
    public static String resolveDisplaySender(ServerPlayer sender, String forgeName) {
        String real = sender.getGameProfile().getName();
        if (forgeName == null || forgeName.isBlank()) return real;
        String target = forgeName.trim();
        if (target.equalsIgnoreCase(real)) return real;

        MinecraftServer server = sender.getServer();
        if (server == null) return real;
        boolean known = server.getPlayerList().getPlayerByName(target) != null;
        if (!known) {
            try {
                known = server.getProfileCache() != null
                    && server.getProfileCache().get(target).isPresent();
            } catch (Throwable ignored) {}
        }
        if (!known) {
            sender.sendSystemMessage(Component.literal(
                "§8§oTu n'arrives pas à imiter un sceau que tu n'as jamais vu — §f" + target + "§8 t'est inconnu."));
            return real;
        }

        long now = server.getTickCount();
        Long last = LAST_FORGE_ATTEMPT.get(sender.getUUID());
        if (last != null && now - last < FORGE_COOLDOWN_TICKS) {
            long secLeft = (FORGE_COOLDOWN_TICKS - (now - last)) / 20L;
            long minLeft = (secLeft + 59) / 60L;
            sender.sendSystemMessage(Component.literal(
                "§c§oTes mains tremblent encore — tu ne peux pas usurper un sceau avant §f" + minLeft + " min§c."));
            return real;
        }
        LAST_FORGE_ATTEMPT.put(sender.getUUID(), now);

        boolean success = sender.getRandom().nextDouble() < FORGE_SUCCESS_CHANCE;
        if (success) {
            sender.sendSystemMessage(Component.literal(
                "§6§oTon trait de plume imite à la perfection le sceau de §f" + target + "§6..."));
            playForgeFeedback(sender, true);
            return target;
        }
        sender.sendSystemMessage(Component.literal(
            "§8§oTa tentative d'imiter le sceau de §f" + target + "§8 a raté — la lettre part sous ton vrai nom."));
        playForgeFeedback(sender, false);
        return real;
    }

    /** Effets RP du résultat d'usurpation — visibles uniquement par l'expéditeur. */
    private static void playForgeFeedback(ServerPlayer sender, boolean success) {
        if (!(sender.level() instanceof ServerLevel sl)) return;
        Vec3 p = sender.position().add(0, 1.2, 0);
        if (success) {
            sl.playSound(null, sender.blockPosition(),
                SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.6f, 1.6f);
            sl.playSound(null, sender.blockPosition(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.5f, 1.4f);
            sl.sendParticles(ParticleTypes.END_ROD,  p.x, p.y, p.z, 10, 0.4, 0.4, 0.4, 0.02);
            sl.sendParticles(ParticleTypes.GLOW,     p.x, p.y, p.z, 16, 0.5, 0.5, 0.5, 0.03);
            sl.sendParticles(ParticleTypes.FLAME,    p.x, p.y, p.z,  6, 0.3, 0.3, 0.3, 0.01);
        } else {
            sl.playSound(null, sender.blockPosition(),
                SoundEvents.WOOL_BREAK, SoundSource.PLAYERS, 0.7f, 0.7f);
            sl.playSound(null, sender.blockPosition(),
                SoundEvents.NOTE_BLOCK_BASEDRUM.value(), SoundSource.PLAYERS, 0.5f, 0.6f);
            sl.sendParticles(ParticleTypes.SMOKE,    p.x, p.y, p.z, 14, 0.4, 0.4, 0.4, 0.02);
            sl.sendParticles(ParticleTypes.ASH,      p.x, p.y, p.z, 18, 0.5, 0.4, 0.5, 0.03);
        }
    }
}
