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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
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
@SuppressWarnings("null")
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
        /** Seul le bird principal ouvre l'UI de composition et déclenche le départ. */
        boolean isMain;
        /** Nom du destinataire vers lequel ce bird s'envole au départ (null = direction par défaut). */
        String recipientName;
        // pour DELIVERY :
        UUID msgId;
        String sender, subject, body;

        /** IDs des livraisons programmées portées par ce corbeau (SUMMON OUTGOING interceptable). */
        List<UUID> deliveryIds;
        /** Contenu de la lettre portée (SUMMON OUTGOING). */
        String outSubject, outBody;
        /** Durée totale de vol pour un SUMMON OUTGOING porteur — calée sur le délai de livraison. */
        long outgoingDurationTicks;
        /** Vrai si ce corbeau de livraison transporte une lettre retournée (le destinataire AFK la renvoie). */
        boolean isReturn;

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
        final boolean isReturn;
        PendingDelivery(UUID r, UUID id, String s, String su, String b, long t, boolean ret) {
            this.recipientUUID = r; this.msgId = id; this.sender = s; this.subject = su; this.body = b;
            this.deliverAtTick = t; this.isReturn = ret;
        }
    }
    private static final List<PendingDelivery> PENDING = new ArrayList<>();

    /** Message arrivé en attente d'être physiquement livré (queue par joueur). */
    public static final class QueuedMessage {
        public final UUID id;
        public final String sender, subject, body;
        public final boolean isReturn;
        public QueuedMessage(UUID id, String s, String su, String b, boolean ret) {
            this.id = id; this.sender = s; this.subject = su; this.body = b; this.isReturn = ret;
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

    /** 12 couleurs de sceau ; index déterminé par hash stable du nom de l'expéditeur. */
    private static final ChatFormatting[] SEAL_COLORS = new ChatFormatting[] {
        ChatFormatting.RED, ChatFormatting.GOLD, ChatFormatting.YELLOW, ChatFormatting.GREEN,
        ChatFormatting.AQUA, ChatFormatting.BLUE, ChatFormatting.LIGHT_PURPLE, ChatFormatting.DARK_RED,
        ChatFormatting.DARK_GREEN, ChatFormatting.DARK_AQUA, ChatFormatting.DARK_PURPLE, ChatFormatting.DARK_BLUE
    };

    /**
     * @param name nom de l'expéditeur
     * @return couleur de sceau déterministe associée à ce nom
     */
    public static ChatFormatting sealColorFor(String name) {
        if (name == null || name.isEmpty()) return ChatFormatting.GRAY;
        int h = name.toLowerCase(Locale.ROOT).hashCode();
        return SEAL_COLORS[Math.floorMod(h, SEAL_COLORS.length)];
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
     * Sous orage, 50 % de chances que le délai soit doublé.---- Minecraft Crash Report ----
// My bad.

Time: 2026-05-18 16:58:29
Description: mouseClicked event handler

java.lang.NoSuchMethodError: 'com.mojang.brigadier.builder.LiteralArgumentBuilder net.minecraft.commands.Commands.literal(java.lang.String)'
	at com.crowcommunication.corbeau.CorbeauCommand.register(CorbeauCommand.java:43) ~[crowcommunication-1.0.0.jar%23172!/:1.0.0] {re:classloading}
	at com.crowcommunication.corbeau.CorbeauCommand.onRegister(CorbeauCommand.java:34) ~[crowcommunication-1.0.0.jar%23172!/:1.0.0] {re:classloading}
	at com.crowcommunication.corbeau.__CorbeauCommand_onRegister_RegisterCommandsEvent.invoke(.dynamic) ~[crowcommunication-1.0.0.jar%23172!/:1.0.0] {re:classloading,pl:eventbus:B}
	at net.minecraftforge.eventbus.ASMEventHandler.invoke(ASMEventHandler.java:55) ~[eventbus-6.2.33.jar%2387!/:?] {}
	at net.minecraftforge.eventbus.EventBus.post(EventBus.java:312) ~[eventbus-6.2.33.jar%2387!/:?] {}
	at net.minecraftforge.eventbus.EventBus.post(EventBus.java:298) ~[eventbus-6.2.33.jar%2387!/:?] {}
	at net.minecraftforge.event.ForgeEventFactory.onCommandRegister(ForgeEventFactory.java:817) ~[forge-1.20.1-47.4.20-universal.jar%23181!/:?] {re:classloading}
	at net.minecraft.commands.Commands.<init>(Commands.java:221) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.server.ReloadableServerResources.<init>(ReloadableServerResources.java:41) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.server.ReloadableServerResources.m_247740_(ReloadableServerResources.java:75) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.server.WorldLoader.m_214362_(WorldLoader.java:38) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.screens.worldselection.WorldOpenFlows.m_246486_(WorldOpenFlows.java:162) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.screens.worldselection.WorldOpenFlows.m_233122_(WorldOpenFlows.java:113) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.screens.worldselection.WorldOpenFlows.doLoadLevel(WorldOpenFlows.java:181) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.screens.worldselection.WorldOpenFlows.m_233145_(WorldOpenFlows.java:169) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.screens.worldselection.WorldOpenFlows.m_233133_(WorldOpenFlows.java:65) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.screens.worldselection.WorldSelectionList$WorldListEntry.m_101744_(WorldSelectionList.java:575) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.screens.worldselection.WorldSelectionList$WorldListEntry.m_101704_(WorldSelectionList.java:474) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.screens.worldselection.WorldSelectionList$WorldListEntry.m_6375_(WorldSelectionList.java:416) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.components.AbstractSelectionList.m_6375_(AbstractSelectionList.java:298) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,re:classloading,pl:mixin:APP:mixins.essential.json:client.gui.AbstractListAccessor,pl:mixin:APP:mixins.essential.json:client.gui.Mixin_SelectionListDividers_GuiList,pl:mixin:APP:mixins.essential.json:client.gui.Mixin_SelectionListDividers_GuiMultiplayer,pl:mixin:A}
	at net.minecraft.client.gui.components.events.ContainerEventHandler.m_6375_(ContainerEventHandler.java:38) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,re:computing_frames,re:classloading}
	at net.minecraft.client.MouseHandler.m_168084_(MouseHandler.java:92) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,re:classloading,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent_Priority,pl:mixin:APP:mixins.essential.json:client.MouseHelperAccessor,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiMouseReleaseEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_MouseScrollEvent,pl:mixin:APP:mixins.essential.json:feature.chat.Mixin_ChatPeekScrolling,pl:mixin:A}
	at net.minecraft.client.gui.screens.Screen.m_96579_(Screen.java:437) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,pl:accesstransformer:B,re:computing_frames,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:APP:mixins.essential.json:client.gui.GuiScreenAccessor,pl:mixin:APP:mixins.essential.json:client.gui.Mixin_GuiScreen_PostKeyTypedEvent,pl:mixin:APP:mixins.essential.json:client.gui.MixinGuiScreen,pl:mixin:APP:mixins.essential.json:client.gui.drag_drop_gui.Mixin_MuteNarration_Screen,pl:mixin:A}
	at net.minecraft.client.MouseHandler.m_91530_(MouseHandler.java:89) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,re:classloading,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent_Priority,pl:mixin:APP:mixins.essential.json:client.MouseHelperAccessor,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiMouseReleaseEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_MouseScrollEvent,pl:mixin:APP:mixins.essential.json:feature.chat.Mixin_ChatPeekScrolling,pl:mixin:A}
	at net.minecraft.client.MouseHandler.m_168091_(MouseHandler.java:189) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,re:classloading,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent_Priority,pl:mixin:APP:mixins.essential.json:client.MouseHelperAccessor,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiMouseReleaseEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_MouseScrollEvent,pl:mixin:APP:mixins.essential.json:feature.chat.Mixin_ChatPeekScrolling,pl:mixin:A}
	at net.minecraft.util.thread.BlockableEventLoop.execute(BlockableEventLoop.java:102) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,pl:accesstransformer:B,re:computing_frames,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:APP:mixins.essential.json:client.Mixin_ThreadTaskExecutor,pl:mixin:A}
	at net.minecraft.client.MouseHandler.m_91565_(MouseHandler.java:188) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,re:classloading,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent_Priority,pl:mixin:APP:mixins.essential.json:client.MouseHelperAccessor,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiMouseReleaseEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_MouseScrollEvent,pl:mixin:APP:mixins.essential.json:feature.chat.Mixin_ChatPeekScrolling,pl:mixin:A}
	at org.lwjgl.glfw.GLFWMouseButtonCallbackI.callback(GLFWMouseButtonCallbackI.java:43) ~[lwjgl-glfw-3.3.1.jar%23141!/:build 7] {}
	at org.lwjgl.system.JNI.invokeV(Native Method) ~[lwjgl-3.3.1.jar%23153!/:build 7] {}
	at org.lwjgl.glfw.GLFW.glfwWaitEventsTimeout(GLFW.java:3474) ~[lwjgl-glfw-3.3.1.jar%23141!/:build 7] {re:mixin}
	at com.mojang.blaze3d.systems.RenderSystem.limitDisplayFPS(RenderSystem.java:237) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading,pl:mixin:APP:mixins.essential.json:client.Mixin_SuppressScreenshotBufferFlip,pl:mixin:A}
	at net.minecraft.client.Minecraft.m_91383_(Minecraft.java:1173) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,pl:accesstransformer:B,re:mixin,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:A}
	at net.minecraft.client.Minecraft.m_91374_(Minecraft.java:718) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,pl:accesstransformer:B,re:mixin,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:A}
	at net.minecraft.client.main.Main.main(Main.java:218) ~[forge-47.4.20.jar:?] {re:classloading}
	at jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method) ~[?:?] {}
	at jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77) ~[?:?] {}
	at jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43) ~[?:?] {}
	at java.lang.reflect.Method.invoke(Method.java:569) ~[?:?] {}
	at net.minecraftforge.fml.loading.targets.CommonLaunchHandler.runTarget(CommonLaunchHandler.java:111) ~[fmlloader-1.20.1-47.4.20.jar:?] {}
	at net.minecraftforge.fml.loading.targets.CommonLaunchHandler.clientService(CommonLaunchHandler.java:99) ~[fmlloader-1.20.1-47.4.20.jar:?] {}
	at net.minecraftforge.fml.loading.targets.CommonClientLaunchHandler.lambda$makeService$0(CommonClientLaunchHandler.java:25) ~[fmlloader-1.20.1-47.4.20.jar:?] {}
	at cpw.mods.modlauncher.LaunchServiceHandlerDecorator.launch(LaunchServiceHandlerDecorator.java:30) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.LaunchServiceHandler.launch(LaunchServiceHandler.java:53) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.LaunchServiceHandler.launch(LaunchServiceHandler.java:71) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.Launcher.run(Launcher.java:108) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.Launcher.main(Launcher.java:78) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.BootstrapLaunchConsumer.accept(BootstrapLaunchConsumer.java:26) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.BootstrapLaunchConsumer.accept(BootstrapLaunchConsumer.java:23) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.bootstraplauncher.BootstrapLauncher.main(BootstrapLauncher.java:141) ~[bootstraplauncher-1.1.2.jar:?] {}


