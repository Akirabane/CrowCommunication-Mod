package com.professionsmod.event;

import com.professionsmod.client.gui.ProfessionScreen;
import com.professionsmod.init.ModKeybinds;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@OnlyIn(Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        if (ModKeybinds.openProfessionMenu != null && ModKeybinds.openProfessionMenu.consumeClick()) {
            mc.setScreen(new ProfessionScreen());
        }
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        if (ModKeybinds.openProfessionMenu != null) {
            event.register(ModKeybinds.openProfessionMenu);
        }
    }
}
