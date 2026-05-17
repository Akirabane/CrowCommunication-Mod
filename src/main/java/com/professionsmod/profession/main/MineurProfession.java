package com.professionsmod.profession.main;

import com.professionsmod.profession.Profession;
import com.professionsmod.profession.ProfessionType;
import com.professionsmod.skill.Skill;
import com.professionsmod.skill.Skill.SkillEffect;
import com.professionsmod.skill.SkillNode;
import com.professionsmod.skill.SkillTree;

public class MineurProfession extends Profession {

    public MineurProfession() {
        super(ProfessionType.MINEUR);
    }

    @Override
    protected void buildSkillTree(SkillTree tree) {
        // Tier 1
        SkillNode efficacite = new SkillNode(new Skill("mineur_efficacite", "Efficacité", "+20% vitesse de minage", 1, SkillEffect.EFFICACITE), 0.5f, 0.1f);
        SkillNode soie = new SkillNode(new Skill("mineur_soie", "Toucher Délicat", "Chance de toucher de soie naturelle", 1, SkillEffect.TOUCHER_DE_SOIE_BONUS), 0.2f, 0.2f);
        SkillNode fortune = new SkillNode(new Skill("mineur_fortune", "Fortune Naturelle", "+1 niveau de fortune implicite", 1, SkillEffect.FORTUNE_BONUS), 0.8f, 0.2f);

        // Tier 2
        SkillNode vitesse = new SkillNode(new Skill("mineur_vitesse", "Vitesse de Minage", "Miner sans fatigue", 2, SkillEffect.VITESSE_MINAGE), 0.5f, 0.4f, "mineur_efficacite");
        SkillNode detection = new SkillNode(new Skill("mineur_detection", "Détection", "Ressent les minerais à 5 blocs", 2, SkillEffect.DETECTION_MINERAIS), 0.2f, 0.45f, "mineur_soie");
        SkillNode robustesse = new SkillNode(new Skill("mineur_robustesse", "Robustesse", "+20 PV max sous terre", 2, SkillEffect.ROBUSTESSE), 0.8f, 0.45f, "mineur_fortune");

        // Tier 3
        SkillNode explosif = new SkillNode(new Skill("mineur_explosif", "Minage Explosif", "Mine un cube 3x3 à chaque frappe (CD 30s)", 3, SkillEffect.EXPLOSIF), 0.5f, 0.75f, "mineur_vitesse");
        SkillNode nuit = new SkillNode(new Skill("mineur_nuit", "Œil de Nuit", "Vision nocturne permanente sous Y=32", 3, SkillEffect.NUIT_BLANCHE), 0.25f, 0.7f, "mineur_detection", "mineur_vitesse");
        SkillNode endurance = new SkillNode(new Skill("mineur_endurance", "Endurance", "Immunité à la fatigue de minage", 3, SkillEffect.ENDURANCE), 0.75f, 0.7f, "mineur_robustesse", "mineur_vitesse");

        tree.addNode(efficacite);
        tree.addNode(soie);
        tree.addNode(fortune);
        tree.addNode(vitesse);
        tree.addNode(detection);
        tree.addNode(robustesse);
        tree.addNode(explosif);
        tree.addNode(nuit);
        tree.addNode(endurance);
    }
}
