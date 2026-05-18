package com.crowcommunication.corbeau;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.crowcommunication.network.NetworkHandler;
import com.crowcommunication.network.PacketOpenCompose;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Gestionnaire central du système de courrier par corbeau.
 *
 * <h2>Cycle d'envoi</h2>
 * <ol>
 *   <li>{@code /corbeau} → corbeau {@code SUMMON} descend chez l'expéditeur et attend.</li>
 *   <li>L'expéditeur soumet sa lettre → programmation d'une {@link PendingDelivery} avec délai
 *       (10 s + distance), le corbeau {@code SUMMON} repart.</li>
 * </ol>
 *
 * <h2>Cycle de livraison</h2>
 * <ol>
 *   <li>À échéance, le message entre dans la {@link #QUEUE} du destinataire.</li>
 *   <li>Un corbeau {@code DELIVERY} est spawné ; il descend, affiche la lettre dans le chat
 *       et attend la décision du joueur (max 15 s).</li>
 *   <li>Le joueur garde ou détruit la lettre ; le corbeau repart. Le message suivant suit.</li>
 * </ol>
 *
 * <h2>Timeout</h2>
 * <p>Sans réponse en 15 s, la lettre est droppée devant l'expéditeur original et le corbeau repart.</p>
 *
 * <h2>Interception</h2>
 * <p>Un corbeau {@code DELIVERY} est vulnérable : abattu à l'arc, la lettre tombe au sol.</p>
 */
public class CorbeauManager {

    // ============================ Types ============================

    private enum Kind { SUMMON, DELIVERY }
    private enum Phase { INCOMING, WAITING, OUTGOING }

    private static final class Bird {
        Kind kind;
        final Mob chicken;
        ServerPlayer player;
        final Vec3 origin;
        Phase phase;
        int ticks;
        Vec3 flyTo;
        /** Seul le bird principal ouvre l'UI de composition et déclenche le départ. */
        boolean isMain;
        /** Nom du destinataire vers lequel ce bird s'envole au départ (null = direction par défaut). */
        String recipientName;
        // pour DELIVERY :
        UUID msgId;
        String sender, subject, body;
        /** Nom à afficher au destinataire (null = utiliser sender). Set si usurpation réussie. */
        String displaySender;

        /** IDs des livraisons programmées portées par ce corbeau (SUMMON OUTGOING interceptable). */
        List<UUID> deliveryIds;
        /** Contenu de la lettre portée (SUMMON OUTGOING). */
        String outSubject, outBody;
        /** Durée totale de vol pour un SUMMON OUTGOING porteur — calée sur le délai de livraison. */
        long outgoingDurationTicks;
        /** Position de décollage capturée à l'instant où l'oiseau passe en OUTGOING (départ depuis l'expéditeur). */
        Vec3 outgoingFrom;
        /** Vrai si ce corbeau de livraison transporte une lettre retournée (le destinataire AFK la renvoie). */
        boolean isReturn;

        Bird(Kind k, Mob c, ServerPlayer p, Vec3 origin) {
            this.kind = k; this.chicken = c; this.player = p; this.origin = origin;
            this.phase = Phase.INCOMING; this.ticks = 0;
        }
    }

    private static final List<Bird> BIRDS = new ArrayList<>();

    /** Une livraison programmée temporellement (en cours de "vol" virtuel jusqu'au destinataire). */
    public static final class PendingDelivery {
        public final UUID recipientUUID;
        public final UUID msgId;
        public final String sender, subject, body;
        /** Nom affiché au destinataire — si {@code null}, on utilise {@link #sender}. Usurpation = différent. */
        public final String displaySender;
        public final long deliverAtTick;
        public final boolean isReturn;
        public PendingDelivery(UUID r, UUID id, String s, String disp, String su, String b, long t, boolean ret) {
            this.recipientUUID = r; this.msgId = id; this.sender = s; this.displaySender = disp;
            this.subject = su; this.body = b;
            this.deliverAtTick = t; this.isReturn = ret;
        }
    }
    private static final List<PendingDelivery> PENDING = new ArrayList<>();

    /** Message arrivé en attente d'être physiquement livré (queue par joueur). */
    public static final class QueuedMessage {
        public final UUID id;
        public final String sender, subject, body;
        public final String displaySender;
        public final boolean isReturn;
        public QueuedMessage(UUID id, String s, String disp, String su, String b, boolean ret) {
            this.id = id; this.sender = s; this.displaySender = disp;
            this.subject = su; this.body = b; this.isReturn = ret;
        }
    }
    private static final Map<UUID, Deque<QueuedMessage>> QUEUE = new ConcurrentHashMap<>();

    /**
     * Un pigeon perdu : lettre qu'un corbeau n'a jamais pu atteindre (destinataire trop loin).
     * Apparaît au sol après un délai, à une position calculée au moment de l'envoi —
     * quiconque trouve la lettre peut la lire.
     */
    public static final class LostPigeon {
        public final String senderRealName;   // expéditeur réel — pour traçabilité interne
        public final String displaySender;    // nom affiché (peut être usurpé)
        public final String subject, body;
        public final String dimensionKey;     // "minecraft:overworld" etc.
        public final double x, y, z;
        public final long dropAtMillis;       // epoch ms : survit aux restarts serveur
        public LostPigeon(String real, String disp, String su, String b,
                          String dim, double x, double y, double z, long dropAt) {
            this.senderRealName = real; this.displaySender = disp;
            this.subject = su; this.body = b;
            this.dimensionKey = dim; this.x = x; this.y = y; this.z = z;
            this.dropAtMillis = dropAt;
        }
    }
    private static final List<LostPigeon> LOST_PIGEONS = new ArrayList<>();
    /** Marque la persistance comme à-sauver à la prochaine sauvegarde du monde. */
    private static java.util.function.Consumer<Void> persistDirty = v -> {};
    public static void setPersistenceDirtyMarker(java.util.function.Consumer<Void> marker) {
        persistDirty = marker == null ? (v -> {}) : marker;
    }

    // ============================ Économie & règles ============================

    public static final long COOLDOWN_TICKS = 20L * 120L;          // 2 min entre deux envois
    public static final double MAX_DELIVERY_DISTANCE = 1500.0;     // au-delà : "le corbeau s'est perdu"
    public static final double INTERCEPT_RADIUS = 32.0;            // joueurs tiers qui entendent passer

    private static final Map<UUID, Long> LAST_SEND = new ConcurrentHashMap<>();
    /** Liste de destinataires en attente pour le prochain envoi d'un joueur (après /corbeau-groupe). */
    private static final Map<UUID, List<String>> PENDING_GROUP = new ConcurrentHashMap<>();
    /** Dernier tick d'alerte d'interception émis pour un corbeau (anti-spam). */
    private static final Map<UUID, Long> LAST_INTERCEPT_PING = new ConcurrentHashMap<>();

    /** Historique des 10 dernières lettres envoyées/reçues par joueur. */
    public static final class HistoryEntry {
        public final long worldTimeMillis;
        public final boolean outgoing;     // true = envoyée par moi
        public final String otherParty;    // nom du destinataire (envoi) ou de l'expéditeur affiché (réception)
        public final String subject;
        public final boolean forgeryAttempted;
        public final boolean forgerySucceeded;
        public HistoryEntry(long t, boolean out, String other, String subject,
                            boolean attempted, boolean succeeded) {
            this.worldTimeMillis = t; this.outgoing = out;
            this.otherParty = other; this.subject = subject;
            this.forgeryAttempted = attempted; this.forgerySucceeded = succeeded;
        }
    }
    private static final int HISTORY_MAX = 10;
    private static final Map<UUID, Deque<HistoryEntry>> HISTORY = new ConcurrentHashMap<>();

    public static void recordHistory(UUID playerUUID, HistoryEntry entry) {
        Deque<HistoryEntry> dq = HISTORY.computeIfAbsent(playerUUID, k -> new ArrayDeque<>());
        synchronized (dq) {
            dq.addFirst(entry);
            while (dq.size() > HISTORY_MAX) dq.removeLast();
        }
    }

    public static List<HistoryEntry> getHistory(UUID playerUUID) {
        Deque<HistoryEntry> dq = HISTORY.get(playerUUID);
        if (dq == null) return Collections.emptyList();
        synchronized (dq) { return new ArrayList<>(dq); }
    }

    /**
     * @param p le joueur expéditeur
     * @return le nombre de ticks restants avant le prochain envoi autorisé, {@code 0} si disponible
     */
    public static long cooldownRemainingTicks(ServerPlayer p) {
        Long last = LAST_SEND.get(p.getUUID());
        if (last == null) return 0;
        long server = p.getServer() == null ? 0 : p.getServer().getTickCount();
        long left = COOLDOWN_TICKS - (server - last);
        return Math.max(0, left);
    }

    public static void markSent(ServerPlayer p) {
        if (p.getServer() != null) LAST_SEND.put(p.getUUID(), (long) p.getServer().getTickCount());
    }

    /** Façade vers {@link ForgerySystem#cooldownRemainingTicks(ServerPlayer)}. */
    public static long forgeCooldownRemainingTicks(ServerPlayer p) {
        return ForgerySystem.cooldownRemainingTicks(p);
    }

    /** Façade vers {@link ForgerySystem#resolveDisplaySender(ServerPlayer, String)}. */
    public static String resolveDisplaySender(ServerPlayer sender, String forgeName) {
        return ForgerySystem.resolveDisplaySender(sender, forgeName);
    }

    public static void setPendingGroup(ServerPlayer p, List<String> names) {
        PENDING_GROUP.put(p.getUUID(), new ArrayList<>(names));
    }

    public static List<String> takePendingGroup(ServerPlayer p) {
        List<String> l = PENDING_GROUP.remove(p.getUUID());
        return l == null ? Collections.emptyList() : l;
    }

    public static List<String> peekPendingGroup(ServerPlayer p) {
        return PENDING_GROUP.getOrDefault(p.getUUID(), Collections.emptyList());
    }

    /** Façade vers {@link LetterRenderer#sealColorFor(String)}. */
    public static ChatFormatting sealColorFor(String name) {
        return LetterRenderer.sealColorFor(name);
    }

    // ============================ API publique ============================

    /**
     * Valide les conditions d'envoi et spawne un corbeau {@code SUMMON} pour ce joueur.
     *
     * <p>Vérifie successivement : MCEF prêt, pas de corbeau d'envoi en cours, cooldown,
     * accès au ciel, et stock de papier. Toute condition non remplie envoie un message
     * d'erreur au joueur et annule le spawn.</p>
     *
     * @param player le joueur qui exécute {@code /corbeau}
     */
    public static void summonForPlayer(ServerPlayer player) {
        if (!ClientReadyState.isReady(player)) {
            player.sendSystemMessage(Component.literal(
                "§c§oLe service du corbeau n'est pas encore prêt. Patiente quelques instants."));
            return;
        }
        // Refus s'il existe déjà un corbeau d'envoi (SUMMON) en cours
        synchronized (BIRDS) {
            for (Bird b : BIRDS) {
                if (b.player == player && b.kind == Kind.SUMMON) {
                    player.sendSystemMessage(Component.literal("§8§oUn corbeau est déjà en route."));
                    return;
                }
            }
        }
        // Cooldown anti-spam
        long cdLeft = cooldownRemainingTicks(player);
        if (cdLeft > 0 && !player.isCreative()) {
            long s = (cdLeft + 19) / 20;
            player.sendSystemMessage(Component.literal(
                "§c§oTes corbeaux ont besoin de souffler — reviens dans §f" + s + "§c secondes."));
            return;
        }
        // Combien de papiers ? 1 par destinataire (groupe) ou 1 par défaut.
        int needed = Math.max(1, peekPendingGroup(player).size());
        if (!player.isCreative()) {
            if (countPaper(player) < needed) {
                player.sendSystemMessage(Component.literal(
                    "§c§oIl te faut §f" + needed + "§c papier(s) pour envoyer cette missive."));
                PENDING_GROUP.remove(player.getUUID()); // on annule le groupe sinon il reste en attente
                return;
            }
            consumePaper(player, needed);
        }

        BlockPos eyeBlock = BlockPos.containing(player.getEyePosition()).above();
        if (!player.level().canSeeSky(eyeBlock)) {
            player.sendSystemMessage(Component.literal(
                "§c§oLe corbeau ne peut pas atteindre le ciel depuis ici."));
            return;
        }

        List<String> recipients = peekPendingGroup(player);
        boolean spawned = spawnSummonBirds(player, recipients);
        if (!spawned) return;
        int count = Math.max(1, recipients.size());
        String msg = count > 1
            ? "§8§oDes battements d'ailes au loin... " + count + " corbeaux approchent."
            : "§8§oUn battement d'ailes au loin... le corbeau approche.";
        player.sendSystemMessage(Component.literal(msg));
    }

    public static void onMessageSent(ServerPlayer player) { startAllOutgoingSummon(player, 400, 80, true); }

    public static void onMessageSent(ServerPlayer player, int fanCount) { onMessageSent(player); }

    public static void onLetterCancelled(ServerPlayer player) { startAllOutgoingSummon(player, 18, 16, false); }

    /**
     * Fait décoller tous les oiseaux SUMMON en attente pour ce joueur.
     *
     * @param useRecipientDir {@code true} → chaque oiseau vole vers son destinataire ;
     *                        {@code false} → direction de repli (annulation)
     */
    private static void startAllOutgoingSummon(ServerPlayer player, int horizontal, int vertical,
                                               boolean useRecipientDir) {
        synchronized (BIRDS) {
            for (Bird b : BIRDS) {
                if (b.player != player || b.kind != Kind.SUMMON || b.phase != Phase.WAITING) continue;
                b.phase = Phase.OUTGOING; b.ticks = 0;
                b.outgoingFrom = b.chicken.position();
                Vec3 fallback = player.getLookAngle().reverse();
                Vec3 dir = useRecipientDir ? departureDir(player, b.recipientName, fallback) : fallback;
                b.flyTo = player.getEyePosition().add(dir.x * horizontal, vertical, dir.z * horizontal);
                if (useRecipientDir) b.chicken.setInvulnerable(false); // interceptable en vol
            }
        }
    }

    /**
     * Direction horizontale normalisée depuis {@code from} vers le joueur nommé {@code recipientName}.
     * Retourne {@code fallback} si le destinataire est introuvable ou dans une autre dimension.
     */
    private static Vec3 departureDir(ServerPlayer from, String recipientName, Vec3 fallback) {
        if (recipientName == null || from.getServer() == null) return fallback;
        ServerPlayer recipient = from.getServer().getPlayerList().getPlayerByName(recipientName);
        if (recipient == null || !recipient.level().dimension().equals(from.level().dimension())) return fallback;
        Vec3 delta = recipient.position().subtract(from.position());
        double hDistSq = delta.x * delta.x + delta.z * delta.z;
        if (hDistSq < 1.0) return fallback;
        double len = Math.sqrt(hDistSq);
        return new Vec3(delta.x / len, 0, delta.z / len);
    }

    /**
     * Calcule le délai de livraison en ticks : 10 s + 1 min par 100 blocs de distance.
     * Sous orage, 50 % de chances que le délai soit doublé.
     *
     * @param sender    l'expéditeur
     * @param recipient le destinataire
     * @return délai en ticks avant que la lettre entre dans la queue du destinataire
     */
    public static long computeDeliveryDelayTicks(ServerPlayer sender, ServerPlayer recipient) {
        double dist = senderDistance(sender, recipient);
        long seconds = 10L + (long) Math.floor(dist / 100.0 * 60.0);
        long ticks = seconds * 20L;
        // Météo : sous orage/pluie forte, 50% de chance que le corbeau "se perde" → delay x2
        Level lvl = sender.level();
        boolean storm = lvl.isThundering() || lvl.isRaining();
        if (storm && sender.getRandom().nextDouble() < 0.5) {
            ticks *= 2;
            sender.sendSystemMessage(Component.literal(
                "§9§oLa pluie battante désoriente le corbeau — sa route sera plus longue."));
        }
        return ticks;
    }

    /**
     * Distance entre deux joueurs. Retourne {@code 5000} s'ils sont dans des dimensions différentes.
     *
     * @return distance en blocs
     */
    public static double senderDistance(ServerPlayer sender, ServerPlayer recipient) {
        if (sender.level().dimension().equals(recipient.level().dimension()))
            return sender.position().distanceTo(recipient.position());
        return 5000;
    }

    public static ServerPlayer findPlayer(MinecraftServer server, ServerPlayer sender, String name) {
        if (name == null || name.isEmpty()) return null;
        // Auto-envoi interdit : ne jamais résoudre le nom de l'expéditeur lui-même.
        if (sender != null) {
            String selfName = sender.getGameProfile().getName();
            if (selfName != null && selfName.equalsIgnoreCase(name)) return null;
            if (name.equals(sender.getStringUUID())) return null;
        }
        for (ServerPlayer sp : server.getPlayerList().getPlayers())
            if (sp.getGameProfile().getName().equalsIgnoreCase(name)) return sp;
        return null;
    }

    /**
     * Programme la livraison d'une lettre après un délai.
     *
     * @param server     le serveur Minecraft
     * @param recipient  le joueur destinataire
     * @param senderName nom affiché de l'expéditeur
     * @param subject    objet de la lettre
     * @param body       corps de la lettre
     * @param delayTicks délai en ticks avant l'entrée en queue
     */
    public static UUID scheduleDelivery(MinecraftServer server, ServerPlayer recipient,
                                        String senderName, String displaySender,
                                        String subject, String body, long delayTicks) {
        return scheduleDeliveryInternal(server, recipient.getUUID(), senderName, displaySender,
            subject, body, delayTicks, false);
    }

    private static UUID scheduleDeliveryInternal(MinecraftServer server, UUID recipientUUID,
                                                 String senderName, String displaySender,
                                                 String subject, String body,
                                                 long delayTicks, boolean isReturn) {
        long deliverAt = server.getTickCount() + delayTicks;
        UUID msgId = UUID.randomUUID();
        synchronized (PENDING) {
            PENDING.add(new PendingDelivery(recipientUUID, msgId, senderName, displaySender,
                subject, body, deliverAt, isReturn));
        }
        persistDirty.accept(null);
        return msgId;
    }

    /**
     * Programme un "pigeon perdu" : la lettre apparaîtra au sol dans un endroit aléatoire
     * autour de l'expéditeur après un délai variable (5–20 min). Le sender ne reçoit aucune
     * confirmation que la lettre a été perdue — c'est volontairement opaque pour le RP.
     *
     * @return le délai estimé en secondes, pour information uniquement (non communiqué au joueur)
     */
    public static long scheduleLostPigeon(ServerPlayer sender, String displaySender,
                                          String subject, String body) {
        var rng = sender.getRandom();
        // Position : entre 800 et 1400 blocs du sender, dans une direction aléatoire
        double angle = rng.nextDouble() * Math.PI * 2.0;
        double dist = 800 + rng.nextDouble() * 600;
        double x = sender.getX() + Math.cos(angle) * dist;
        double z = sender.getZ() + Math.sin(angle) * dist;
        double y = sender.getY(); // Y du sender, faute de mieux — sera ajusté au spawn
        long delayMs = (5L + rng.nextInt(16)) * 60L * 1000L; // 5 à 20 min
        long dropAt = System.currentTimeMillis() + delayMs;
        String dim = sender.level().dimension().location().toString();
        synchronized (LOST_PIGEONS) {
            LOST_PIGEONS.add(new LostPigeon(sender.getGameProfile().getName(), displaySender,
                subject, body, dim, x, y, z, dropAt));
        }
        persistDirty.accept(null);
        return delayMs / 1000L;
    }

    /** Accès lecture-seule aux pigeons perdus pour la couche de persistance. */
    public static List<LostPigeon> snapshotLostPigeons() {
        synchronized (LOST_PIGEONS) { return new ArrayList<>(LOST_PIGEONS); }
    }
    public static List<PendingDelivery> snapshotPending() {
        synchronized (PENDING) { return new ArrayList<>(PENDING); }
    }
    public static void restorePersistedState(List<PendingDelivery> pending, List<LostPigeon> lost) {
        synchronized (PENDING) { PENDING.clear(); PENDING.addAll(pending); }
        synchronized (LOST_PIGEONS) { LOST_PIGEONS.clear(); LOST_PIGEONS.addAll(lost); }
    }
    /** Rebuilds an internal PendingDelivery from persisted fields (used by SavedData on load). */
    public static PendingDelivery createPendingForRestore(UUID recipientUUID, UUID msgId, String sender,
                                                          String displaySender, String subject, String body,
                                                          long deliverAtTick, boolean isReturn) {
        return new PendingDelivery(recipientUUID, msgId, sender, displaySender, subject, body, deliverAtTick, isReturn);
    }

    /**
     * Attribue le contenu de la lettre aux corbeaux SUMMON OUTGOING du joueur,
     * pour permettre l'interception (drop + annulation de livraison si tué).
     *
     * @param player  l'expéditeur
     * @param subject objet de la lettre
     * @param body    corps de la lettre
     * @param ids     IDs des livraisons programmées portées par ces corbeaux
     */
    public static void assignOutgoingLetter(ServerPlayer player, String subject, String body,
                                            List<String> recipientNames, List<UUID> ids, List<Long> delays,
                                            String displaySender) {
        synchronized (BIRDS) {
            for (Bird b : BIRDS) {
                if (b.player != player || b.kind != Kind.SUMMON || b.phase != Phase.OUTGOING) continue;
                if (b.recipientName == null) continue;
                int idx = -1;
                for (int i = 0; i < recipientNames.size(); i++) {
                    if (recipientNames.get(i).equalsIgnoreCase(b.recipientName)) { idx = i; break; }
                }
                if (idx < 0) continue;
                b.outSubject = subject;
                b.outBody = body;
                b.deliveryIds = new ArrayList<>(List.of(ids.get(idx)));
                b.outgoingDurationTicks = delays.get(idx);
                b.displaySender = displaySender;
            }
        }
    }

    private static void cancelDeliveries(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return;
        synchronized (PENDING) {
            PENDING.removeIf(d -> ids.contains(d.msgId));
        }
        // Si le timing a déjà fait passer le message PENDING → QUEUE, le retirer aussi
        // pour qu'aucun corbeau de livraison ne spawne malgré l'annulation.
        for (Deque<QueuedMessage> q : QUEUE.values()) {
            q.removeIf(m -> ids.contains(m.id));
        }
    }

    /**
     * Traite la décision du destinataire (garder ou détruire la lettre).
     *
     * @param player le joueur destinataire
     * @param msgId  l'identifiant du message affiché
     * @param keep   {@code true} pour conserver la lettre en item, {@code false} pour la détruire
     */
    public static void onLetterDecision(ServerPlayer player, UUID msgId, boolean keep) {
        System.out.println("[Corbeau] onLetterDecision player=" + player.getGameProfile().getName()
            + " msgId=" + msgId + " keep=" + keep);
        Bird match = null;
        synchronized (BIRDS) {
            for (Bird b : BIRDS) {
                if (b.kind == Kind.DELIVERY
                        && b.player.getUUID().equals(player.getUUID())
                        && msgId.equals(b.msgId)) {
                    match = b; break;
                }
            }
        }
        if (match == null) {
            System.out.println("[Corbeau] no matching DELIVERY bird for decision");
            return;
        }
        // Déjà traité (spam de clics) — ignorer
        if (match.phase == Phase.OUTGOING) return;

        if (keep) {
            String shown = (match.displaySender != null && !match.displaySender.isBlank())
                ? match.displaySender : match.sender;
            giveLetterItem(player, shown, match.subject, match.body);
            player.sendSystemMessage(Component.literal("§8§oTu glisses la lettre dans ta poche."));
        } else {
            player.sendSystemMessage(Component.literal("§8§oTu confies la lettre au corbeau, qui l'emporte au feu."));
        }
        // Le corbeau s'en va
        match.phase = Phase.OUTGOING;
        match.ticks = 0;
        Vec3 dir = player.getLookAngle().reverse();
        match.flyTo = player.getEyePosition().add(dir.x * 26, 24, dir.z * 26);
    }

    // ============================ Spawn ============================

    /** Candidats de remplacement, par ordre de préférence. Premier trouvé = utilisé. */
    private static final ResourceLocation[] MESSENGER_CANDIDATES = new ResourceLocation[] {
        ResourceLocation.fromNamespaceAndPath("naturalist", "sparrow"),   // priorité : moineau (Naturalist)
        ResourceLocation.fromNamespaceAndPath("naturalist", "finch"),
        ResourceLocation.fromNamespaceAndPath("naturalist", "robin"),
        ResourceLocation.fromNamespaceAndPath("naturalist", "bluejay")
    };

    @SuppressWarnings("unchecked")
    private static EntityType<? extends Mob> pickMessengerType() {
        for (ResourceLocation id : MESSENGER_CANDIDATES) {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
            // getValue retourne le "default" (généralement pig) si absent — comparer à pig pour filtrer
            if (type != null && type != EntityType.PIG) {
                System.out.println("[Corbeau] Utilise entité messager : " + id);
                return (EntityType<? extends Mob>) type;
            }
        }
        System.out.println("[Corbeau] Aucun oiseau mod trouvé, fallback vanilla chicken.");
        return EntityType.CHICKEN;
    }

    /**
     * Spawne N oiseaux SUMMON en éventail autour du joueur (un par destinataire).
     * L'oiseau central ({@code isMain=true}) ouvrira l'UI ; les autres hovèrent en attente.
     *
     * @return {@code true} si au moins un oiseau a été spawné
     */
    private static boolean spawnSummonBirds(ServerPlayer player, List<String> recipients) {
        int count = Math.max(1, recipients.size());
        Vec3 look = player.getLookAngle();
        double baseAngle = (new Vec3(look.x, 0, look.z).lengthSqr() < 1e-4)
            ? player.getRandom().nextDouble() * Math.PI * 2.0
            : Math.atan2(look.z, look.x);

        boolean anySpawned = false;
        for (int i = 0; i < count; i++) {
            double spread = (i == 0) ? 0 : ((i % 2 == 0) ? -1 : 1) * (Math.PI / 7) * ((i + 1) / 2);
            Bird b = spawnBirdAtAngle(player, Kind.SUMMON, baseAngle + spread);
            if (b == null) continue;
            b.isMain = (i == 0);
            b.recipientName = (i < recipients.size()) ? recipients.get(i) : null;
            anySpawned = true;
        }
        return anySpawned;
    }

    /** Spawne un oiseau de livraison depuis une direction aléatoire. */
    private static Bird spawnBird(ServerPlayer player, Kind kind) {
        double angle = player.getRandom().nextDouble() * Math.PI * 2.0;
        return spawnBirdAtAngle(player, kind, angle);
    }

    /**
     * Spawne un oiseau depuis la position calculée à partir de {@code angle} horizontal.
     * Le point de spawn est à 40 blocs dans cette direction et 35 blocs au-dessus du joueur.
     */
    private static Bird spawnBirdAtAngle(ServerPlayer player, Kind kind, double angle) {
        ServerLevel level = player.serverLevel();
        Vec3 eye = player.getEyePosition();
        Vec3 dir = new Vec3(Math.cos(angle), 0, Math.sin(angle));
        Vec3 spawn = eye.add(dir.scale(40)).add(0, 35, 0);

        EntityType<? extends Mob> type = pickMessengerType();
        Mob bird;
        try {
            bird = type.create(level);
        } catch (Throwable t) {
            System.err.println("[Corbeau] Échec création " + type + " — fallback chicken : " + t);
            bird = EntityType.CHICKEN.create(level);
        }
        if (bird == null) return null;
        bird.setPos(spawn.x, spawn.y, spawn.z);
        bird.setNoAi(true);
        bird.setSilent(true);
        // DELIVERY = tuable (RP : interceptable à l'arc). SUMMON = invulnérable.
        bird.setInvulnerable(kind != Kind.DELIVERY);
        bird.setNoGravity(true);
        bird.setCustomNameVisible(false);
        level.addFreshEntity(bird);
        Bird b = new Bird(kind, bird, player, spawn);
        synchronized (BIRDS) { BIRDS.add(b); }
        return b;
    }

    private static boolean hasActiveDeliveryFor(ServerPlayer p) {
        synchronized (BIRDS) {
            for (Bird b : BIRDS) {
                if (b.kind == Kind.DELIVERY && b.player == p) return true;
                // Un SUMMON OUTGOING porteur vers ce destinataire compte aussi —
                // il livrera en place à l'arrivée, pas besoin d'en spawner un nouveau.
                if (b.kind == Kind.SUMMON && b.phase == Phase.OUTGOING
                        && b.outSubject != null && b.recipientName != null
                        && b.recipientName.equalsIgnoreCase(p.getGameProfile().getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ============================ Tick principal ============================

    /** Au démarrage du serveur, attacher la couche de persistance (PENDING + LOST_PIGEONS). */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        try {
            ServerLevel overworld = event.getServer().overworld();
            if (overworld != null) CorbeauSavedData.attach(overworld);
        } catch (Throwable t) {
            System.err.println("[Corbeau] Impossible d'attacher la persistance : " + t);
        }
    }

    /** Corbeau abattu en plein vol : la lettre tombe au sol pour qui la trouve. */
    @SubscribeEvent
    public static void onBirdDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        Bird match;
        synchronized (BIRDS) {
            match = null;
            for (Bird b : BIRDS) if (b.chicken == mob) { match = b; break; }
        }
        if (match == null) return;
        // RP : intercepter un corbeau est totalement silencieux. Personne n'est notifié —
        // ni l'expéditeur, ni le destinataire, ni le tueur. Seule la lettre qui tombe au sol
        // dit ce qui s'est passé — au tueur de la ramasser pour découvrir.
        if (match.kind == Kind.DELIVERY && match.phase != Phase.OUTGOING) {
            String shownSender = (match.displaySender != null && !match.displaySender.isBlank())
                ? match.displaySender : match.sender;
            if (mob.level() instanceof ServerLevel sl) {
                ItemStack letter = makeLetterItem(shownSender, match.subject, match.body);
                Vec3 p = mob.position();
                ItemEntity drop = new ItemEntity(sl, p.x, p.y + 0.4, p.z, letter);
                drop.setDeltaMovement(0, 0.1, 0);
                sl.addFreshEntity(drop);
                sl.sendParticles(ParticleTypes.POOF, p.x, p.y + 0.5, p.z, 14, 0.4, 0.3, 0.4, 0.03);
                sl.sendParticles(ParticleTypes.ASH,  p.x, p.y + 0.5, p.z, 18, 0.5, 0.4, 0.5, 0.04);
            }
        } else if (match.kind == Kind.SUMMON && match.phase == Phase.OUTGOING && match.outSubject != null) {
            String shownSender = (match.displaySender != null && !match.displaySender.isBlank())
                ? match.displaySender : match.player.getGameProfile().getName();
            if (mob.level() instanceof ServerLevel sl) {
                ItemStack letter = makeLetterItem(shownSender, match.outSubject, match.outBody);
                Vec3 p = mob.position();
                ItemEntity drop = new ItemEntity(sl, p.x, p.y + 0.4, p.z, letter);
                drop.setDeltaMovement(0, 0.1, 0);
                sl.addFreshEntity(drop);
                sl.sendParticles(ParticleTypes.POOF, p.x, p.y + 0.5, p.z, 14, 0.4, 0.3, 0.4, 0.03);
                sl.sendParticles(ParticleTypes.ASH,  p.x, p.y + 0.5, p.z, 18, 0.5, 0.4, 0.5, 0.04);
            }
            cancelDeliveries(match.deliveryIds);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server == null) return;

        // 1) Livraisons temporelles → entrent dans la queue du joueur
        long now = server.getTickCount();
        List<PendingDelivery> done = new ArrayList<>();
        synchronized (PENDING) {
            for (PendingDelivery d : PENDING) {
                if (now >= d.deliverAtTick) {
                    QUEUE.computeIfAbsent(d.recipientUUID, k -> new ArrayDeque<>())
                         .addLast(new QueuedMessage(d.msgId, d.sender, d.displaySender, d.subject, d.body, d.isReturn));
                    done.add(d);
                }
            }
            PENDING.removeAll(done);
        }

        // 2) Dispatcher : pour chaque joueur en ligne avec une queue, si pas de delivery active, spawn
        for (Map.Entry<UUID, Deque<QueuedMessage>> e : QUEUE.entrySet()) {
            Deque<QueuedMessage> q = e.getValue();
            if (q == null || q.isEmpty()) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null || p.isRemoved()) continue;
            if (hasActiveDeliveryFor(p)) continue;
            QueuedMessage msg = q.pollFirst();
            if (msg == null) continue;
            spawnDeliveryBird(p, msg);
        }

        // 3) Tick des oiseaux
        List<Bird> toRemove = new ArrayList<>();
        synchronized (BIRDS) {
            for (Bird b : BIRDS) {
                if (b.chicken == null || b.chicken.isRemoved() || b.player.isRemoved()) {
                    if (b.chicken != null && !b.chicken.isRemoved()) b.chicken.discard();
                    toRemove.add(b); continue;
                }
                b.ticks++;
                tickBird(b, toRemove);
            }
            BIRDS.removeAll(toRemove);
        }

        // 4) Pigeons perdus : à l'instant programmé, la lettre tombe dans le monde
        long nowMs = System.currentTimeMillis();
        List<LostPigeon> hatched = new ArrayList<>();
        synchronized (LOST_PIGEONS) {
            for (LostPigeon lp : LOST_PIGEONS) {
                if (nowMs >= lp.dropAtMillis) hatched.add(lp);
            }
            LOST_PIGEONS.removeAll(hatched);
        }
        for (LostPigeon lp : hatched) dropLostPigeonInWorld(server, lp);
        if (!hatched.isEmpty()) persistDirty.accept(null);
    }

    /** Fait apparaître l'item lettre d'un pigeon perdu à sa position planifiée. */
    private static void dropLostPigeonInWorld(MinecraftServer server, LostPigeon lp) {
        ServerLevel sl = null;
        for (ServerLevel candidate : server.getAllLevels()) {
            if (candidate.dimension().location().toString().equals(lp.dimensionKey)) { sl = candidate; break; }
        }
        if (sl == null) return; // dimension supprimée entre-temps, on abandonne silencieusement
        // Trouver une hauteur de sol valide à (x, z) — le Y stocké est juste un fallback
        int bx = (int) Math.floor(lp.x);
        int bz = (int) Math.floor(lp.z);
        int by = sl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx, bz);
        double spawnY = by > 0 ? by + 0.5 : lp.y;
        ItemStack letter = makeLetterItem(lp.displaySender, lp.subject, lp.body);
        ItemEntity drop = new ItemEntity(sl, lp.x, spawnY, lp.z, letter);
        drop.setDeltaMovement(0, 0.05, 0);
        drop.setPickUpDelay(0);
        // L'item ne disparaît jamais — il attend qu'un joueur le trouve
        drop.setUnlimitedLifetime();
        sl.addFreshEntity(drop);
        // Particules discrètes pour signaler l'apparition (peu visible à distance, c'est voulu)
        sl.sendParticles(ParticleTypes.POOF, lp.x, spawnY + 0.4, lp.z, 8, 0.3, 0.2, 0.3, 0.01);
    }

    private static void spawnDeliveryBird(ServerPlayer player, QueuedMessage msg) {
        Bird b = spawnBird(player, Kind.DELIVERY);
        if (b == null) {
            // si on n'a pas pu spawn, on remet le message dans la queue
            QUEUE.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>()).addFirst(msg);
            return;
        }
        b.msgId = msg.id;
        b.sender = msg.sender;
        b.displaySender = msg.displaySender;
        b.subject = msg.subject;
        b.body = msg.body;
        b.isReturn = msg.isReturn;
        player.sendSystemMessage(Component.literal(msg.isReturn
            ? "§8§oUn corbeau revient des cieux — il rapporte une lettre que tu avais envoyée..."
            : "§8§oUn corbeau approche, une lettre attachée à la patte..."));
    }

    // ============================ Tick d'un oiseau ============================

    private static void tickBird(Bird b, List<Bird> toRemove) {
        switch (b.phase) {
            case INCOMING -> tickIncoming(b);
            case WAITING  -> tickWaiting(b);
            case OUTGOING -> {
                if (b.ticks % 6 == 0) {
                    b.chicken.level().playSound(null, b.chicken.blockPosition(),
                        SoundEvents.PARROT_FLY, SoundSource.NEUTRAL, 0.5f, 0.6f);
                }
                spawnFeatherTrail(b.chicken);

                boolean carryingLetter = b.outSubject != null
                    && b.outgoingDurationTicks > 0 && b.recipientName != null
                    && (b.kind == Kind.SUMMON || b.isReturn);

                if (carryingLetter) {
                    // Vol garanti sur toute la durée : montée vers la couche de croisière,
                    // croisière horizontale tranquille, descente sur le destinataire vivant.
                    // L'oiseau reste interceptable et ne disparaît qu'à l'instant pile de livraison.
                    MinecraftServer server = b.player.getServer();
                    ServerPlayer recipient = (server == null)
                        ? null : server.getPlayerList().getPlayerByName(b.recipientName);
                    Vec3 from = b.outgoingFrom != null ? b.outgoingFrom : b.chicken.position();
                    double targetX, targetZ, targetY;
                    if (recipient != null && !recipient.isRemoved()
                            && recipient.level().dimension().equals(b.chicken.level().dimension())) {
                        targetX = recipient.getX();
                        targetZ = recipient.getZ();
                        targetY = recipient.getY() + 1.6;
                    } else {
                        targetX = b.flyTo.x;
                        targetZ = b.flyTo.z;
                        targetY = b.flyTo.y;
                    }
                    double cruiseY = Math.max(100.0, Math.max(from.y, targetY) + 18.0);
                    long total = Math.max(20L, b.outgoingDurationTicks);
                    double progress = Math.min(1.0, b.ticks / (double) total);
                    double kxz = easeInOut(progress);
                    double desiredX = from.x + (targetX - from.x) * kxz;
                    double desiredZ = from.z + (targetZ - from.z) * kxz;
                    double desiredY;
                    if (progress < 0.20) {
                        double k = progress / 0.20;
                        desiredY = from.y + (cruiseY - from.y) * easeOutCubic(k);
                    } else if (progress > 0.80) {
                        double k = (progress - 0.80) / 0.20;
                        desiredY = cruiseY + (targetY - cruiseY) * easeOutCubic(k);
                    } else {
                        desiredY = cruiseY + Math.sin(b.ticks * 0.12) * 0.35;
                    }
                    Vec3 cur = b.chicken.position();
                    Vec3 desired = new Vec3(desiredX, desiredY, desiredZ);
                    Vec3 step = desired.subtract(cur);
                    double stepLen = step.length();
                    if (stepLen > 2.8) step = step.normalize().scale(2.8);
                    Vec3 newPos = cur.add(step);
                    Vec3 yawStep = new Vec3(desired.x - cur.x, 0, desired.z - cur.z);
                    float yaw = yawStep.lengthSqr() > 0.01 ? computeYaw(yawStep) : b.chicken.getYRot();
                    b.chicken.moveTo(newPos.x, newPos.y, newPos.z, yaw, -10f);
                    if (b.ticks >= b.outgoingDurationTicks) {
                        arriveCarrying(b, recipient, toRemove);
                    }
                } else {
                    // OUTGOING simple (annulation / sans lettre) — vol bref de repli.
                    if (moveTowardWithArc(b.chicken, b.flyTo, 1.2) || b.ticks > 20 * 90) {
                        poof(b.chicken);
                        b.chicken.discard();
                        toRemove.add(b);
                    }
                }
            }
        }
    }

    /**
     * Conversion en place à l'arrivée d'un corbeau porteur (SUMMON livrant au destinataire, ou
     * DELIVERY de retour livrant à l'expéditeur initial). Le même oiseau reste en jeu et
     * passe en WAITING auprès du nouvel interlocuteur — pas de poof, pas de bird dupliqué.
     */
    private static void arriveCarrying(Bird b, ServerPlayer recipient, List<Bird> toRemove) {
        boolean wasReturn = b.isReturn;
        String senderName = wasReturn ? b.sender : b.player.getGameProfile().getName();
        UUID msgId = (b.deliveryIds != null && !b.deliveryIds.isEmpty()) ? b.deliveryIds.get(0)
                   : (b.msgId != null ? b.msgId : UUID.randomUUID());
        String subject = b.outSubject;
        String body = b.outBody;

        // Si le destinataire n'est pas joignable : on laisse le système PENDING/QUEUE faire le fallback.
        if (recipient == null || recipient.isRemoved()
                || !recipient.level().dimension().equals(b.chicken.level().dimension())) {
            poof(b.chicken);
            b.chicken.discard();
            toRemove.add(b);
            return;
        }

        // Le bird arrivé prend en charge la livraison — on annule l'éventuel PENDING/QUEUE doublon.
        if (b.deliveryIds != null && !b.deliveryIds.isEmpty()) {
            cancelDeliveries(b.deliveryIds);
            Deque<QueuedMessage> q = QUEUE.get(recipient.getUUID());
            if (q != null) q.removeIf(m -> b.deliveryIds.contains(m.id));
        }

        b.kind = Kind.DELIVERY;
        b.player = recipient;
        b.phase = Phase.WAITING;
        b.ticks = 0;
        b.msgId = msgId;
        b.sender = senderName;
        b.subject = subject;
        b.body = body;
        b.isReturn = wasReturn;
        b.outSubject = null;
        b.outBody = null;
        b.outgoingDurationTicks = 0;
        b.deliveryIds = null;
        b.chicken.setInvulnerable(false);
        b.chicken.setDeltaMovement(Vec3.ZERO);

        recipient.sendSystemMessage(Component.literal(wasReturn
            ? "§8§oTon corbeau revient — il rapporte la lettre que tu avais envoyée..."
            : "§8§oUn corbeau se pose près de toi, une lettre attachée à la patte..."));
        onArrived(b);
    }

    private static void tickIncoming(Bird b) {
        Vec3 target = b.player.getEyePosition().add(b.player.getLookAngle().scale(2.2)).add(0, -0.1, 0);
        // 40 blocs de distance pour les deux types → 300 ticks (15s) pour une vitesse cohérente
        int durationTicks = 300;
        double progress = Math.min(1.0, b.ticks / (double) durationTicks);
        double eased = easeOutCubic(progress);
        Vec3 cur = b.chicken.position();
        Vec3 desired = b.origin.add(target.subtract(b.origin).scale(eased))
                               .add(0, Math.sin(progress * Math.PI) * -2.5, 0);
        Vec3 step = desired.subtract(cur);
        if (step.length() > 0.7) step = step.normalize().scale(0.7);
        Vec3 newPos = cur.add(step);
        b.chicken.moveTo(newPos.x, newPos.y, newPos.z, computeYaw(step), -10f);
        spawnFeatherTrail(b.chicken);

        if (cur.distanceTo(target) < 1.2 || progress >= 1.0) {
            b.phase = Phase.WAITING;
            b.ticks = 0;
            b.chicken.setDeltaMovement(Vec3.ZERO);
            onArrived(b);
        }
        if (b.ticks % 7 == 0) {
            b.chicken.level().playSound(null, b.chicken.blockPosition(),
                SoundEvents.PARROT_FLY, SoundSource.NEUTRAL, 0.55f, 0.55f);
        }

        // Interception ambiante : tout joueur tiers à <32 blocs entend & voit passer le corbeau
        if (b.kind == Kind.DELIVERY && b.ticks % 18 == 0 && b.chicken.level() instanceof ServerLevel sl) {
            Vec3 bp = b.chicken.position();
            for (ServerPlayer other : sl.getPlayers(sp -> sp != b.player
                    && sp.position().distanceToSqr(bp) <= INTERCEPT_RADIUS * INTERCEPT_RADIUS)) {
                Long lastPing = LAST_INTERCEPT_PING.get(other.getUUID());
                long nowTime = sl.getGameTime();
                if (lastPing != null && nowTime - lastPing < 30) continue;
                LAST_INTERCEPT_PING.put(other.getUUID(), nowTime);
                sl.playSound(null, other.blockPosition(),
                    SoundEvents.PARROT_FLY, SoundSource.NEUTRAL, 0.8f, 0.7f);
                sl.sendParticles(other, ParticleTypes.ASH, false,
                    bp.x, bp.y + 0.4, bp.z, 6, 0.5, 0.4, 0.5, 0.02);
            }
        }
    }

    /** Côté serveur : action à l'arrivée selon le type. */
    private static void onArrived(Bird b) {
        // effet d'arrivée discret
        if (b.chicken.level() instanceof ServerLevel sl) {
            Vec3 p = b.chicken.position().add(0, 0.4, 0);
            sl.sendParticles(ParticleTypes.CLOUD, p.x, p.y, p.z, 6, 0.25, 0.15, 0.25, 0.01);
            sl.sendParticles(ParticleTypes.ASH,   p.x, p.y, p.z, 10, 0.3, 0.2, 0.3, 0.0);
            sl.playSound(null, b.chicken.blockPosition(),
                SoundEvents.BAT_TAKEOFF, SoundSource.NEUTRAL, 0.4f, 0.6f);
        }

        if (b.kind == Kind.SUMMON) {
            // Seul l'oiseau principal ouvre l'UI — les autres hovèrent en silence
            if (b.isMain) {
                String recipients = String.join(", ", peekPendingGroup(b.player));
                int forgeCdSec = (int) (forgeCooldownRemainingTicks(b.player) / 20L);
                NetworkHandler.sendToClient(new PacketOpenCompose(recipients, forgeCdSec), b.player);
            }
        } else { // DELIVERY → décision via chat clickable uniquement
            // Historique destinataire : enregistrement à l'arrivée (pour les retours AFK, on n'enregistre pas un doublon)
            if (!b.isReturn) {
                String shown = (b.displaySender != null && !b.displaySender.isBlank()) ? b.displaySender : b.sender;
                recordHistory(b.player.getUUID(), new HistoryEntry(
                    System.currentTimeMillis(), false, shown, b.subject, false, false));
            }
            showLetterInChat(b);
        }
    }

    /** Affiche la lettre dans le chat avec deux boutons cliquables vanilla. */
    private static void showLetterInChat(Bird b) {
        ServerPlayer p = b.player;
        String cmdBase = "/corbeau-choice " + b.msgId + " ";

        // Si l'usurpation a réussi, le destinataire voit le pseudo usurpé (sceau et nom).
        String shownSender = (b.displaySender != null && !b.displaySender.isBlank()) ? b.displaySender : b.sender;
        ChatFormatting seal = sealColorFor(shownSender);
        String sealChar = "§" + seal.getChar() + "● ";
        Component header = b.isReturn
            ? Component.literal("§6───────── §c§l↩ §6§lLettre retournée §6─────────")
            : Component.literal("§6───────── §e§l✉ §6§lUne lettre §6─────────");
        String metaPrefix = b.isReturn ? "§7Ta lettre §7· §f§o" : "§7De §b" + shownSender + " §7· §f§o";
        Component meta = Component.literal(sealChar + metaPrefix + b.subject);

        MutableJoin actions = new MutableJoin();
        actions.add(Component.literal("§a§l[ Garder la lettre ]")
            .copy()
            .withStyle(s -> s
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmdBase + "keep"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.literal("§fGlisse la lettre dans ta besace — elle y restera, froissée de voyage.")))));
        actions.add(Component.literal("   "));
        actions.add(Component.literal("§c§l[ La détruire ]")
            .copy()
            .withStyle(s -> s
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmdBase + "destroy"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.literal("§fLe corbeau emporte la lettre au loin.")))));

        p.sendSystemMessage(Component.literal(""));
        p.sendSystemMessage(header);
        p.sendSystemMessage(meta);
        p.sendSystemMessage(Component.literal(""));
        // Le corps peut contenir des sauts de ligne — on l'envoie tel quel
        for (String line : b.body.split("\n")) {
            p.sendSystemMessage(Component.literal("§7§o“" + line + "”"));
        }
        p.sendSystemMessage(Component.literal(""));
        p.sendSystemMessage(actions.build());
        p.sendSystemMessage(Component.literal("§6─────────────────────────────"));
    }

    /** Helper pour concaténer des Components mutable. */
    private static final class MutableJoin {
        private final net.minecraft.network.chat.MutableComponent root = Component.literal("");
        void add(Component c) { root.append(c); }
        Component build() { return root; }
    }

    private static void tickWaiting(Bird b) {
        // Décalage latéral léger pour séparer visuellement les oiseaux en groupe
        Vec3 look = b.player.getLookAngle();
        Vec3 right = new Vec3(-look.z, 0, look.x).normalize();
        float slot = b.recipientName != null ? (Math.abs(b.recipientName.hashCode()) % 5) - 2 : 0;
        Vec3 hover = b.player.getEyePosition()
            .add(look.scale(2.2))
            .add(right.scale(slot * 0.55));
        double off = Math.sin(b.ticks * 0.14) * 0.09;
        b.chicken.moveTo(hover.x, hover.y + off, hover.z, b.chicken.getYRot(), 0);
        Vec3 lookVec = b.player.position().subtract(b.chicken.position());
        float yaw = (float)(Math.atan2(lookVec.z, lookVec.x) * 180.0 / Math.PI) - 90f;
        b.chicken.setYRot(yaw); b.chicken.setYHeadRot(yaw);

        if (b.ticks % 48 == 0) {
            b.chicken.level().playSound(null, b.chicken.blockPosition(),
                SoundEvents.PARROT_FLY, SoundSource.NEUTRAL, 0.25f, 0.45f);
        }
        if (b.ticks % 12 == 0) spawnFeatherTrail(b.chicken);

        // Timeout DELIVERY 15 s / SUMMON 10 min (filet de sécurité zombie-bird uniquement)
        int timeoutTicks = b.kind == Kind.DELIVERY ? 20 * 15 : 20 * 60 * 10;
        if (b.ticks > timeoutTicks) {
            if (b.kind == Kind.DELIVERY && !b.isReturn) {
                // AFK : demi-tour en place — le même corbeau reprend son vol vers l'expéditeur initial.
                MinecraftServer server = b.player.getServer();
                ServerPlayer originalSender = (server == null) ? null
                    : server.getPlayerList().getPlayerByName(b.sender);
                if (originalSender != null && !originalSender.isRemoved()
                        && originalSender.level().dimension().equals(b.chicken.level().dimension())) {
                    String recipientName = b.player.getGameProfile().getName();
                    b.player.sendSystemMessage(Component.literal(
                        "§8§oLasse d'attendre, le corbeau s'envole — il rapporte la lettre à son expéditeur."));
                    String origSubject = b.subject;
                    String origBody = b.body;
                    String origSenderName = b.sender;
                    b.player = originalSender;
                    b.recipientName = origSenderName;
                    b.sender = recipientName;
                    b.outSubject = origSubject;
                    b.outBody = origBody;
                    b.isReturn = true;
                    long delayTicks = Math.max(20L * 5L,
                        computeDeliveryDelayTicks(b.player, originalSender));
                    b.outgoingDurationTicks = delayTicks;
                    b.outgoingFrom = b.chicken.position();
                    b.deliveryIds = null;
                    b.phase = Phase.OUTGOING;
                    b.ticks = 0;
                    Vec3 dir = originalSender.position().subtract(b.chicken.position());
                    if (dir.lengthSqr() < 1e-3) dir = originalSender.getLookAngle();
                    b.flyTo = originalSender.position().add(0, 1.6, 0);
                    return;
                }
                // Expéditeur introuvable — la lettre tombe au sol devant le destinataire.
                if (b.player.level() instanceof ServerLevel sl) {
                    String shown = (b.displaySender != null && !b.displaySender.isBlank())
                        ? b.displaySender : b.sender;
                    Vec3 dropPos = b.chicken.position().add(0, -0.5, 0);
                    ItemStack returned = makeLetterItem(shown, b.subject, b.body);
                    ItemEntity drop = new ItemEntity(sl, dropPos.x, dropPos.y, dropPos.z, returned);
                    drop.setDeltaMovement(0, 0.05, 0);
                    drop.setPickUpDelay(60);
                    sl.addFreshEntity(drop);
                }
                b.player.sendSystemMessage(Component.literal(
                    "§8§oLa lettre a glissé du bec du corbeau — elle est tombée à terre devant toi."));
            } else if (b.kind == Kind.DELIVERY && b.isReturn) {
                // Retour ignoré par l'expéditeur — la lettre tombe à ses pieds.
                if (b.player.level() instanceof ServerLevel sl) {
                    String shown = (b.displaySender != null && !b.displaySender.isBlank())
                        ? b.displaySender : b.sender;
                    Vec3 dropPos = b.chicken.position().add(0, -0.5, 0);
                    ItemStack returned = makeLetterItem(shown, b.subject, b.body);
                    ItemEntity drop = new ItemEntity(sl, dropPos.x, dropPos.y, dropPos.z, returned);
                    drop.setDeltaMovement(0, 0.05, 0);
                    drop.setPickUpDelay(60);
                    sl.addFreshEntity(drop);
                }
                b.player.sendSystemMessage(Component.literal(
                    "§8§oLa lettre a glissé du bec du corbeau — elle est tombée à terre devant toi."));
            } else {
                b.player.sendSystemMessage(Component.literal(
                    "§8§oLasse d'attendre, le corbeau s'envole avec sa lettre."));
            }
            b.phase = Phase.OUTGOING; b.ticks = 0;
            Vec3 dir = b.player.getLookAngle().reverse();
            b.flyTo = hover.add(dir.x * 20, 22, dir.z * 20);
        }
    }

    // ============================ Item papier (garder) ============================

    /** Façade vers {@link LetterRenderer#giveLetterItem}. */
    private static void giveLetterItem(ServerPlayer player, String sender, String subject, String body) {
        LetterRenderer.giveLetterItem(player, sender, subject, body);
    }

    /** Façade vers {@link LetterRenderer#makeLetterItem}. */
    private static ItemStack makeLetterItem(String sender, String subject, String body) {
        return LetterRenderer.makeLetterItem(sender, subject, body);
    }

    // ============================ Effets visuels ============================

    private static void spawnFeatherTrail(Mob c) {
        if (!(c.level() instanceof ServerLevel sl)) return;
        Vec3 p = c.position().add(0, 0.4, 0);
        sl.sendParticles(ParticleTypes.POOF, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.0);
        if (c.getRandom().nextInt(3) == 0)
            sl.sendParticles(ParticleTypes.ASH, p.x, p.y, p.z, 1, 0.08, 0.08, 0.08, 0.0);
    }

    private static void poof(Mob c) {
        if (!(c.level() instanceof ServerLevel sl)) return;
        Vec3 p = c.position().add(0, 0.5, 0);
        sl.sendParticles(ParticleTypes.POOF,        p.x, p.y, p.z, 28, 0.45, 0.35, 0.45, 0.04);
        sl.sendParticles(ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, 14, 0.4,  0.3,  0.4,  0.02);
        sl.sendParticles(ParticleTypes.ASH,         p.x, p.y, p.z, 22, 0.55, 0.55, 0.55, 0.05);
        sl.sendParticles(ParticleTypes.END_ROD,     p.x, p.y, p.z, 6,  0.2,  0.2,  0.2,  0.05);
        sl.playSound(null, c.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 0.7f, 1.45f);
        sl.playSound(null, c.blockPosition(), SoundEvents.PHANTOM_FLAP,      SoundSource.NEUTRAL, 0.9f, 0.7f);
    }

    // ============================ Math/move ============================

    private static double easeOutCubic(double t) { double i = 1 - t; return 1 - i * i * i; }

    private static double easeInOut(double t) {
        return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
    }

    private static float computeYaw(Vec3 step) {
        if (step.lengthSqr() < 1e-6) return 0;
        return (float)(Math.atan2(step.z, step.x) * 180.0 / Math.PI) - 90f;
    }

    private static boolean moveTowardWithArc(Mob c, Vec3 target, double speed) {
        Vec3 cur = c.position();
        Vec3 delta = target.subtract(cur);
        double dist = delta.length();
        if (dist < 1.0) return true;
        Vec3 step = delta.normalize().scale(Math.min(speed, dist));
        double lift = Math.sin(c.tickCount * 0.4) * 0.04;
        c.moveTo(cur.x + step.x, cur.y + step.y + lift, cur.z + step.z, computeYaw(step), -20f);
        return false;
    }

    public static int countPaper(ServerPlayer p) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && st.getItem() == Items.PAPER && !st.hasTag()) n += st.getCount();
        }
        return n;
    }

    private static void consumePaper(ServerPlayer p, int amount) {
        var inv = p.getInventory();
        int left = amount;
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty() || st.getItem() != Items.PAPER || st.hasTag()) continue;
            int take = Math.min(st.getCount(), left);
            st.shrink(take);
            left -= take;
            if (st.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
        }
    }
}