A detailed walkthrough of the error, its code path and all known details is as follows:
---------------------------------------------------------------------------------------

-- Head --
Thread: Render thread
Suspected Mod: 
	Crow Communication Mod (crowcommunication), Version: 1.0.0
		Issue tracker URL: https://github.com/Akirabane/crowcommunication/issues
		at TRANSFORMER/crowcommunication@1.0.0/com.crowcommunication.corbeau.CorbeauCommand.register(CorbeauCommand.java:43)
Stacktrace:
	at com.crowcommunication.corbeau.CorbeauCommand.register(CorbeauCommand.java:43) ~[crowcommunication-1.0.0.jar%23172!/:1.0.0] {re:classloading}
	at com.crowcommunication.corbeau.CorbeauCommand.onRegister(CorbeauCommand.java:34) ~[crowcommunication-1.0.0.jar%23172!/:1.0.0] {re:classloading}
	at com.crowcommunication.corbeau.__CorbeauCommand_onRegister_RegisterCommandsEvent.invoke(.dynamic) ~[crowcommunication-1.0.0.jar%23172!/:1.0.0] {re:classloading,pl:eventbus:B}
	at net.minecraftforge.eventbus.ASMEventHandler.invoke(ASMEventHandler.java:55) ~[eventbus-6.2.33.jar%2387!/:?] {}
	at net.minecraftforge.eventbus.EventBus.post(EventBus.java:312) ~[eventbus-6.2.33.jar%2387!/:?] {}
	at net.minecraftforge.eventbus.EventBus.post(EventBus.java:298) ~[eventbus-6.2.33.jar%2387!/:?] {}
	at net.minecraftforge.event.ForgeEventFactory.onCommandRegister(ForgeEventFactory.java:817) ~[forge-1.20.1-47.4.20-universal.jar%23181!/:?] {re:classloading}
	at net.minecraft.commands.Commands.<init>(Commands.java:221) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.server.ReloadableServerResources.<init>(ReloadableServerResources.java:41) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.server.ReloadableServerResources.m_247740_(ReloadableServerResources.java:75) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.server.WorldLoader.m_214362_(WorldLoader.java:38) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.screens.worldselection.WorldOpenFlows.m_246486_(WorldOpenFlows.java:162) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.screens.worldselection.WorldOpenFlows.m_233122_(WorldOpenFlows.java:113) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.screens.worldselection.WorldOpenFlows.doLoadLevel(WorldOpenFlows.java:181) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.screens.worldselection.WorldOpenFlows.m_233145_(WorldOpenFlows.java:169) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.screens.worldselection.WorldOpenFlows.m_233133_(WorldOpenFlows.java:65) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.screens.worldselection.WorldSelectionList$WorldListEntry.m_101744_(WorldSelectionList.java:575) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.screens.worldselection.WorldSelectionList$WorldListEntry.m_101704_(WorldSelectionList.java:474) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.screens.worldselection.WorldSelectionList$WorldListEntry.m_6375_(WorldSelectionList.java:416) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.gui.components.AbstractSelectionList.m_6375_(AbstractSelectionList.java:298) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,re:classloading,pl:mixin:APP:mixins.essential.json:client.gui.AbstractListAccessor,pl:mixin:APP:mixins.essential.json:client.gui.Mixin_SelectionListDividers_GuiList,pl:mixin:APP:mixins.essential.json:client.gui.Mixin_SelectionListDividers_GuiMultiplayer,pl:mixin:A}
	at net.minecraft.client.gui.components.events.ContainerEventHandler.m_6375_(ContainerEventHandler.java:38) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,re:computing_frames,re:classloading}
	at net.minecraft.client.MouseHandler.m_168084_(MouseHandler.java:92) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,re:classloading,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent_Priority,pl:mixin:APP:mixins.essential.json:client.MouseHelperAccessor,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiMouseReleaseEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_MouseScrollEvent,pl:mixin:APP:mixins.essential.json:feature.chat.Mixin_ChatPeekScrolling,pl:mixin:A}
	at net.minecraft.client.gui.screens.Screen.m_96579_(Screen.java:437) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,pl:accesstransformer:B,re:computing_frames,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:APP:mixins.essential.json:client.gui.GuiScreenAccessor,pl:mixin:APP:mixins.essential.json:client.gui.Mixin_GuiScreen_PostKeyTypedEvent,pl:mixin:APP:mixins.essential.json:client.gui.MixinGuiScreen,pl:mixin:APP:mixins.essential.json:client.gui.drag_drop_gui.Mixin_MuteNarration_Screen,pl:mixin:A}
	at net.minecraft.client.MouseHandler.m_91530_(MouseHandler.java:89) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,re:classloading,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent_Priority,pl:mixin:APP:mixins.essential.json:client.MouseHelperAccessor,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiMouseReleaseEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_MouseScrollEvent,pl:mixin:APP:mixins.essential.json:feature.chat.Mixin_ChatPeekScrolling,pl:mixin:A}
	at net.minecraft.client.MouseHandler.m_168091_(MouseHandler.java:189) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,re:classloading,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent_Priority,pl:mixin:APP:mixins.essential.json:client.MouseHelperAccessor,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiMouseReleaseEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_MouseScrollEvent,pl:mixin:APP:mixins.essential.json:feature.chat.Mixin_ChatPeekScrolling,pl:mixin:A}
	at net.minecraft.util.thread.BlockableEventLoop.execute(BlockableEventLoop.java:102) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,pl:accesstransformer:B,re:computing_frames,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:APP:mixins.essential.json:client.Mixin_ThreadTaskExecutor,pl:mixin:A}
	at net.minecraft.client.MouseHandler.m_91565_(MouseHandler.java:188) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,re:classloading,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent_Priority,pl:mixin:APP:mixins.essential.json:client.MouseHelperAccessor,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiMouseReleaseEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_MouseScrollEvent,pl:mixin:APP:mixins.essential.json:feature.chat.Mixin_ChatPeekScrolling,pl:mixin:A}
	at org.lwjgl.glfw.GLFWMouseButtonCallbackI.callback(GLFWMouseButtonCallbackI.java:43) ~[lwjgl-glfw-3.3.1.jar%23141!/:build 7] {}
	at org.lwjgl.system.JNI.invokeV(Native Method) ~[lwjgl-3.3.1.jar%23153!/:build 7] {}
	at org.lwjgl.glfw.GLFW.glfwWaitEventsTimeout(GLFW.java:3474) ~[lwjgl-glfw-3.3.1.jar%23141!/:build 7] {re:mixin}
