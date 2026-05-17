package com.professionsmod.skill;

import java.util.ArrayList;
import java.util.List;

public class SkillNode {

    private final Skill skill;
    private final float posX;
    private final float posY;
    private final List<String> prerequisites;

    public SkillNode(Skill skill, float posX, float posY, String... prerequisites) {
        this.skill = skill;
        this.posX = posX;
        this.posY = posY;
        this.prerequisites = new ArrayList<>();
        for (String prereq : prerequisites) {
            this.prerequisites.add(prereq);
        }
    }

    public Skill getSkill() { return skill; }
    public float getPosX() { return posX; }
    public float getPosY() { return posY; }
    public List<String> getPrerequisites() { return prerequisites; }
    public String getId() { return skill.getId(); }
}
