package com.crowcommunication;

import com.crowcommunication.network.NetworkHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(CrowCommunicationMod.MODID)
public class CrowCommunicationMod {

    public static final String MODID = "crowcommunication";

    public CrowCommunicationMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);

        MinecraftForge.EVENT_BUS.register(com.crowcommunication.corbeau.CorbeauCommand.class);
        MinecraftForge.EVENT_BUS.register(com.crowcommunication.corbeau.CorbeauChoiceCommand.class);
        MinecraftForge.EVENT_BUS.register(com.crowcommunication.corbeau.CorbeauManager.class);
        MinecraftForge.EVENT_BUS.register(com.crowcommunication.corbeau.ClientReadyState.class);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(NetworkHandler::init);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MinecraftForge.EVENT_BUS.register(com.crowcommunication.client.web.ClientLoginHook.class);
            com.crowcommunication.client.web.MCEFBootstrap.init();
        });
    }
}