-- Affected screen --
Details:
	Screen name: net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
Stacktrace:
	at net.minecraft.client.gui.screens.Screen.m_96579_(Screen.java:437) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,pl:accesstransformer:B,re:computing_frames,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:APP:mixins.essential.json:client.gui.GuiScreenAccessor,pl:mixin:APP:mixins.essential.json:client.gui.Mixin_GuiScreen_PostKeyTypedEvent,pl:mixin:APP:mixins.essential.json:client.gui.MixinGuiScreen,pl:mixin:APP:mixins.essential.json:client.gui.drag_drop_gui.Mixin_MuteNarration_Screen,pl:mixin:A}
	at net.minecraft.client.MouseHandler.m_91530_(MouseHandler.java:89) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,re:classloading,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent_Priority,pl:mixin:APP:mixins.essential.json:client.MouseHelperAccessor,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiMouseReleaseEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_MouseScrollEvent,pl:mixin:APP:mixins.essential.json:feature.chat.Mixin_ChatPeekScrolling,pl:mixin:A}
	at net.minecraft.client.MouseHandler.m_168091_(MouseHandler.java:189) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,re:classloading,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent_Priority,pl:mixin:APP:mixins.essential.json:client.MouseHelperAccessor,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiMouseReleaseEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_MouseScrollEvent,pl:mixin:APP:mixins.essential.json:feature.chat.Mixin_ChatPeekScrolling,pl:mixin:A}
	at net.minecraft.util.thread.BlockableEventLoop.execute(BlockableEventLoop.java:102) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,pl:accesstransformer:B,re:computing_frames,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:APP:mixins.essential.json:client.Mixin_ThreadTaskExecutor,pl:mixin:A}
	at net.minecraft.client.MouseHandler.m_91565_(MouseHandler.java:188) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,re:classloading,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent_Priority,pl:mixin:APP:mixins.essential.json:client.MouseHelperAccessor,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiClickEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_GuiMouseReleaseEvent,pl:mixin:APP:mixins.essential.json:events.Mixin_MouseScrollEvent,pl:mixin:APP:mixins.essential.json:feature.chat.Mixin_ChatPeekScrolling,pl:mixin:A}
	at org.lwjgl.glfw.GLFWMouseButtonCallbackI.callback(GLFWMouseButtonCallbackI.java:43) ~[lwjgl-glfw-3.3.1.jar%23141!/:build 7] {}
	at org.lwjgl.system.JNI.invokeV(Native Method) ~[lwjgl-3.3.1.jar%23153!/:build 7] {}
	at org.lwjgl.glfw.GLFW.glfwWaitEventsTimeout(GLFW.java:3474) ~[lwjgl-glfw-3.3.1.jar%23141!/:build 7] {re:mixin}
	at com.mojang.blaze3d.systems.RenderSystem.limitDisplayFPS(RenderSystem.java:237) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading,pl:mixin:APP:mixins.essential.json:client.Mixin_SuppressScreenshotBufferFlip,pl:mixin:A}
	at net.minecraft.client.Minecraft.m_91383_(Minecraft.java:1173) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,pl:accesstransformer:B,re:mixin,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:A}
	at net.minecraft.client.Minecraft.m_91374_(Minecraft.java:718) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,pl:accesstransformer:B,re:mixin,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:A}
	at net.minecraft.client.main.Main.main(Main.java:218) ~[forge-47.4.20.jar:?] {re:classloading}
	at jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method) ~[?:?] {}
	at jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77) ~[?:?] {}
	at jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43) ~[?:?] {}
	at java.lang.reflect.Method.invoke(Method.java:569) ~[?:?] {}
	at net.minecraftforge.fml.loading.targets.CommonLaunchHandler.runTarget(CommonLaunchHandler.java:111) ~[fmlloader-1.20.1-47.4.20.jar:?] {}
	at net.minecraftforge.fml.loading.targets.CommonLaunchHandler.clientService(CommonLaunchHandler.java:99) ~[fmlloader-1.20.1-47.4.20.jar:?] {}
	at net.minecraftforge.fml.loading.targets.CommonClientLaunchHandler.lambda$makeService$0(CommonClientLaunchHandler.java:25) ~[fmlloader-1.20.1-47.4.20.jar:?] {}
	at cpw.mods.modlauncher.LaunchServiceHandlerDecorator.launch(LaunchServiceHandlerDecorator.java:30) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.LaunchServiceHandler.launch(LaunchServiceHandler.java:53) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.LaunchServiceHandler.launch(LaunchServiceHandler.java:71) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.Launcher.run(Launcher.java:108) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.Launcher.main(Launcher.java:78) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.BootstrapLaunchConsumer.accept(BootstrapLaunchConsumer.java:26) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.BootstrapLaunchConsumer.accept(BootstrapLaunchConsumer.java:23) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.bootstraplauncher.BootstrapLauncher.main(BootstrapLauncher.java:141) ~[bootstraplauncher-1.1.2.jar:?] {}


