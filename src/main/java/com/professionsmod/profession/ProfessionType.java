package com.professionsmod.profession;

public enum ProfessionType {
    // Métiers principaux
    GUERRIER("Guerrier", true, "Berserker", "Dégâts +50% sous 30% de vie"),
    MINEUR("Mineur", true, "Géologue", "Voir les minerais à travers les blocs"),
    FERMIER("Fermier", true, "Druide", "Les plantes poussent 3x plus vite autour de toi"),
    ALCHIMISTE("Alchimiste", true, "Sorcier", "Les potions affectent aussi les alliés proches"),
    BATISSEUR("Bâtisseur", true, "Architecte", "Placer des blocs en 3x3"),

    // Métiers secondaires
    ENCHANTEUR("Enchanteur", false, null, null),
    BUCHERON("Bûcheron", false, null, null),
    PECHEUR("Pêcheur", false, null, null),
    CHASSEUR("Chasseur", false, null, null),
    CUISINIER("Cuisinier", false, null, null);

    private final String displayName;
    private final boolean isMain;
    private final String specialtyName;
    private final String specialtyDescription;

    ProfessionType(String displayName, boolean isMain, String specialtyName, String specialtyDescription) {
        this.displayName = displayName;
        this.isMain = isMain;
        this.specialtyName = specialtyName;
        this.specialtyDescription = specialtyDescription;
    }

    public String getDisplayName() { return displayName; }
    public boolean isMain() { return isMain; }
    public boolean isSecondary() { return !isMain; }
    public String getSpecialtyName() { return specialtyName; }
    public String getSpecialtyDescription() { return specialtyDescription; }
    public boolean hasSpecialty() { return specialtyName != null; }

    public static final int MAX_LEVEL = 10;
    public static final int[] XP_PER_LEVEL = {0, 100, 250, 500, 1000, 2000, 3500, 5000, 7500, 10000};
}
