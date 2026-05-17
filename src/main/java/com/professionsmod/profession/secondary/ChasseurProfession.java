package com.professionsmod.profession.secondary;

import com.professionsmod.profession.Profession;
import com.professionsmod.profession.ProfessionType;
import com.professionsmod.skill.Skill;
import com.professionsmod.skill.Skill.SkillEffect;
import com.professionsmod.skill.SkillNode;
import com.professionsmod.skill.SkillTree;

public class ChasseurProfession extends Profession {

    public ChasseurProfession() {
        super(ProfessionType.CHASSEUR);
    }

    @Override
    protected void buildSkillTree(SkillTree tree) {
        SkillNode traque = new SkillNode(new Skill("chas_traque", "Traque", "Voir les traces d'animaux sauvages à 15 blocs", 1, SkillEffect.TRAQUE), 0.3f, 0.15f);
        SkillNode furtivite = new SkillNode(new Skill("chas_furtivite", "Furtivité", "Se déplacer sans attirer les monstres (crouching)", 1, SkillEffect.FURTIVITE), 0.7f, 0.15f);
        SkillNode lame = new SkillNode(new Skill("chas_lame", "Lame du Chasseur", "+20% dégâts sur les animaux et monstres du Nether", 2, SkillEffect.LAME_CHASSEUR), 0.3f, 0.5f, "chas_traque");
        SkillNode instinct = new SkillNode(new Skill("chas_instinct", "Instinct Prédateur", "Voir la santé des monstres ciblés", 2, SkillEffect.INSTINCT_PREDATEUR), 0.7f, 0.5f, "chas_furtivite");
        SkillNode maitrise = new SkillNode(new Skill("chas_maitrise", "Maître Chasseur", "Les monstres lâchent 2x plus de loot", 3, SkillEffect.CHASSEUR_MAITRISE), 0.5f, 0.85f, "chas_lame", "chas_instinct");

        tree.addNode(traque);
        tree.addNode(furtivite);
        tree.addNode(lame);
        tree.addNode(instinct);
        tree.addNode(maitrise);
    }
}
