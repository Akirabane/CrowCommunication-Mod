package com.professionsmod.profession.main;

import com.professionsmod.profession.Profession;
import com.professionsmod.profession.ProfessionType;
import com.professionsmod.skill.Skill;
import com.professionsmod.skill.Skill.SkillEffect;
import com.professionsmod.skill.SkillNode;
import com.professionsmod.skill.SkillTree;

public class FermierProfession extends Profession {

    public FermierProfession() {
        super(ProfessionType.FERMIER);
    }

    @Override
    protected void buildSkillTree(SkillTree tree) {
        SkillNode pouce = new SkillNode(new Skill("fermier_pouce", "Pouce Vert", "+15% de rendement des cultures", 1, SkillEffect.POUCE_VERT), 0.5f, 0.1f);
        SkillNode recolte = new SkillNode(new Skill("fermier_recolte", "Récolte Double", "20% de chance de doubler la récolte", 1, SkillEffect.RECOLTE_DOUBLE), 0.2f, 0.2f);
        SkillNode croissance = new SkillNode(new Skill("fermier_croissance", "Croissance Rapide", "Cultures poussent 30% plus vite", 1, SkillEffect.CROISSANCE_RAPIDE), 0.8f, 0.2f);

        SkillNode chute = new SkillNode(new Skill("fermier_chute", "Amorti par la Terre", "Immunité aux dégâts de chute", 2, SkillEffect.RESISTANCE_CHUTES), 0.2f, 0.45f, "fermier_recolte");
        SkillNode animaux = new SkillNode(new Skill("fermier_animaux", "Ami des Animaux", "Les animaux vous font confiance, +XP élevage", 2, SkillEffect.AMI_DES_ANIMAUX), 0.5f, 0.4f, "fermier_pouce");
        SkillNode irrigation = new SkillNode(new Skill("fermier_irrigation", "Irrigation Naturelle", "L'eau fertilise automatiquement", 2, SkillEffect.IRRIGATION), 0.8f, 0.45f, "fermier_croissance");

        SkillNode compost = new SkillNode(new Skill("fermier_compost", "Maître Composteur", "Composteur 2x plus efficace", 3, SkillEffect.COMPOST), 0.25f, 0.7f, "fermier_animaux", "fermier_chute");
        SkillNode graine = new SkillNode(new Skill("fermier_graine", "Graine d'Or", "Planter crée une aura de croissance (3 blocs)", 3, SkillEffect.GRAINE_OR), 0.5f, 0.75f, "fermier_animaux", "fermier_irrigation");
        SkillNode abondance = new SkillNode(new Skill("fermier_abondance", "Abondance", "La nourriture redonne +2 niveaux de saturation", 3, SkillEffect.ABONDANCE), 0.75f, 0.7f, "fermier_irrigation");

        tree.addNode(pouce);
        tree.addNode(recolte);
        tree.addNode(croissance);
        tree.addNode(chute);
        tree.addNode(animaux);
        tree.addNode(irrigation);
        tree.addNode(compost);
        tree.addNode(graine);
        tree.addNode(abondance);
    }
}
