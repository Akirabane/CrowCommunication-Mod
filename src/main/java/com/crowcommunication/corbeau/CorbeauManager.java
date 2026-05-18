package com.crowcommunication.corbeau;

import com.crowcommunication.network.NetworkHandler;
import com.crowcommunication.network.PacketOpenCompose;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Système de courrier par corbeau — version "livraison physique".
 *
 *  Envoi
 *    /corbeau  → corbeau d'envoi (kind SUMMON) descend chez l'expéditeur, attente, départ
 *    Submit    → message rangé en file pour le destinataire après délai (10s + dist)
 *
 *  Livraison
 *    Quand l'heure arrive, le message est mis dans la queue du destinataire
 *    Tant qu'il y a quelque chose dans la queue ET aucun corbeau de livraison actif pour ce joueur,
 *    le manager spawn un corbeau de livraison (kind DELIVERY) qui descend, ouvre la lettre,
 *    attend la décision puis repart. Le suivant prend la suite.
 *
 *  Décision
 *    Garder    → un item papier custom avec lore = corps de la lettre va dans l'inventaire
 *    Détruire  → simplement retiré, le corbeau l'emporte
 */
public class CorbeauManager {

    // ============================ Types ============================

    private enum Kind { SUMMON, DELIVERY }
    private enum Phase { INCOMING, WAITING, OUTGOING }

    private static final class Bird {
        final Kind kind;
        final Mob chicken;
        final ServerPlayer player;
        final Vec3 origin;
        Phase phase;
        int ticks;
        Vec3 flyTo;
        // pour DELIVERY :
        UUID msgId;
        String sender, subject, body;

        Bird(Kind k, Mob c, ServerPlayer p, Vec3 origin) {
            this.kind = k; this.chicken = c; this.player = p; this.origin = origin;
            this.phase = Phase.INCOMING; this.ticks = 0;
        }
    }

    private static final List<Bird> BIRDS = new ArrayList<>();

    /** Une livraison programmée temporellement (en cours de "vol" virtuel jusqu'au destinataire). */
    private static final class PendingDelivery {
        final UUID recipientUUID;
        final UUID msgId;
        final String sender, subject, body;
        final long deliverAtTick;
        PendingDelivery(UUID r, UUID id, String s, String su, String b, long t) {
            this.recipientUUID = r; this.msgId = id; this.sender = s; this.subject = su; this.body = b;
            this.deliverAtTick = t;
        }
    }
    private static final List<PendingDelivery> PENDING = new ArrayList<>();

    /** Message arrivé en attente d'être physiquement livré (queue par joueur). */
    public static final class QueuedMessage {
        public final UUID id;
        public final String sender, subject, body;
        public QueuedMessage(UUID id, String s, String su, String b) {
            this.id = id; this.sender = s; this.subject = su; this.body = b;
        }
    }
    private static final Map<UUID, Deque<QueuedMessage>> QUEUE = new ConcurrentHashMap<>();

    // ============================ Économie & règles ============================

    public static final long COOLDOWN_TICKS = 20L * 120L;          // 2 min entre deux envois
    public static final double MAX_DELIVERY_DISTANCE = 1500.0;     // au-delà : "le corbeau s'est perdu"
    public static final double INTERCEPT_RADIUS = 32.0;            // joueurs tiers qui entendent passer

    private static final Map<UUID, Long> LAST_SEND = new ConcurrentHashMap<>();
    /** Liste de destinataires en attente pour le prochain envoi d'un joueur (après /corbeau-groupe). */
    private static final Map<UUID, List<String>> PENDING_GROUP = new ConcurrentHashMap<>();
    /** Dernier tick d'alerte d'interception émis pour un corbeau (anti-spam). */
    private static final Map<UUID, Long> LAST_INTERCEPT_PING = new ConcurrentHashMap<>();

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

    /** Palette de sceaux : 12 couleurs vives. Hash stable du nom → couleur. */
    private static final ChatFormatting[] SEAL_COLORS = new ChatFormatting[] {
        ChatFormatting.RED, ChatFormatting.GOLD, ChatFormatting.YELLOW, ChatFormatting.GREEN,
        ChatFormatting.AQUA, ChatFormatting.BLUE, ChatFormatting.LIGHT_PURPLE, ChatFormatting.DARK_RED,
        ChatFormatting.DARK_GREEN, ChatFormatting.DARK_AQUA, ChatFormatting.DARK_PURPLE, ChatFormatting.DARK_BLUE
    };

