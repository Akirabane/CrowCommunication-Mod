package com.professionsmod.event;

import com.professionsmod.capability.PlayerDataCapability;
import com.professionsmod.data.PlayerProfessionData;
import com.professionsmod.network.NetworkHandler;
import com.professionsmod.network.PacketSyncData;
import com.professionsmod.profession.ProfessionType;
import com.professionsmod.skill.Skill.SkillEffect;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class ProfessionXPEvents {

    // === GUERRIER: XP en tuant des monstres ===
    @SubscribeEvent
    public static void onMobKill(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        PlayerProfessionData data = PlayerDataCapability.get(player);

        if (data.isActiveProfession(ProfessionType.GUERRIER)) {
            int xp = (event.getEntity() instanceof Monster) ? 15 : 5;
            int oldLevel = data.getLevel(ProfessionType.GUERRIER);
            data.addXP(ProfessionType.GUERRIER, xp);
            checkLevelUp(player, data, ProfessionType.GUERRIER, oldLevel);
            NetworkHandler.sendToClient(PacketSyncData.from(data), player);
        }

        if (data.isActiveProfession(ProfessionType.CHASSEUR)) {
            int xp = (event.getEntity() instanceof Animal) ? 20 : 8;
            int oldLevel = data.getLevel(ProfessionType.CHASSEUR);
            data.addXP(ProfessionType.CHASSEUR, xp);
            checkLevelUp(player, data, ProfessionType.CHASSEUR, oldLevel);
            NetworkHandler.sendToClient(PacketSyncData.from(data), player);
        }
    }

    // === MINEUR: XP en minant des blocs ===
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        PlayerProfessionData data = PlayerDataCapability.get(player);
        Block block = event.getState().getBlock();

        if (data.isActiveProfession(ProfessionType.MINEUR)) {
            int xp = 0;
            if (event.getState().is(Tags.Blocks.ORES)) xp = 25;
            else if (event.getState().is(Tags.Blocks.STONE)) xp = 3;
            else if (block instanceof SandBlock || block instanceof GravelBlock) xp = 2;
            if (xp > 0) {
                int oldLevel = data.getLevel(ProfessionType.MINEUR);
                data.addXP(ProfessionType.MINEUR, xp);
                checkLevelUp(player, data, ProfessionType.MINEUR, oldLevel);
                NetworkHandler.sendToClient(PacketSyncData.from(data), player);
            }
        }

        if (data.isActiveProfession(ProfessionType.BUCHERON)) {
            if (block instanceof LeavesBlock || block instanceof RotatedPillarBlock) {
                String name = block.getClass().getSimpleName().toLowerCase();
                if (name.contains("log") || name.contains("wood")) {
                    int oldLevel = data.getLevel(ProfessionType.BUCHERON);
                    data.addXP(ProfessionType.BUCHERON, 10);
                    checkLevelUp(player, data, ProfessionType.BUCHERON, oldLevel);
                    NetworkHandler.sendToClient(PacketSyncData.from(data), player);
                }
            }
        }

        if (data.isActiveProfession(ProfessionType.BATISSEUR)) {
        }
    }

    // === FERMIER: XP en récoltant des cultures ===
    @SubscribeEvent
    public static void onCropBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        PlayerProfessionData data = PlayerDataCapability.get(player);

        if (data.isActiveProfession(ProfessionType.FERMIER)) {
            Block block = event.getState().getBlock();
            if (block instanceof CropBlock || block instanceof StemGrownBlock) {
                int oldLevel = data.getLevel(ProfessionType.FERMIER);
                data.addXP(ProfessionType.FERMIER, 12);
                checkLevelUp(player, data, ProfessionType.FERMIER, oldLevel);
                NetworkHandler.sendToClient(PacketSyncData.from(data), player);
            }
        }
    }

    // === BATISSEUR: XP en posant des blocs ===
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PlayerProfessionData data = PlayerDataCapability.get(player);

        if (data.isActiveProfession(ProfessionType.BATISSEUR)) {
            int oldLevel = data.getLevel(ProfessionType.BATISSEUR);
            data.addXP(ProfessionType.BATISSEUR, 2);
            checkLevelUp(player, data, ProfessionType.BATISSEUR, oldLevel);
            NetworkHandler.sendToClient(PacketSyncData.from(data), player);
        }
    }

    // === ALCHIMISTE: XP en brassant des potions ===
    // (handled via BrewingStandMenu interaction — tracked by item use event)
    @SubscribeEvent
    public static void onItemCraft(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PlayerProfessionData data = PlayerDataCapability.get(player);

        if (data.isActiveProfession(ProfessionType.CUISINIER)) {
            var item = event.getCrafting().getItem();
            if (item.isEdible()) {
                int oldLevel = data.getLevel(ProfessionType.CUISINIER);
                data.addXP(ProfessionType.CUISINIER, 15);
                checkLevelUp(player, data, ProfessionType.CUISINIER, oldLevel);
                NetworkHandler.sendToClient(PacketSyncData.from(data), player);
            }
        }
    }

    // === PECHEUR: XP pris en charge via FishingHook ===
    @SubscribeEvent
    public static void onFishCatch(PlayerEvent.ItemPickupEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PlayerProfessionData data = PlayerDataCapability.get(player);

        if (data.isActiveProfession(ProfessionType.PECHEUR)) {
            var item = event.getStack().getItem();
            if (item == Items.COD || item == Items.SALMON || item == Items.PUFFERFISH || item == Items.TROPICAL_FISH) {
                int oldLevel = data.getLevel(ProfessionType.PECHEUR);
                data.addXP(ProfessionType.PECHEUR, 20);
                checkLevelUp(player, data, ProfessionType.PECHEUR, oldLevel);
                NetworkHandler.sendToClient(PacketSyncData.from(data), player);
            }
        }
    }

    // === ENCHANTEUR: XP lors des enchantements ===
    @SubscribeEvent
    public static void onEnchant(net.minecraftforge.event.enchanting.EnchantmentLevelSetEvent event) {
        // XP géré via le menu d'enchantement
    }

    private static void checkLevelUp(ServerPlayer player, PlayerProfessionData data, ProfessionType type, int oldLevel) {
        int newLevel = data.getLevel(type);
        if (newLevel > oldLevel) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§6[Professions] §aVous êtes maintenant niveau " + newLevel + " en " + type.getDisplayName() + "!"
            ));
            if (newLevel == ProfessionType.MAX_LEVEL && type.hasSpecialty()) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§6[Professions] §d✦ Spécialité débloquée: " + type.getSpecialtyName() + "!"
                ));
            }
        }
    }
}
