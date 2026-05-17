package com.professionsmod.skill;

import java.util.*;

public class SkillTree {

    private final Map<String, SkillNode> nodes = new LinkedHashMap<>();

    public void addNode(SkillNode node) {
        nodes.put(node.getId(), node);
    }

    public Map<String, SkillNode> getNodes() { return Collections.unmodifiableMap(nodes); }

    public SkillNode getNode(String id) { return nodes.get(id); }

    public boolean canUnlock(String skillId, Set<String> unlockedSkills) {
        SkillNode node = nodes.get(skillId);
        if (node == null || unlockedSkills.contains(skillId)) return false;
        return unlockedSkills.containsAll(node.getPrerequisites());
    }

    public List<SkillNode> getAllNodes() {
        return new ArrayList<>(nodes.values());
    }
}
