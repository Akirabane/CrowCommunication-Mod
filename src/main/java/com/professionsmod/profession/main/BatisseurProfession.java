package com.professionsmod.profession.main;

import com.professionsmod.profession.Profession;
import com.professionsmod.profession.ProfessionType;
import com.professionsmod.skill.Skill;
import com.professionsmod.skill.Skill.SkillEffect;
import com.professionsmod.skill.SkillNode;
import com.professionsmod.skill.SkillTree;

public class BatisseurProfession extends Profession {

    public BatisseurProfession() {
        super(ProfessionType.BATISSEUR);
    }

    @Override
    protected void buildSkillTree(SkillTree tree) {
        SkillNode rapide = new SkillNode(new Skill("bat_rapide", "Placement Rapide", "Poser des blocs 30% plus vite", 1, SkillEffect.PLACEMENT_RAPIDE), 0.5f, 0.1f);
        SkillNode portee = new SkillNode(new Skill("bat_portee", "Portée Étendue", "+1 bloc de portée pour placer", 1, SkillEffect.PORTEE_ETENDUE), 0.2f, 0.2f);
        SkillNode economie = new SkillNode(new Skill("bat_economie", "Économie de Matériaux", "10% de chance de ne pas consommer le bloc", 1, SkillEffect.ECONOMIE_MATERIAUX), 0.8f, 0.2f);

        SkillNode vision = new SkillNode(new Skill("bat_vision", "Vision Architecturale", "Voir une grille de construction (overlay)", 2, SkillEffect.VISION_ARCHITECTURALE), 0.2f, 0.45f, "bat_portee");
        SkillNode stabilite = new SkillNode(new Skill("bat_stabilite", "Stabilité", "Construire en l'air sans chuter", 2, SkillEffect.STABILITE), 0.5f, 0.4f, "bat_rapide");
        SkillNode esthetique = new SkillNode(new Skill("bat_esthetique", "Esthétique", "Identifier automatiquement le meilleur bloc voisin", 2, SkillEffect.ESTHETIQUE), 0.8f, 0.45f, "bat_economie");

        SkillNode massive = new SkillNode(new Skill("bat_massive", "Construction Massive", "Placer 5 blocs d'un coup en ligne", 3, SkillEffect.CONSTRUCTION_MASSIVE), 0.25f, 0.7f, "bat_stabilite", "bat_vision");
        SkillNode ingenierie = new SkillNode(new Skill("bat_ingenierie", "Ingénierie", "Les pistons et mécanismes ont +2 de portée", 3, SkillEffect.INGENIERIE), 0.5f, 0.75f, "bat_stabilite");
        SkillNode maitrise = new SkillNode(new Skill("bat_maitrise", "Maîtrise du Bâtisseur", "+20% de résistance des structures construites", 3, SkillEffect.MAITRISE_BATISSEUR), 0.75f, 0.7f, "bat_esthetique", "bat_stabilite");

        tree.addNode(rapide);
        tree.addNode(portee);
        tree.addNode(economie);
        tree.addNode(vision);
        tree.addNode(stabilite);
        tree.addNode(esthetique);
        tree.addNode(massive);
        tree.addNode(ingenierie);
        tree.addNode(maitrise);
    }
}
