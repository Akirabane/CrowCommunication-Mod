package com.professionsmod.profession.secondary;

import com.professionsmod.profession.Profession;
import com.professionsmod.profession.ProfessionType;
import com.professionsmod.skill.Skill;
import com.professionsmod.skill.Skill.SkillEffect;
import com.professionsmod.skill.SkillNode;
import com.professionsmod.skill.SkillTree;

public class PecheurProfession extends Profession {

    public PecheurProfession() {
        super(ProfessionType.PECHEUR);
    }

    @Override
    protected void buildSkillTree(SkillTree tree) {
        SkillNode appat = new SkillNode(new Skill("pech_appat", "Appât Amélioré", "Poisson mord 40% plus vite", 1, SkillEffect.APPAT_AMELIORE), 0.3f, 0.15f);
        SkillNode rapide = new SkillNode(new Skill("pech_rapide", "Pêche Rapide", "Retirer l'hameçon 25% plus vite", 1, SkillEffect.PECHE_RAPIDE), 0.7f, 0.15f);
        SkillNode tresor = new SkillNode(new Skill("pech_tresor", "Trésor des Mers", "+15% de chance de trouver des trésors rares", 2, SkillEffect.TRESOR_DES_MERS), 0.3f, 0.5f, "pech_appat");
        SkillNode sante = new SkillNode(new Skill("pech_sante", "Santé Marine", "Régénération lente en étant dans l'eau", 2, SkillEffect.SANTE_MARINE), 0.7f, 0.5f, "pech_rapide");
        SkillNode maitrise = new SkillNode(new Skill("pech_maitrise", "Maître Pêcheur", "Pêcher des items enchantés plus fréquemment", 3, SkillEffect.PECHEUR_MAITRISE), 0.5f, 0.85f, "pech_tresor", "pech_sante");

        tree.addNode(appat);
        tree.addNode(rapide);
        tree.addNode(tresor);
        tree.addNode(sante);
        tree.addNode(maitrise);
    }
}
