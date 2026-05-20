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
    /** Probabilité serveur d'usurpation effective une fois le QTE 3 manches réussi. */
    public static final double FORGE_SUCCESS_CHANCE = 0.30;

    private static final Map<UUID, Long> LAST_FORGE_ATTEMPT = new ConcurrentHashMap<>();

    /** Mélange aléatoire (Fisher–Yates) des lettres d'un nom pour obtenir un anagramme. */
    private static String anagram(String name, net.minecraft.util.RandomSource rng) {
        if (name == null || name.length() < 2) return name;
        char[] chars = name.toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            char tmp = chars[i]; chars[i] = chars[j]; chars[j] = tmp;
        }
        String result = new String(chars);
        // Évite anagramme identique au nom original (statistiquement rare mais possible)
        if (result.equals(name)) {
            return new StringBuilder(name).reverse().toString();
        }
        return result;
    }

    /** Réinitialise le cooldown d'usurpation pour ce joueur. Pour debug/op. */
    public static void resetCooldown(ServerPlayer p) {
        if (p != null) LAST_FORGE_ATTEMPT.remove(p.getUUID());
    }

    /** Cooldown d'usurpation restant en ticks pour ce joueur (0 si disponible). */
    public static long cooldownRemainingTicks(ServerPlayer p) {
        Long last = LAST_FORGE_ATTEMPT.get(p.getUUID());
        MinecraftServer srv = p.getServer();
        if (last == null || srv == null) return 0;
        long left = FORGE_COOLDOWN_TICKS - (srv.getTickCount() - last);
        return Math.max(0, left);
    }

    /**
     * Résout le nom à afficher au destinataire selon le score QTE :
     * <ul>
     *   <li>{@code qteRounds} = 0 ou 1 → vrai pseudo (échec ou pas de tentative). Pas de cooldown.</li>
     *   <li>{@code qteRounds} = 2 → anagramme du vrai pseudo (la plume a tremblé sur la fin). Cooldown 30 min.</li>
     *   <li>{@code qteRounds} = 3 → tirage 30 % pseudo cible / 70 % anagramme du pseudo cible. Cooldown 30 min.</li>
     * </ul>
     * Pour les cas avec cooldown actif : repli silencieux vers vrai pseudo, message d'avertissement.
     * Pour pseudo cible inconnu : repli vers vrai pseudo, message d'avertissement.
     */
    public static String resolveDisplaySender(ServerPlayer sender, String forgeName, int qteRounds) {
        String real = sender.getGameProfile().getName();
        if (forgeName == null || forgeName.isBlank()) return real;
        String target = forgeName.trim();
        if (target.equalsIgnoreCase(real)) return real;

        // 0 ou 1 manche → QTE raté, vrai nom, aucune autre conséquence
        if (qteRounds <= 1) {
            sender.sendSystemMessage(Component.literal(
                "§8§oTa plume a tremblé dès le premier trait — la lettre part sous ton vrai nom."));
            playForgeFeedback(sender, false);
            return real;
        }

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

        // 2 manches → anagramme du VRAI nom (échec partiel : la plume a fini par déraper)
        if (qteRounds == 2) {
            String shuffled = anagram(real, sender.getRandom());
            sender.sendSystemMessage(Component.literal(
                "§8§oTa plume a tremblé sur le dernier trait — la signature ressemble à un brouillon... §f"
                + shuffled + "§8."));
            playForgeFeedback(sender, false);
            return shuffled;
        }

        // 3 manches → 30 % vrai sceau cible, 70 % anagramme du sceau cible
        boolean perfect = sender.getRandom().nextDouble() < FORGE_SUCCESS_CHANCE;
        if (perfect) {
            sender.sendSystemMessage(Component.literal(
                "§6§oTon trait de plume imite à la perfection le sceau de §f" + target + "§6..."));
            playForgeFeedback(sender, true);
            return target;
        }
        String shuffledTarget = anagram(target, sender.getRandom());
        sender.sendSystemMessage(Component.literal(
            "§e§oTu as réussi le rythme, mais l'encre a bavé... la signature ressemble à §f"
            + shuffledTarget + "§e plutôt qu'à §f" + target + "§e."));
        playForgeFeedback(sender, false);
        return shuffledTarget;
    }

    /** Façade 2-arg historique : équivaut à un QTE plein réussi. */
    public static String resolveDisplaySender(ServerPlayer sender, String forgeName) {
        return resolveDisplaySender(sender, forgeName, 3);
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
