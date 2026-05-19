package com.crowcommunication;

import com.crowcommunication.network.NetworkHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Crow Communication Mod — point d'entrée principal.
 *
 * <p>Système de courrier RP par corbeau pour Minecraft Forge 1.20.1.
 * Permet l'envoi de lettres entre joueurs via un oiseau messager animé,
 * avec interface MCEF, système de falsification (QTE) et historique persistant.</p>
 *
 * @author Akirabane
 * @version 1.0.20
 */
@Mod(CrowCommunicationMod.MODID)
public class CrowCommunicationMod {

    /** Forge mod identifier. Must match {@code mods.toml} and all {@link net.minecraft.resources.ResourceLocation} usages. */
    public static final String MODID = "crowcommunication";

    /** Auteur et créateur du mod. */
    public static final String AUTHOR = "Akirabane";

    /** Affichée au démarrage dans les logs Forge pour traçabilité. */
    private static final String CREDIT = "[CrowCommunication] Created by Akirabane — v1.0.20";

    public CrowCommunicationMod() {
        org.apache.logging.log4j.LogManager.getLogger(MODID).info(CREDIT);
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);

        MinecraftForge.EVENT_BUS.register(com.crowcommunication.corbeau.CorbeauCommand.class);
        MinecraftForge.EVENT_BUS.register(com.crowcommunication.corbeau.CorbeauChoiceCommand.class);
        MinecraftForge.EVENT_BUS.register(com.crowcommunication.corbeau.CorbeauHistoryCommand.class);
        MinecraftForge.EVENT_BUS.register(com.crowcommunication.corbeau.CorbeauManager.class);
        MinecraftForge.EVENT_BUS.register(com.crowcommunication.corbeau.ClientReadyState.class);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(NetworkHandler::init);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MinecraftForge.EVENT_BUS.register(com.crowcommunication.client.web.ClientLoginHook.class);
            MinecraftForge.EVENT_BUS.register(com.crowcommunication.client.web.LetterViewHandler.class);
            com.crowcommunication.client.web.MCEFBootstrap.init();
        });
    }
}