    public static ChatFormatting sealColorFor(String name) {
        if (name == null || name.isEmpty()) return ChatFormatting.GRAY;
        int h = name.toLowerCase(Locale.ROOT).hashCode();
        return SEAL_COLORS[Math.floorMod(h, SEAL_COLORS.length)];
    }

    // ============================ API publique ============================

    public static void summonForPlayer(ServerPlayer player) {
        if (!ClientReadyState.isReady(player)) {
            player.sendSystemMessage(Component.literal(
                "§c§oTon client n'a pas fini de préparer le service du corbeau. Patiente quelques secondes."));
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
                "§c§oTes corbeaux ont besoin de souffler — réessaie dans §f" + s + " s§c."));
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

        Bird b = spawnBird(player, Kind.SUMMON);
        if (b == null) return;
        player.sendSystemMessage(Component.literal(
            "§8§oUn battement d'ailes au loin... le corbeau approche."));
    }

    public static void onMessageSent(ServerPlayer player)   { onMessageSent(player, 1); }

    public static void onMessageSent(ServerPlayer player, int fanCount) {
        Bird mainBird = startOutgoingSummon(player, 32, 28);
        if (mainBird == null || fanCount <= 1) return;
        // Éventail : spawn (fanCount - 1) corbeaux décoratifs partants depuis la même position
        Vec3 origin = mainBird.chicken.position();
        double baseAngle = Math.atan2(player.getLookAngle().z, player.getLookAngle().x) + Math.PI;
        for (int i = 1; i < fanCount; i++) {
            double spread = ((i % 2 == 0) ? -1 : 1) * (Math.PI / 7) * ((i + 1) / 2);
            double a = baseAngle + spread;
            Vec3 target = origin.add(Math.cos(a) * 32, 28, Math.sin(a) * 32);
            spawnFanBird(player, origin, target);
        }
    }

    public static void onLetterCancelled(ServerPlayer player) { startOutgoingSummon(player, 18, 16); }

    private static Bird startOutgoingSummon(ServerPlayer player, int horizontal, int vertical) {
        synchronized (BIRDS) {
            for (Bird b : BIRDS) {
                if (b.player == player && b.kind == Kind.SUMMON && b.phase == Phase.WAITING) {
                    b.phase = Phase.OUTGOING; b.ticks = 0;
                    Vec3 dir = player.getLookAngle().reverse();
                    b.flyTo = player.getEyePosition().add(dir.x * horizontal, vertical, dir.z * horizontal);
                    return b;
                }
            }
        }
        return null;
    }

    private static void spawnFanBird(ServerPlayer player, Vec3 origin, Vec3 target) {
        ServerLevel level = player.serverLevel();
        EntityType<? extends Mob> type = pickMessengerType();
        Mob bird;
        try { bird = type.create(level); } catch (Throwable t) { bird = EntityType.CHICKEN.create(level); }
        if (bird == null) return;
        bird.setPos(origin.x, origin.y, origin.z);
        bird.setNoAi(true); bird.setSilent(true); bird.setInvulnerable(true);
        bird.setNoGravity(true); bird.setCustomNameVisible(false);
        level.addFreshEntity(bird);
        Bird b = new Bird(Kind.SUMMON, bird, player, origin);
        b.phase = Phase.OUTGOING; b.flyTo = target;
        synchronized (BIRDS) { BIRDS.add(b); }
    }

    public static long computeDeliveryDelayTicks(ServerPlayer sender, ServerPlayer recipient) {
        double dist = senderDistance(sender, recipient);
        long seconds = 10L + (long) Math.floor(dist / 1000.0 * 60.0);
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

    public static double senderDistance(ServerPlayer sender, ServerPlayer recipient) {
        if (sender.level().dimension().equals(recipient.level().dimension()))
            return sender.position().distanceTo(recipient.position());
        return 5000;
    }

    public static ServerPlayer findPlayer(MinecraftServer server, ServerPlayer sender, String name) {
        if (name == null || name.isEmpty()) return null;
        if (sender != null && name.equalsIgnoreCase(sender.getGameProfile().getName())) return sender;
        for (ServerPlayer sp : server.getPlayerList().getPlayers())
            if (sp.getGameProfile().getName().equalsIgnoreCase(name)) return sp;
        return null;
    }

    public static void scheduleDelivery(MinecraftServer server, ServerPlayer recipient,
                                        String senderName, String subject, String body, long delayTicks) {
        long deliverAt = server.getTickCount() + delayTicks;
        UUID msgId = UUID.randomUUID();
        synchronized (PENDING) {
            PENDING.add(new PendingDelivery(recipient.getUUID(), msgId, senderName, subject, body, deliverAt));
        }
    }

    /** Le client a tranché : on retire l'oiseau et on délivre l'item papier si demandé. */
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
            giveLetterItem(player, match.sender, match.subject, match.body);
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
        new ResourceLocation("naturalist", "sparrow"),   // priorité : moineau (Naturalist)
        new ResourceLocation("naturalist", "finch"),
        new ResourceLocation("naturalist", "robin"),
        new ResourceLocation("naturalist", "bluejay"),
        new ResourceLocation("alexsmobs",   "crow"),     // fallback Alex's Mobs si présent
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

    private static Bird spawnBird(ServerPlayer player, Kind kind) {
        ServerLevel level = player.serverLevel();
        Vec3 eye = player.getEyePosition();
        Vec3 spawn;
        if (kind == Kind.DELIVERY) {
            // Direction aléatoire — interceptable de n'importe où
            double angle = player.getRandom().nextDouble() * Math.PI * 2.0;
            Vec3 dir = new Vec3(Math.cos(angle), 0, Math.sin(angle));
            spawn = eye.add(dir.scale(40)).add(0, 35, 0);
        } else {
            // SUMMON : 40 blocs DEVANT le joueur (où il regarde), 35 verticaux
            // → le joueur le voit arriver, jamais "sur la tête"
            Vec3 look = player.getLookAngle();
            Vec3 horiz = new Vec3(look.x, 0, look.z);
            if (horiz.lengthSqr() < 1e-4) {
                // Joueur regarde droit en haut/bas : on prend une direction aléatoire
                double a = player.getRandom().nextDouble() * Math.PI * 2.0;
                horiz = new Vec3(Math.cos(a), 0, Math.sin(a));
            } else {
                horiz = horiz.normalize();
            }
            spawn = eye.add(horiz.scale(40)).add(0, 35, 0);
        }
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
            }
        }
        return false;
    }

    // ============================ Tick principal ============================

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
        if (match.kind == Kind.DELIVERY && match.phase != Phase.OUTGOING) {
            // L'oiseau portait une lettre — la faire tomber sur place
            if (mob.level() instanceof ServerLevel sl) {
                ItemStack letter = makeLetterItem(match.sender, match.subject, match.body);
                Vec3 p = mob.position();
                ItemEntity drop = new ItemEntity(sl, p.x, p.y + 0.4, p.z, letter);
                drop.setDeltaMovement(0, 0.1, 0);
                sl.addFreshEntity(drop);
                sl.sendParticles(ParticleTypes.POOF, p.x, p.y + 0.5, p.z, 14, 0.4, 0.3, 0.4, 0.03);
                sl.sendParticles(ParticleTypes.ASH,  p.x, p.y + 0.5, p.z, 18, 0.5, 0.4, 0.5, 0.04);
            }
            // Message au tueur s'il est un joueur (autre que le destinataire)
            if (event.getSource().getEntity() instanceof ServerPlayer killer
                    && !killer.getUUID().equals(match.player.getUUID())) {
                killer.sendSystemMessage(Component.literal(
                    "§e§oTu as intercepté une lettre de §f" + match.sender
                    + " §eà §f" + match.player.getGameProfile().getName() + "§e."));
                match.player.sendSystemMessage(Component.literal(
                    "§c§o§f" + killer.getGameProfile().getName()
                    + "§c a abattu le corbeau qui te portait une lettre de §f" + match.sender + "§c."));
            } else {
                match.player.sendSystemMessage(Component.literal(
                    "§c§oUn corbeau s'est écroulé en plein vol — une lettre de §f" + match.sender + "§c ne t'arrivera jamais."));
            }
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
                         .addLast(new QueuedMessage(d.msgId, d.sender, d.subject, d.body));
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
        b.subject = msg.subject;
        b.body = msg.body;
        player.sendSystemMessage(Component.literal(
            "§8§oUn corbeau approche, une lettre attachée à la patte..."));
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
                if (moveTowardWithArc(b.chicken, b.flyTo, 0.55) || b.ticks > 20 * 8) {
                    poof(b.chicken);
                    b.chicken.discard();
                    toRemove.add(b);
                }
            }
        }
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
            String recipients = String.join(", ", peekPendingGroup(b.player));
            NetworkHandler.sendToClient(new PacketOpenCompose(recipients), b.player);
        } else { // DELIVERY → décision via chat clickable uniquement
            showLetterInChat(b);
        }
    }

    /** Affiche la lettre dans le chat avec deux boutons cliquables vanilla. */
    private static void showLetterInChat(Bird b) {
        ServerPlayer p = b.player;
        String cmdBase = "/corbeau-choice " + b.msgId + " ";

        ChatFormatting seal = sealColorFor(b.sender);
        String sealChar = "§" + seal.getChar() + "● ";
        Component header = Component.literal("§6───────── §e§l✉ §6§lUne lettre §6─────────");
        Component meta = Component.literal(sealChar + "§7De §b" + b.sender + " §7· §f§o" + b.subject);
        Component body = Component.literal("§7§o\"" + b.body + "§7§o\"");

        MutableJoin actions = new MutableJoin();
        actions.add(Component.literal("§a§l[ Garder la lettre ]")
            .copy()
            .withStyle(s -> s
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmdBase + "keep"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.literal("§fGarde une copie de la lettre dans ton inventaire (papier rose).")))));
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
        Vec3 hover = b.player.getEyePosition().add(b.player.getLookAngle().scale(2.2));
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

        // Timeout 15s : si pas de réponse, le corbeau repart avec la lettre.
        if (b.ticks > 20 * 15) {
            if (b.kind == Kind.DELIVERY) {
                // Retour à l'expéditeur : on reschedule une livraison vers le sender original
                MinecraftServer server = b.player.getServer();
                ServerPlayer originalSender = (server == null) ? null
                    : server.getPlayerList().getPlayerByName(b.sender);
                if (originalSender != null) {
                    String returnBody = "Ton corbeau est revenu sans réponse — §o"
                        + b.player.getGameProfile().getName()
                        + "§r n'a pas pris la lettre.\n\n— Lettre originale —\n" + b.body;
                    long delay = computeDeliveryDelayTicks(originalSender, b.player);
                    scheduleDelivery(server, originalSender,
                        b.player.getGameProfile().getName(),
                        "↩ Retour : " + b.subject,
                        returnBody, delay);
                }
                b.player.sendSystemMessage(Component.literal(
                    "§8§oLasse d'attendre, le corbeau s'envole — il rapportera la lettre à son expéditeur."));
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

    private static void giveLetterItem(ServerPlayer player, String sender, String subject, String body) {
        ItemStack stack = makeLetterItem(sender, subject, body);
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }

    private static ItemStack makeLetterItem(String sender, String subject, String body) {
        ItemStack stack = new ItemStack(Items.PAPER);
        // Nom : sujet entre guillemets, doré
        Component title = Component.literal("✉ " + subject).withStyle(s ->
            s.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(false));
        stack.setHoverName(title);

        CompoundTag display = stack.getOrCreateTagElement("display");
        ListTag lore = new ListTag();
        ChatFormatting seal = sealColorFor(sender);
        lore.add(StringTag.valueOf(Component.Serializer.toJson(
            Component.literal("● ").withStyle(seal)
                .append(Component.literal("Sceau de " + sender).withStyle(ChatFormatting.GRAY)))));
        lore.add(StringTag.valueOf(Component.Serializer.toJson(
            Component.literal("De : " + sender).withStyle(ChatFormatting.GRAY))));
        lore.add(StringTag.valueOf(Component.Serializer.toJson(
            Component.literal(""))));
        for (String line : wrap(body, 38)) {
            lore.add(StringTag.valueOf(Component.Serializer.toJson(
                Component.literal(line)
                    .withStyle(s -> s.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(false)))));
        }
        display.put("Lore", lore);
        return stack;
    }

    private static List<String> wrap(String body, int width) {
        List<String> out = new ArrayList<>();
        if (body == null) return out;
        for (String paragraph : body.split("\n")) {
            if (paragraph.isEmpty()) { out.add(""); continue; }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (line.length() == 0) line.append(word);
                else if (line.length() + 1 + word.length() <= width) line.append(' ').append(word);
                else { out.add(line.toString()); line.setLength(0); line.append(word); }
            }
            if (line.length() > 0) out.add(line.toString());
        }
        return out;
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

    private static int findPaperSlot(ServerPlayer p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && st.getItem() == Items.PAPER && !st.hasTag()) return i;
        }
        return -1;
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
