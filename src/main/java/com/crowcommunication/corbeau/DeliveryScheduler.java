package com.crowcommunication.corbeau;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Gère les files d'attente de livraison, les pigeons perdus et la persistance temporelle.
 *
 * <p>Extraction depuis {@link CorbeauManager} (v1.0.9). Contient :
 * {@code PENDING} (livraisons en vol virtuel), {@code QUEUE} (messages prêts par joueur),
 * {@code LOST_PIGEONS} (lettres égarées à déposer au sol).</p>
 */
final class DeliveryScheduler {

    private DeliveryScheduler() {}

    private static final List<CorbeauManager.PendingDelivery> PENDING = new ArrayList<>();
    private static final Map<UUID, Deque<CorbeauManager.QueuedMessage>> QUEUE = new ConcurrentHashMap<>();
    private static final List<CorbeauManager.LostPigeon> LOST_PIGEONS = new ArrayList<>();

    private static java.util.function.Consumer<Void> persistDirty = v -> {};

    static void setPersistenceDirtyMarker(java.util.function.Consumer<Void> marker) {
        persistDirty = marker == null ? (v -> {}) : marker;
    }

    // ---- Scheduling ----

    static UUID scheduleDelivery(MinecraftServer server, ServerPlayer recipient,
                                 String senderName, String displaySender,
                                 String subject, String body, long delayTicks) {
        return scheduleDeliveryInternal(server, recipient.getUUID(), senderName, displaySender,
            subject, body, delayTicks, false);
    }

    static UUID scheduleDeliveryInternal(MinecraftServer server, UUID recipientUUID,
                                         String senderName, String displaySender,
                                         String subject, String body,
                                         long delayTicks, boolean isReturn) {
        long deliverAt = server.getTickCount() + delayTicks;
        UUID msgId = UUID.randomUUID();
        synchronized (PENDING) {
            PENDING.add(new CorbeauManager.PendingDelivery(recipientUUID, msgId, senderName, displaySender,
                subject, body, deliverAt, isReturn));
        }
        persistDirty.accept(null);
        return msgId;
    }

    static void cancelDeliveries(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return;
        synchronized (PENDING) {
            PENDING.removeIf(d -> ids.contains(d.msgId));
        }
        for (Deque<CorbeauManager.QueuedMessage> q : QUEUE.values()) {
            q.removeIf(m -> ids.contains(m.id));
        }
    }

    static long scheduleLostPigeon(ServerPlayer sender, String displaySender,
                                   String subject, String body) {
        var rng = sender.getRandom();
        double angle = rng.nextDouble() * Math.PI * 2.0;
        double dist = 800 + rng.nextDouble() * 600;
        double x = sender.getX() + Math.cos(angle) * dist;
        double z = sender.getZ() + Math.sin(angle) * dist;
        double y = sender.getY();
        long delayMs = (5L + rng.nextInt(16)) * 60L * 1000L;
        long dropAt = System.currentTimeMillis() + delayMs;
        String dim = sender.level().dimension().location().toString();
        synchronized (LOST_PIGEONS) {
            LOST_PIGEONS.add(new CorbeauManager.LostPigeon(sender.getGameProfile().getName(), displaySender,
                subject, body, dim, x, y, z, dropAt));
        }
        persistDirty.accept(null);
        return delayMs / 1000L;
    }

    // ---- Tick steps ----

    /** Étape 1 du tick serveur : fait progresser PENDING → QUEUE. */
    static void tickPending(MinecraftServer server) {
        long now = server.getTickCount();
        List<CorbeauManager.PendingDelivery> done = new ArrayList<>();
        synchronized (PENDING) {
            for (CorbeauManager.PendingDelivery d : PENDING) {
                if (now >= d.deliverAtTick) {
                    QUEUE.computeIfAbsent(d.recipientUUID, k -> new ArrayDeque<>())
                         .addLast(new CorbeauManager.QueuedMessage(d.msgId, d.sender, d.displaySender,
                             d.subject, d.body, d.isReturn));
                    done.add(d);
                }
            }
            PENDING.removeAll(done);
        }
    }

