package com.professionsmod.profession.main;

import com.professionsmod.profession.Profession;
import com.professionsmod.profession.ProfessionType;
import com.professionsmod.skill.Skill;
import com.professionsmod.skill.Skill.SkillEffect;
import com.professionsmod.skill.SkillNode;
import com.professionsmod.skill.SkillTree;

public class GuerrierProfession extends Profession {

    public GuerrierProfession() {
        super(ProfessionType.GUERRIER);
    }

    @Override
    protected void buildSkillTree(SkillTree tree) {
        // Tier 1
        SkillNode force = new SkillNode(new Skill("guerrier_force", "Force Brute", "+10% de dégâts", 1, SkillEffect.FORCE_BRUTE), 0.5f, 0.1f);
        SkillNode resistance = new SkillNode(new Skill("guerrier_resistance", "Résistance", "+10% de résistance aux dégâts", 1, SkillEffect.RESISTANCE), 0.2f, 0.2f);
        SkillNode critique = new SkillNode(new Skill("guerrier_critique", "Frappe Critique", "+5% de chance de coup critique", 1, SkillEffect.CRITIQUE), 0.8f, 0.2f);

        // Tier 2 (nécessite tier 1)
        SkillNode esquive = new SkillNode(new Skill("guerrier_esquive", "Esquive", "+8% de chance d'esquiver", 2, SkillEffect.ESQUIVE), 0.2f, 0.45f, "guerrier_resistance");
        SkillNode rage = new SkillNode(new Skill("guerrier_rage", "Rage", "Dégâts +5% par ennemi tué (max 50%)", 2, SkillEffect.RAGE), 0.5f, 0.4f, "guerrier_force");
        SkillNode garde = new SkillNode(new Skill("guerrier_garde", "Garde", "Bloquer réduit les dégâts de 30%", 2, SkillEffect.GARDE), 0.8f, 0.45f, "guerrier_critique");

        // Tier 3 (nécessite tier 2)
        SkillNode tranchant = new SkillNode(new Skill("guerrier_tranchant", "Lame Tranchante", "Ignore 20% de l'armure ennemie", 3, SkillEffect.TRANCHANT), 0.3f, 0.7f, "guerrier_rage", "guerrier_esquive");
        SkillNode instinct = new SkillNode(new Skill("guerrier_instinct", "Instinct de Survie", "Régénère 2 PV/s quand < 50% de vie", 3, SkillEffect.INSTINCT), 0.5f, 0.75f, "guerrier_rage");
        SkillNode bravoure = new SkillNode(new Skill("guerrier_bravoure", "Bravoure", "+15% de dégâts si seul contre plusieurs", 3, SkillEffect.BRAVOURE), 0.7f, 0.7f, "guerrier_rage", "guerrier_garde");

        tree.addNode(force);
        tree.addNode(resistance);
        tree.addNode(critique);
        tree.addNode(esquive);
        tree.addNode(rage);
        tree.addNode(garde);
        tree.addNode(tranchant);
        tree.addNode(instinct);
        tree.addNode(bravoure);
    }
}
