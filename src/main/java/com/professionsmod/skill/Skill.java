package com.professionsmod.skill;

public class Skill {

    private final String id;
    private final String name;
    private final String description;
    private final SkillEffect effect;
    private final int tier;

    public Skill(String id, String name, String description, int tier, SkillEffect effect) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.tier = tier;
        this.effect = effect;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getTier() { return tier; }
    public SkillEffect getEffect() { return effect; }

    public enum SkillEffect {
        // Guerrier
        FORCE_BRUTE, RESISTANCE, CRITIQUE, ESQUIVE, RAGE, GARDE, TRANCHANT, INSTINCT, BRAVOURE,
        // Mineur
        EFFICACITE, TOUCHER_DE_SOIE_BONUS, FORTUNE_BONUS, VITESSE_MINAGE, DETECTION_MINERAIS, ROBUSTESSE, EXPLOSIF, NUIT_BLANCHE, ENDURANCE,
        // Fermier
        POUCE_VERT, RECOLTE_DOUBLE, CROISSANCE_RAPIDE, RESISTANCE_CHUTES, AMI_DES_ANIMAUX, IRRIGATION, COMPOST, GRAINE_OR, ABONDANCE,
        // Alchimiste
        DOSAGE_PRECIS, DUREE_PROLONGEE, PUISSANCE_AUGMENTEE, RESISTANCE_POISONS, ALCHIMIE_AVANCEE, TRANSMUTATION, DISTILLATION, CATALYSEUR, MAITRISE_ALCHIMIE,
        // Bâtisseur
        PLACEMENT_RAPIDE, PORTEE_ETENDUE, ECONOMIE_MATERIAUX, VISION_ARCHITECTURALE, STABILITE, ESTHETIQUE, CONSTRUCTION_MASSIVE, INGENIERIE, MAITRISE_BATISSEUR,
        // Enchanteur
        EXPERIENCE_BONUS, ENCHANTEMENT_AMELIORE, CONNAISSANCE_ANCIENNE, MAGIE_PRECISE, ENCHANTEUR_MAITRISE,
        // Bûcheron
        ABATTAGE_RAPIDE, BOIS_EXTRA, REPLANTATION, COUPE_NETTE, BUCHERON_MAITRISE,
        // Pêcheur
        APPAT_AMELIORE, PECHE_RAPIDE, TRESOR_DES_MERS, SANTE_MARINE, PECHEUR_MAITRISE,
        // Chasseur
        TRAQUE, FURTIVITE, LAME_CHASSEUR, INSTINCT_PREDATEUR, CHASSEUR_MAITRISE,
        // Cuisinier
        RECETTE_AMELIOREE, FESTIN, GOUT_FIN, CONSERVATION, CUISINIER_MAITRISE,

        NONE
    }
}
