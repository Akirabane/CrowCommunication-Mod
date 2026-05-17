package com.professionsmod;

import com.professionsmod.capability.PlayerDataCapability;
import com.professionsmod.event.ClientEvents;
import com.professionsmod.event.ProfessionXPEvents;
import com.professionsmod.init.ModKeybinds;
import com.professionsmod.network.NetworkHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ProfessionsMod.MODID)
public class ProfessionsMod {

    public static final String MODID = "professionsmod";

    public ProfessionsMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);

        MinecraftForge.EVENT_BUS.register(ProfessionXPEvents.class);
        MinecraftForge.EVENT_BUS.register(PlayerDataCapability.class);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(NetworkHandler::init);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ModKeybinds.register();
            MinecraftForge.EVENT_BUS.register(ClientEvents.class);
        });
    }
}
