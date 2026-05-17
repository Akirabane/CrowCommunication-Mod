package com.professionsmod.profession;

import com.professionsmod.skill.SkillTree;

public abstract class Profession {

    private final ProfessionType type;
    private final SkillTree skillTree;

    public Profession(ProfessionType type) {
        this.type = type;
        this.skillTree = new SkillTree();
        buildSkillTree(this.skillTree);
    }

    protected abstract void buildSkillTree(SkillTree tree);

    public ProfessionType getType() { return type; }
    public SkillTree getSkillTree() { return skillTree; }
    public String getName() { return type.getDisplayName(); }

    public int getXpForLevel(int level) {
        if (level <= 0 || level > ProfessionType.MAX_LEVEL) return 0;
        return ProfessionType.XP_PER_LEVEL[level - 1];
    }

    public int getTotalXpForLevel(int targetLevel) {
        int total = 0;
        for (int i = 0; i < targetLevel - 1; i++) {
            total += ProfessionType.XP_PER_LEVEL[i];
        }
        return total;
    }
}