-- Last reload --
Details:
	Reload number: 1
	Reload reason: initial
	Finished: Yes
	Packs: vanilla, mod_resources, Essential Assets, essential
Stacktrace:
	at net.minecraft.client.ResourceLoadStateTracker.m_168562_(ResourceLoadStateTracker.java:49) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:classloading}
	at net.minecraft.client.Minecraft.m_91354_(Minecraft.java:2326) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,pl:accesstransformer:B,re:mixin,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:A}
	at net.minecraft.client.Minecraft.m_91374_(Minecraft.java:735) ~[client-1.20.1-20230612.114412-srg.jar%23176!/:?] {re:mixin,pl:accesstransformer:B,re:mixin,pl:accesstransformer:B,re:classloading,pl:accesstransformer:B,pl:mixin:A}
	at net.minecraft.client.main.Main.main(Main.java:218) ~[forge-47.4.20.jar:?] {re:classloading}
	at jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method) ~[?:?] {}
	at jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77) ~[?:?] {}
	at jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43) ~[?:?] {}
	at java.lang.reflect.Method.invoke(Method.java:569) ~[?:?] {}
	at net.minecraftforge.fml.loading.targets.CommonLaunchHandler.runTarget(CommonLaunchHandler.java:111) ~[fmlloader-1.20.1-47.4.20.jar:?] {}
	at net.minecraftforge.fml.loading.targets.CommonLaunchHandler.clientService(CommonLaunchHandler.java:99) ~[fmlloader-1.20.1-47.4.20.jar:?] {}
	at net.minecraftforge.fml.loading.targets.CommonClientLaunchHandler.lambda$makeService$0(CommonClientLaunchHandler.java:25) ~[fmlloader-1.20.1-47.4.20.jar:?] {}
	at cpw.mods.modlauncher.LaunchServiceHandlerDecorator.launch(LaunchServiceHandlerDecorator.java:30) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.LaunchServiceHandler.launch(LaunchServiceHandler.java:53) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.LaunchServiceHandler.launch(LaunchServiceHandler.java:71) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.Launcher.run(Launcher.java:108) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.Launcher.main(Launcher.java:78) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.BootstrapLaunchConsumer.accept(BootstrapLaunchConsumer.java:26) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.modlauncher.BootstrapLaunchConsumer.accept(BootstrapLaunchConsumer.java:23) ~[modlauncher-10.0.9.jar:?] {}
	at cpw.mods.bootstraplauncher.BootstrapLauncher.main(BootstrapLauncher.java:141) ~[bootstraplauncher-1.1.2.jar:?] {}


