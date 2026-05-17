package com.professionsmod.profession.secondary;

import com.professionsmod.profession.Profession;
import com.professionsmod.profession.ProfessionType;
import com.professionsmod.skill.Skill;
import com.professionsmod.skill.Skill.SkillEffect;
import com.professionsmod.skill.SkillNode;
import com.professionsmod.skill.SkillTree;

public class CuisinierProfession extends Profession {

    public CuisinierProfession() {
        super(ProfessionType.CUISINIER);
    }

    @Override
    protected void buildSkillTree(SkillTree tree) {
        SkillNode recette = new SkillNode(new Skill("cuis_recette", "Recette Améliorée", "La nourriture cuite restaure +1 faim", 1, SkillEffect.RECETTE_AMELIOREE), 0.3f, 0.15f);
        SkillNode festin = new SkillNode(new Skill("cuis_festin", "Festin", "Cuire plusieurs items à la fois au four", 1, SkillEffect.FESTIN), 0.7f, 0.15f);
        SkillNode gout = new SkillNode(new Skill("cuis_gout", "Goût Fin", "Manger donne des effets aléatoires positifs (30s)", 2, SkillEffect.GOUT_FIN), 0.3f, 0.5f, "cuis_recette");
        SkillNode conservation = new SkillNode(new Skill("cuis_conservation", "Conservation", "La nourriture dans votre inventaire ne se périme pas", 2, SkillEffect.CONSERVATION), 0.7f, 0.5f, "cuis_festin");
        SkillNode maitrise = new SkillNode(new Skill("cuis_maitrise", "Grand Chef", "Créer des repas spéciaux avec des effets de potion", 3, SkillEffect.CUISINIER_MAITRISE), 0.5f, 0.85f, "cuis_gout", "cuis_conservation");

        tree.addNode(recette);
        tree.addNode(festin);
        tree.addNode(gout);
        tree.addNode(conservation);
        tree.addNode(maitrise);
    }
}
