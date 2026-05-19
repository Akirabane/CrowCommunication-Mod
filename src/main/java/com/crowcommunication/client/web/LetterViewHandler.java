package com.crowcommunication.client.web;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Ouvre une interface MCEF en lecture seule au clic droit sur un item lettre.
 *
 * <p>La lettre est identifiée par la présence du tag NBT {@code crow_sender} posé par
 * {@link com.crowcommunication.corbeau.LetterRenderer#makeLetterItem}.</p>
 */
@OnlyIn(Dist.CLIENT)
public class LetterViewHandler {

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        ItemStack stack = event.getItemStack();
        if (!stack.is(Items.PAPER)) return;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("crow_sender")) return;

        event.setCanceled(true);

        String sender  = tag.getString("crow_sender");
        String subject = tag.getString("crow_subject");
        String body    = tag.getString("crow_body");

        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> LetterViewOpener.open(sender, subject, body));
    }
}
