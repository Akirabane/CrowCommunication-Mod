package com.professionsmod.init;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public class ModKeybinds {

    public static KeyMapping openProfessionMenu;

    public static void register() {
        openProfessionMenu = new KeyMapping(
            "key.professionsmod.open_menu",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P,
            "key.categories.professionsmod"
        );
    }
}