-- System Details --
Details:
	Minecraft Version: 1.20.1
	Minecraft Version ID: 1.20.1
	Operating System: Windows 11 (amd64) version 10.0
	Java Version: 17.0.15, Microsoft
	Java VM Version: OpenJDK 64-Bit Server VM (mixed mode), Microsoft
	Memory: 1022406440 bytes (975 MiB) / 1778384896 bytes (1696 MiB) up to 16777216000 bytes (16000 MiB)
	CPUs: 32
	Processor Vendor: GenuineIntel
	Processor Name: Intel(R) Core(TM) i9-14900KF
	Identifier: Intel64 Family 6 Model 183 Stepping 1
	Microarchitecture: unknown
	Frequency (GHz): 3.19
	Number of physical packages: 1
	Number of physical CPUs: 24
	Number of logical CPUs: 32
	Graphics card #0 name: Meta Virtual Monitor
	Graphics card #0 vendor: Meta Inc.
	Graphics card #0 VRAM (MB): 0.00
	Graphics card #0 deviceId: unknown
	Graphics card #0 versionInfo: DriverVersion=11.1.45.729
	Graphics card #1 name: Parsec Virtual Display Adapter
	Graphics card #1 vendor: Parsec Cloud, Inc.
	Graphics card #1 VRAM (MB): 0.00
	Graphics card #1 deviceId: unknown
	Graphics card #1 versionInfo: DriverVersion=0.45.0.0
	Graphics card #2 name: NVIDIA GeForce RTX 5080
	Graphics card #2 vendor: NVIDIA (0x10de)
	Graphics card #2 VRAM (MB): 4095.00
	Graphics card #2 deviceId: 0x2c02
	Graphics card #2 versionInfo: DriverVersion=32.0.15.9621
	Memory slot #0 capacity (MB): 32768.00
	Memory slot #0 clockSpeed (GHz): 6.00
	Memory slot #0 type: Unknown
	Memory slot #1 capacity (MB): 32768.00
	Memory slot #1 clockSpeed (GHz): 6.00
	Memory slot #1 type: Unknown
	Virtual memory max (MB): 69415.02
	Virtual memory used (MB): 32641.62
	Swap memory total (MB): 4096.00
	Swap memory used (MB): 0.00
	JVM Flags: 4 total; -XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump -Xss1M -Xmx16000m -Xms256m
	Launched Version: forge-47.4.20
	Backend library: LWJGL version 3.3.1 build 7
	Backend API: NVIDIA GeForce RTX 5080/PCIe/SSE2 GL version 4.6.0 NVIDIA 596.21, NVIDIA Corporation
	Window size: 1920x1009
	GL Caps: Using framebuffer using OpenGL 3.2
	GL debug messages: 
	Using VBOs: Yes
	Is Modded: Definitely; Client brand changed to 'forge'
	Type: Client (map_client.txt)
	Graphics mode: fancy
	Resource Packs: 
	Current Language: en_us
	CPU: 32x Intel(R) Core(TM) i9-14900KF
	ModLauncher: 10.0.9+10.0.9+main.dcd20f30
	ModLauncher launch target: forgeclient
	ModLauncher naming: srg
	ModLauncher services: 
		mixin-0.8.5.jar mixin PLUGINSERVICE 
		eventbus-6.2.33.jar eventbus PLUGINSERVICE 
		fmlloader-1.20.1-47.4.20.jar slf4jfixer PLUGINSERVICE 
		fmlloader-1.20.1-47.4.20.jar object_holder_definalize PLUGINSERVICE 
		fmlloader-1.20.1-47.4.20.jar runtime_enum_extender PLUGINSERVICE 
		fmlloader-1.20.1-47.4.20.jar capability_token_subclass PLUGINSERVICE 
		accesstransformers-8.0.4.jar accesstransformer PLUGINSERVICE 
		fmlloader-1.20.1-47.4.20.jar runtimedistcleaner PLUGINSERVICE 
		modlauncher-10.0.9.jar mixin TRANSFORMATIONSERVICE 
		modlauncher-10.0.9.jar essential-loader TRANSFORMATIONSERVICE 
		modlauncher-10.0.9.jar fml TRANSFORMATIONSERVICE 
	FML Language Providers: 
		minecraft@1.0
		lowcodefml@null
		javafml@null
	Mod List: 
		client-1.20.1-20230612.114412-srg.jar             |Minecraft                     |minecraft                     |1.20.1              |DONE      |Manifest: a1:d4:5e:04:4f:d3:d6:e0:7b:37:97:cf:77:b0:de:ad:4a:47:ce:8c:96:49:5f:0a:cf:8c:ae:b2:6d:4b:8a:3f
		naturalist-5.0pre2+forge-1.20.1.jar               |Naturalist                    |naturalist                    |5.0pre2             |DONE      |Manifest: NOSIGNATURE
		crowcommunication-1.0.0.jar                       |Crow Communication Mod        |crowcommunication             |1.0.0               |DONE      |Manifest: NOSIGNATURE
		mcef-forge-2.1.6-1.20.1.jar                       |MCEF (Minecraft Chromium Embed|mcef                          |2.1.6-1.20.1        |DONE      |Manifest: NOSIGNATURE
		forge-1.20.1-47.4.20-universal.jar                |Forge                         |forge                         |47.4.20             |DONE      |Manifest: NOSIGNATURE
		midnightlib-1.4.2-forge.jar                       |MidnightLib                   |midnightlib                   |1.4.2               |DONE      |Manifest: NOSIGNATURE
		geckolib-forge-1.20.1-4.8.3.jar                   |GeckoLib 4                    |geckolib                      |4.8.3               |DONE      |Manifest: NOSIGNATURE
		Essential (forge_1.20.1).jar                      |Essential                     |essential                     |1.3.10.8            |DONE      |Manifest: NOSIGNATURE
	Crash Report UUID: ccd0b913-6d00-4b8d-a301-9f707cdbefef
	FML: 47.4
	Forge: net.minecraftforge:47.4.20
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
                                        String senderName, String subject, String body, long delayTicks) {
        return scheduleDeliveryInternal(server, recipient.getUUID(), senderName, subject, body, delayTicks, false);
    }

    /**
     * Programme un renvoi vers le joueur identifié par {@code recipientUUID}. Marqué {@code isReturn}
     * pour que le timeout de second tour fasse tomber la lettre au sol au lieu de boucler.
     */
    private static UUID scheduleReturnDelivery(MinecraftServer server, UUID recipientUUID,
                                               String senderName, String subject, String body, long delayTicks) {
        return scheduleDeliveryInternal(server, recipientUUID, senderName, subject, body, delayTicks, true);
    }

    private static UUID scheduleDeliveryInternal(MinecraftServer server, UUID recipientUUID,
                                                 String senderName, String subject, String body,
                                                 long delayTicks, boolean isReturn) {
        long deliverAt = server.getTickCount() + delayTicks;
        UUID msgId = UUID.randomUUID();
        synchronized (PENDING) {
            PENDING.add(new PendingDelivery(recipientUUID, msgId, senderName, subject, body, deliverAt, isReturn));
        }
        return msgId;
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
                                            List<String> recipientNames, List<UUID> ids, List<Long> delays) {
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
            }
        }
    }

    private static void cancelDeliveries(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return;
        synchronized (PENDING) {
            PENDING.removeIf(d -> ids.contains(d.msgId));
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
        } else if (match.kind == Kind.SUMMON && match.phase == Phase.OUTGOING && match.outSubject != null) {
            // Corbeau porteur abattu en vol — lettre tombe, livraison annulée
            if (mob.level() instanceof ServerLevel sl) {
                ItemStack letter = makeLetterItem(match.player.getGameProfile().getName(), match.outSubject, match.outBody);
                Vec3 p = mob.position();
                ItemEntity drop = new ItemEntity(sl, p.x, p.y + 0.4, p.z, letter);
                drop.setDeltaMovement(0, 0.1, 0);
                sl.addFreshEntity(drop);
                sl.sendParticles(ParticleTypes.POOF, p.x, p.y + 0.5, p.z, 14, 0.4, 0.3, 0.4, 0.03);
                sl.sendParticles(ParticleTypes.ASH,  p.x, p.y + 0.5, p.z, 18, 0.5, 0.4, 0.5, 0.04);
            }
            cancelDeliveries(match.deliveryIds);
            match.player.sendSystemMessage(Component.literal(
                "§c§oTon corbeau a été abattu en plein vol — ta lettre est tombée quelque part."));
            if (event.getSource().getEntity() instanceof ServerPlayer killer
                    && !killer.getUUID().equals(match.player.getUUID())) {
                killer.sendSystemMessage(Component.literal(
                    "§e§oTu as abattu le messager de §f" + match.player.getGameProfile().getName()
                    + "§e — une lettre est tombée quelque part."));
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
                         .addLast(new QueuedMessage(d.msgId, d.sender, d.subject, d.body, d.isReturn));
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

                boolean carryingLetter = b.kind == Kind.SUMMON
                    && b.outSubject != null && b.outgoingDurationTicks > 0 && b.recipientName != null;

                if (carryingLetter) {
                    // Le corbeau suit le destinataire en temps réel — vitesse calée pour
                    // arriver à l'instant de livraison. Il reste vulnérable (interceptable).
                    MinecraftServer server = b.player.getServer();
                    ServerPlayer recipient = (server == null)
                        ? null : server.getPlayerList().getPlayerByName(b.recipientName);
                    Vec3 targetPos;
                    if (recipient != null && !recipient.isRemoved()
                            && recipient.level().dimension().equals(b.chicken.level().dimension())) {
                        targetPos = recipient.position().add(0, 1.6, 0);
                    } else {
                        targetPos = b.flyTo; // dernière direction connue, fallback
                    }
                    long ticksRemaining = Math.max(1L, b.outgoingDurationTicks - b.ticks);
                    double distRemaining = b.chicken.position().distanceTo(targetPos);
                    double speed = Math.max(0.4, Math.min(2.5, distRemaining / ticksRemaining));
                    boolean reached = moveTowardWithArc(b.chicken, targetPos, speed);
                    if (reached || b.ticks >= b.outgoingDurationTicks) {
                        poof(b.chicken);
                        b.chicken.discard();
                        toRemove.add(b);
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
                NetworkHandler.sendToClient(new PacketOpenCompose(recipients), b.player);
            }
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
        Component header = b.isReturn
            ? Component.literal("§6───────── §c§l↩ §6§lLettre retournée §6─────────")
            : Component.literal("§6───────── §e§l✉ §6§lUne lettre §6─────────");
        String metaPrefix = b.isReturn ? "§7Ta lettre §7· §f§o" : "§7De §b" + b.sender + " §7· §f§o";
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
                // Premier timeout — la lettre est renvoyée à l'expéditeur comme une nouvelle livraison.
                MinecraftServer server = b.player.getServer();
                UUID originalSenderUUID = null;
                if (server != null) {
                    ServerPlayer online = server.getPlayerList().getPlayerByName(b.sender);
                    if (online != null) {
                        originalSenderUUID = online.getUUID();
                    } else {
                        try {
                            originalSenderUUID = server.getProfileCache()
                                .get(b.sender).map(prof -> prof.getId()).orElse(null);
                        } catch (Throwable ignored) {}
                    }
                }
                if (server != null && originalSenderUUID != null) {
                    scheduleReturnDelivery(server, originalSenderUUID, b.sender, b.subject, b.body, 20L * 10L);
                }
                b.player.sendSystemMessage(Component.literal(
                    "§8§oLasse d'attendre, le corbeau s'envole — il rapporte la lettre à son expéditeur."));
            } else if (b.kind == Kind.DELIVERY && b.isReturn) {
                // Second timeout (retour ignoré) — la lettre tombe au sol devant l'expéditeur, sans auto-ramassage.
                if (b.player.level() instanceof ServerLevel sl) {
                    Vec3 dropPos = b.chicken.position().add(0, -0.5, 0);
                    ItemStack returned = makeLetterItem(b.sender, b.subject, b.body);
                    ItemEntity drop = new ItemEntity(sl, dropPos.x, dropPos.y, dropPos.z, returned);
                    drop.setDeltaMovement(0, 0.05, 0);
                    drop.setPickUpDelay(60); // 3 s avant qu'on puisse la ramasser, pour qu'elle soit visible
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
