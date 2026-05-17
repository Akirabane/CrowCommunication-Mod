package com.professionsmod.capability;

import com.professionsmod.ProfessionsMod;
import com.professionsmod.data.PlayerProfessionData;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.*;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class PlayerDataCapability implements ICapabilitySerializable<CompoundTag> {

    public static final Capability<PlayerProfessionData> PROFESSION_DATA = CapabilityManager.get(new CapabilityToken<>() {});
    public static final ResourceLocation ID = new ResourceLocation(ProfessionsMod.MODID, "profession_data");

    private final PlayerProfessionData data = new PlayerProfessionData();
    private final LazyOptional<PlayerProfessionData> optional = LazyOptional.of(() -> data);

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
        return PROFESSION_DATA.orEmpty(cap, optional);
    }

    @Override
    public CompoundTag serializeNBT() {
        return data.save();
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        data.load(tag);
    }

    public static PlayerProfessionData get(Player player) {
        return player.getCapability(PROFESSION_DATA).orElseThrow(() -> new RuntimeException("PlayerProfessionData capability missing"));
    }

    public static LazyOptional<PlayerProfessionData> getOptional(Player player) {
        return player.getCapability(PROFESSION_DATA);
    }

    @SubscribeEvent
    public static void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof Player) {
            event.addCapability(ID, new PlayerDataCapability());
        }
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath() && !event.getEntity().level().isClientSide) return;
        event.getOriginal().reviveCaps();
        getOptional(event.getOriginal()).ifPresent(oldData ->
            getOptional(event.getEntity()).ifPresent(newData -> newData.copyFrom(oldData))
        );
        event.getOriginal().invalidateCaps();
    }
}
