package com.professionsmod.profession.secondary;

import com.professionsmod.profession.Profession;
import com.professionsmod.profession.ProfessionType;
import com.professionsmod.skill.Skill;
import com.professionsmod.skill.Skill.SkillEffect;
import com.professionsmod.skill.SkillNode;
import com.professionsmod.skill.SkillTree;

public class EnchanterProfession extends Profession {

    public EnchanterProfession() {
        super(ProfessionType.ENCHANTEUR);
    }

    @Override
    protected void buildSkillTree(SkillTree tree) {
        SkillNode xp = new SkillNode(new Skill("enc_xp", "Affinité Magique", "+25% d'XP gagnée en enchantant", 1, SkillEffect.EXPERIENCE_BONUS), 0.3f, 0.15f);
        SkillNode ameliore = new SkillNode(new Skill("enc_ameliore", "Enchantement Amélioré", "Enchantements de niveau supérieur disponibles", 1, SkillEffect.ENCHANTEMENT_AMELIORE), 0.7f, 0.15f);
        SkillNode ancienne = new SkillNode(new Skill("enc_ancienne", "Connaissance Ancienne", "Voir tous les enchantements possibles avant de choisir", 2, SkillEffect.CONNAISSANCE_ANCIENNE), 0.3f, 0.5f, "enc_xp");
        SkillNode precise = new SkillNode(new Skill("enc_precise", "Magie Précise", "Enchanter sans dépenser de lapis parfois", 2, SkillEffect.MAGIE_PRECISE), 0.7f, 0.5f, "enc_ameliore");
        SkillNode maitrise = new SkillNode(new Skill("enc_maitrise", "Maître Enchanteur", "Tous les enchantements au max cost -30% de niveaux", 3, SkillEffect.ENCHANTEUR_MAITRISE), 0.5f, 0.85f, "enc_ancienne", "enc_precise");

        tree.addNode(xp);
        tree.addNode(ameliore);
        tree.addNode(ancienne);
        tree.addNode(precise);
        tree.addNode(maitrise);
    }
}
