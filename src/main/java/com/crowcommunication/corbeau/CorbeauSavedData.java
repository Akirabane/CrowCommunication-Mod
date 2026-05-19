package com.crowcommunication.corbeau;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persistance des PENDING (livraisons en vol virtuel) et des LOST_PIGEONS (pigeons perdus).
 *
 * <p>Stocké sur la dimension overworld via le {@code DataStorage} vanilla. Les ticks de
 * {@link CorbeauManager.PendingDelivery#deliverAtTick} sont convertis en epoch millis au moment
 * de la sauvegarde (et reconvertis au load) pour survivre aux restarts serveur.</p>
 *
 * @author Akirabane
 */
public class CorbeauSavedData extends SavedData {

    public static final String NAME = "crowcommunication_state";

    private CorbeauSavedData() {}

    public static CorbeauSavedData load(CompoundTag tag, MinecraftServer server) {
        CorbeauSavedData data = new CorbeauSavedData();
        long nowMs = System.currentTimeMillis();
        long nowTick = server.getTickCount();

        // ---- PENDING ----
        List<CorbeauManager.PendingDelivery> pending = new ArrayList<>();
        ListTag pendList = tag.getList("pending", Tag.TAG_COMPOUND);
        for (int i = 0; i < pendList.size(); i++) {
            CompoundTag t = pendList.getCompound(i);
            try {
                UUID recipientUUID = t.getUUID("recipient");
                UUID msgId = t.getUUID("msgId");
                String sender = t.getString("sender");
                String displaySender = t.contains("displaySender") ? t.getString("displaySender") : null;
                if (displaySender != null && displaySender.isEmpty()) displaySender = null;
                String subject = t.getString("subject");
                String body = t.getString("body");
                long deliverAtMs = t.getLong("deliverAtMs");
                boolean isReturn = t.getBoolean("isReturn");
                // Reconstruire le tick cible à partir du delta restant
                long remainingMs = Math.max(0, deliverAtMs - nowMs);
                long remainingTicks = remainingMs / 50L; // 20 ticks/s = 50 ms/tick
                long deliverAtTick = nowTick + remainingTicks;
                pending.add(CorbeauManager.createPendingForRestore(
                    recipientUUID, msgId, sender, displaySender, subject, body, deliverAtTick, isReturn));
            } catch (Throwable ignored) {}
        }

        // ---- LOST_PIGEONS ----
        List<CorbeauManager.LostPigeon> lost = new ArrayList<>();
        ListTag lostList = tag.getList("lostPigeons", Tag.TAG_COMPOUND);
        for (int i = 0; i < lostList.size(); i++) {
            CompoundTag t = lostList.getCompound(i);
            try {
                lost.add(new CorbeauManager.LostPigeon(
                    t.getString("senderRealName"),
                    t.getString("displaySender"),
                    t.getString("subject"),
                    t.getString("body"),
                    t.getString("dimension"),
                    t.getDouble("x"), t.getDouble("y"), t.getDouble("z"),
                    t.getLong("dropAtMs")
                ));
            } catch (Throwable ignored) {}
        }

        CorbeauManager.restorePersistedState(pending, lost);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        long nowMs = System.currentTimeMillis();
        long nowTick = currentTick();

        ListTag pendList = new ListTag();
        for (CorbeauManager.PendingDelivery d : CorbeauManager.snapshotPending()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("recipient", d.recipientUUID);
            t.putUUID("msgId", d.msgId);
            t.putString("sender", d.sender);
            if (d.displaySender != null) t.putString("displaySender", d.displaySender);
            t.putString("subject", d.subject);
            t.putString("body", d.body);
            long remainingTicks = Math.max(0L, d.deliverAtTick - nowTick);
            t.putLong("deliverAtMs", nowMs + remainingTicks * 50L);
            t.putBoolean("isReturn", d.isReturn);
            pendList.add(t);
        }
        tag.put("pending", pendList);

        ListTag lostList = new ListTag();
        for (CorbeauManager.LostPigeon lp : CorbeauManager.snapshotLostPigeons()) {
            CompoundTag t = new CompoundTag();
            t.putString("senderRealName", lp.senderRealName);
            t.putString("displaySender", lp.displaySender == null ? "" : lp.displaySender);
            t.putString("subject", lp.subject);
            t.putString("body", lp.body);
            t.putString("dimension", lp.dimensionKey);
            t.putDouble("x", lp.x); t.putDouble("y", lp.y); t.putDouble("z", lp.z);
            t.putLong("dropAtMs", lp.dropAtMillis);
            lostList.add(t);
        }
        tag.put("lostPigeons", lostList);

        return tag;
    }

    /** Accès au tick courant côté server (utilisé pour sérialiser en delta-temps). */
    private static long currentTickStatic;
    public static long currentTick() { return currentTickStatic; }
    public static void setCurrentTick(long t) { currentTickStatic = t; }

    /**
     * Hook : récupère (ou crée) le SavedData pour ce monde et le rebranche au manager.
     * À appeler une fois au démarrage serveur, sur la dim overworld.
     */
    public static CorbeauSavedData attach(ServerLevel overworld) {
        MinecraftServer server = overworld.getServer();
        setCurrentTick(server.getTickCount());
        CorbeauSavedData data = overworld.getDataStorage().computeIfAbsent(
            tag -> load(tag, server),
            CorbeauSavedData::new,
            NAME
        );
        // Marquer dirty à chaque mutation de l'état pour que vanilla persiste à la prochaine sauvegarde
        CorbeauManager.setPersistenceDirtyMarker(v -> data.setDirty());
        return data;
    }
}
