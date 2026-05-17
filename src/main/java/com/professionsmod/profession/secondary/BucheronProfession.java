package com.professionsmod.profession.secondary;

import com.professionsmod.profession.Profession;
import com.professionsmod.profession.ProfessionType;
import com.professionsmod.skill.Skill;
import com.professionsmod.skill.Skill.SkillEffect;
import com.professionsmod.skill.SkillNode;
import com.professionsmod.skill.SkillTree;

public class BucheronProfession extends Profession {

    public BucheronProfession() {
        super(ProfessionType.BUCHERON);
    }

    @Override
    protected void buildSkillTree(SkillTree tree) {
        SkillNode abattage = new SkillNode(new Skill("buch_abattage", "Abattage Rapide", "+40% vitesse de coupe de bois", 1, SkillEffect.ABATTAGE_RAPIDE), 0.3f, 0.15f);
        SkillNode boisExtra = new SkillNode(new Skill("buch_bois", "Coupe Extra", "+1 bois par arbre coupé", 1, SkillEffect.BOIS_EXTRA), 0.7f, 0.15f);
        SkillNode replantation = new SkillNode(new Skill("buch_replant", "Replantation Auto", "Plante automatiquement un plant en coupant", 2, SkillEffect.REPLANTATION), 0.3f, 0.5f, "buch_abattage");
        SkillNode nette = new SkillNode(new Skill("buch_nette", "Coupe Nette", "Abattre un arbre entier d'un coup (CD 15s)", 2, SkillEffect.COUPE_NETTE), 0.7f, 0.5f, "buch_bois");
        SkillNode maitrise = new SkillNode(new Skill("buch_maitrise", "Maître Bûcheron", "Couper un arbre donne des pommes et champignons", 3, SkillEffect.BUCHERON_MAITRISE), 0.5f, 0.85f, "buch_replant", "buch_nette");

        tree.addNode(abattage);
        tree.addNode(boisExtra);
        tree.addNode(replantation);
        tree.addNode(nette);
        tree.addNode(maitrise);
    }
}
