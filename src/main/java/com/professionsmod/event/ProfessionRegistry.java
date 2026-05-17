package com.professionsmod.event;

import com.professionsmod.profession.Profession;
import com.professionsmod.profession.ProfessionType;
import com.professionsmod.profession.main.*;
import com.professionsmod.profession.secondary.*;

import java.util.EnumMap;
import java.util.Map;

public class ProfessionRegistry {

    private static final Map<ProfessionType, Profession> REGISTRY = new EnumMap<>(ProfessionType.class);

    static {
        REGISTRY.put(ProfessionType.GUERRIER, new GuerrierProfession());
        REGISTRY.put(ProfessionType.MINEUR, new MineurProfession());
        REGISTRY.put(ProfessionType.FERMIER, new FermierProfession());
        REGISTRY.put(ProfessionType.ALCHIMISTE, new AlchimisteProfession());
        REGISTRY.put(ProfessionType.BATISSEUR, new BatisseurProfession());

        REGISTRY.put(ProfessionType.ENCHANTEUR, new EnchanterProfession());
        REGISTRY.put(ProfessionType.BUCHERON, new BucheronProfession());
        REGISTRY.put(ProfessionType.PECHEUR, new PecheurProfession());
        REGISTRY.put(ProfessionType.CHASSEUR, new ChasseurProfession());
        REGISTRY.put(ProfessionType.CUISINIER, new CuisinierProfession());
    }

    public static Profession get(ProfessionType type) {
        return REGISTRY.get(type);
    }

    public static Map<ProfessionType, Profession> getAll() {
        return REGISTRY;
    }
}
