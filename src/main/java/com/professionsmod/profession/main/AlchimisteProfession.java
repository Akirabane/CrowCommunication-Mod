package com.professionsmod.profession.main;

import com.professionsmod.profession.Profession;
import com.professionsmod.profession.ProfessionType;
import com.professionsmod.skill.Skill;
import com.professionsmod.skill.Skill.SkillEffect;
import com.professionsmod.skill.SkillNode;
import com.professionsmod.skill.SkillTree;

public class AlchimisteProfession extends Profession {

    public AlchimisteProfession() {
        super(ProfessionType.ALCHIMISTE);
    }

    @Override
    protected void buildSkillTree(SkillTree tree) {
        SkillNode dosage = new SkillNode(new Skill("alchi_dosage", "Dosage Précis", "Les potions durent 25% plus longtemps", 1, SkillEffect.DOSAGE_PRECIS), 0.5f, 0.1f);
        SkillNode duree = new SkillNode(new Skill("alchi_duree", "Durée Prolongée", "+1 niveau effectif à toutes les potions", 1, SkillEffect.DUREE_PROLONGEE), 0.2f, 0.2f);
        SkillNode puissance = new SkillNode(new Skill("alchi_puissance", "Puissance Augmentée", "+30% d'efficacité des potions de soin", 1, SkillEffect.PUISSANCE_AUGMENTEE), 0.8f, 0.2f);

        SkillNode poison = new SkillNode(new Skill("alchi_poison", "Résistance aux Poisons", "Immunité au poison et nausée", 2, SkillEffect.RESISTANCE_POISONS), 0.2f, 0.45f, "alchi_duree");
        SkillNode avancee = new SkillNode(new Skill("alchi_avancee", "Alchimie Avancée", "Brasser sans netherverrue (recettes de base)", 2, SkillEffect.ALCHIMIE_AVANCEE), 0.5f, 0.4f, "alchi_dosage");
        SkillNode transmutation = new SkillNode(new Skill("alchi_transmutation", "Transmutation", "Convertir des potions inutiles en XP", 2, SkillEffect.TRANSMUTATION), 0.8f, 0.45f, "alchi_puissance");

        SkillNode distillation = new SkillNode(new Skill("alchi_distillation", "Distillation Pure", "Réutiliser les flacons sans perte", 3, SkillEffect.DISTILLATION), 0.25f, 0.7f, "alchi_avancee", "alchi_poison");
        SkillNode catalyseur = new SkillNode(new Skill("alchi_catalyseur", "Catalyseur", "Brasser 2x plus vite", 3, SkillEffect.CATALYSEUR), 0.5f, 0.75f, "alchi_avancee");
        SkillNode maitrise = new SkillNode(new Skill("alchi_maitrise", "Grand Maître", "Toutes les potions ont +50% de durée", 3, SkillEffect.MAITRISE_ALCHIMIE), 0.75f, 0.7f, "alchi_transmutation", "alchi_avancee");

        tree.addNode(dosage);
        tree.addNode(duree);
        tree.addNode(puissance);
        tree.addNode(poison);
        tree.addNode(avancee);
        tree.addNode(transmutation);
        tree.addNode(distillation);
        tree.addNode(catalyseur);
        tree.addNode(maitrise);
    }
}