    /** Étape 4 du tick serveur : dépose les pigeons perdus arrivés à échéance. */
    static void tickLostPigeons(MinecraftServer server) {
        long nowMs = System.currentTimeMillis();
        List<CorbeauManager.LostPigeon> hatched = new ArrayList<>();
        synchronized (LOST_PIGEONS) {
            for (CorbeauManager.LostPigeon lp : LOST_PIGEONS) {
                if (nowMs >= lp.dropAtMillis) hatched.add(lp);
            }
            LOST_PIGEONS.removeAll(hatched);
        }
        for (CorbeauManager.LostPigeon lp : hatched) dropLostPigeonInWorld(server, lp);
        if (!hatched.isEmpty()) persistDirty.accept(null);
    }

    // ---- Queue access for dispatcher (CorbeauManager step 2) ----

    static Set<UUID> queuedPlayerUUIDs() { return QUEUE.keySet(); }

    static CorbeauManager.QueuedMessage pollFirst(UUID uuid) {
        Deque<CorbeauManager.QueuedMessage> q = QUEUE.get(uuid);
        return q == null ? null : q.pollFirst();
    }

    static void requeueFirst(UUID uuid, CorbeauManager.QueuedMessage msg) {
        QUEUE.computeIfAbsent(uuid, k -> new ArrayDeque<>()).addFirst(msg);
    }

    // ---- Persistence ----

    static List<CorbeauManager.PendingDelivery> snapshotPending() {
        synchronized (PENDING) { return new ArrayList<>(PENDING); }
    }

    static List<CorbeauManager.LostPigeon> snapshotLostPigeons() {
        synchronized (LOST_PIGEONS) { return new ArrayList<>(LOST_PIGEONS); }
    }

    static void restorePersistedState(List<CorbeauManager.PendingDelivery> pending,
                                      List<CorbeauManager.LostPigeon> lost) {
        synchronized (PENDING) { PENDING.clear(); PENDING.addAll(pending); }
        synchronized (LOST_PIGEONS) { LOST_PIGEONS.clear(); LOST_PIGEONS.addAll(lost); }
    }

    static CorbeauManager.PendingDelivery createPendingForRestore(UUID recipientUUID, UUID msgId,
                                                                   String sender, String displaySender,
                                                                   String subject, String body,
                                                                   long deliverAtTick, boolean isReturn) {
        return new CorbeauManager.PendingDelivery(recipientUUID, msgId, sender, displaySender,
            subject, body, deliverAtTick, isReturn);
    }

    // ---- Internal ----

    private static void dropLostPigeonInWorld(MinecraftServer server, CorbeauManager.LostPigeon lp) {
        ServerLevel sl = null;
        for (ServerLevel candidate : server.getAllLevels()) {
            if (candidate.dimension().location().toString().equals(lp.dimensionKey)) { sl = candidate; break; }
        }
        if (sl == null) return;
        int bx = (int) Math.floor(lp.x);
        int bz = (int) Math.floor(lp.z);
        int by = sl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx, bz);
        double spawnY = by > 0 ? by + 0.5 : lp.y;
        ItemStack letter = LetterRenderer.makeLetterItem(lp.displaySender, lp.subject, lp.body);
        ItemEntity drop = new ItemEntity(sl, lp.x, spawnY, lp.z, letter);
        drop.setDeltaMovement(0, 0.05, 0);
        drop.setPickUpDelay(0);
        drop.setUnlimitedLifetime();
        sl.addFreshEntity(drop);
        sl.sendParticles(ParticleTypes.POOF, lp.x, spawnY + 0.4, lp.z, 8, 0.3, 0.2, 0.3, 0.01);
    }
}
