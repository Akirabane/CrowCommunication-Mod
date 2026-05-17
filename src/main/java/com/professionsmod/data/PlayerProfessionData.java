package com.professionsmod.data;

import com.professionsmod.profession.ProfessionType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.*;

public class PlayerProfessionData {

    private ProfessionType mainProfession = null;
    private ProfessionType secondaryProfession = null;

    private final Map<ProfessionType, Integer> levels = new EnumMap<>(ProfessionType.class);
    private final Map<ProfessionType, Integer> xp = new EnumMap<>(ProfessionType.class);
    private final Map<ProfessionType, Set<String>> unlockedSkills = new EnumMap<>(ProfessionType.class);
    private final Map<ProfessionType, Integer> skillPoints = new EnumMap<>(ProfessionType.class);

    public PlayerProfessionData() {
        for (ProfessionType type : ProfessionType.values()) {
            levels.put(type, 1);
            xp.put(type, 0);
            unlockedSkills.put(type, new HashSet<>());
            skillPoints.put(type, 0);
        }
    }

    public void addXP(ProfessionType type, int amount) {
        int currentXP = xp.get(type) + amount;
        int currentLevel = levels.get(type);

        while (currentLevel < ProfessionType.MAX_LEVEL) {
            int xpRequired = ProfessionType.XP_PER_LEVEL[currentLevel - 1];
            if (currentXP >= xpRequired) {
                currentXP -= xpRequired;
                currentLevel++;
                int currentPoints = skillPoints.getOrDefault(type, 0);
                skillPoints.put(type, currentPoints + 1);
            } else {
                break;
            }
        }

        if (currentLevel >= ProfessionType.MAX_LEVEL) {
            currentXP = 0;
        }

        xp.put(type, currentXP);
        levels.put(type, currentLevel);
    }

    public boolean unlockSkill(ProfessionType type, String skillId) {
        int points = skillPoints.getOrDefault(type, 0);
        if (points <= 0) return false;
        Set<String> skills = unlockedSkills.get(type);
        if (skills.contains(skillId)) return false;
        skills.add(skillId);
        skillPoints.put(type, points - 1);
        return true;
    }

    public boolean hasSkill(ProfessionType type, String skillId) {
        return unlockedSkills.getOrDefault(type, Collections.emptySet()).contains(skillId);
    }

    public boolean hasSpecialty(ProfessionType type) {
        return type.isMain() && levels.getOrDefault(type, 1) >= ProfessionType.MAX_LEVEL;
    }

    public int getLevel(ProfessionType type) { return levels.getOrDefault(type, 1); }
    public int getXP(ProfessionType type) { return xp.getOrDefault(type, 0); }
    public int getSkillPoints(ProfessionType type) { return skillPoints.getOrDefault(type, 0); }
    public Set<String> getUnlockedSkills(ProfessionType type) { return Collections.unmodifiableSet(unlockedSkills.getOrDefault(type, Collections.emptySet())); }

    public ProfessionType getMainProfession() { return mainProfession; }
    public ProfessionType getSecondaryProfession() { return secondaryProfession; }

    public boolean setMainProfession(ProfessionType type) {
        if (type != null && type.isSecondary()) return false;
        this.mainProfession = type;
        return true;
    }

    public boolean setSecondaryProfession(ProfessionType type) {
        if (type != null && type.isMain()) return false;
        this.secondaryProfession = type;
        return true;
    }

    public boolean isActiveProfession(ProfessionType type) {
        return type == mainProfession || type == secondaryProfession;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (mainProfession != null) tag.putString("mainProfession", mainProfession.name());
        if (secondaryProfession != null) tag.putString("secondaryProfession", secondaryProfession.name());

        CompoundTag levelsTag = new CompoundTag();
        CompoundTag xpTag = new CompoundTag();
        CompoundTag pointsTag = new CompoundTag();
        CompoundTag skillsTag = new CompoundTag();

        for (ProfessionType type : ProfessionType.values()) {
            levelsTag.putInt(type.name(), levels.getOrDefault(type, 1));
            xpTag.putInt(type.name(), xp.getOrDefault(type, 0));
            pointsTag.putInt(type.name(), skillPoints.getOrDefault(type, 0));

            ListTag list = new ListTag();
            for (String skill : unlockedSkills.getOrDefault(type, Collections.emptySet())) {
                list.add(StringTag.valueOf(skill));
            }
            skillsTag.put(type.name(), list);
        }

        tag.put("levels", levelsTag);
        tag.put("xp", xpTag);
        tag.put("skillPoints", pointsTag);
        tag.put("skills", skillsTag);
        return tag;
    }

    public void load(CompoundTag tag) {
        if (tag.contains("mainProfession")) {
            try { mainProfession = ProfessionType.valueOf(tag.getString("mainProfession")); } catch (Exception ignored) {}
        }
        if (tag.contains("secondaryProfession")) {
            try { secondaryProfession = ProfessionType.valueOf(tag.getString("secondaryProfession")); } catch (Exception ignored) {}
        }

        if (tag.contains("levels")) {
            CompoundTag levelsTag = tag.getCompound("levels");
            for (ProfessionType type : ProfessionType.values()) {
                if (levelsTag.contains(type.name())) levels.put(type, levelsTag.getInt(type.name()));
            }
        }
        if (tag.contains("xp")) {
            CompoundTag xpTag = tag.getCompound("xp");
            for (ProfessionType type : ProfessionType.values()) {
                if (xpTag.contains(type.name())) xp.put(type, xpTag.getInt(type.name()));
            }
        }
        if (tag.contains("skillPoints")) {
            CompoundTag pointsTag = tag.getCompound("skillPoints");
            for (ProfessionType type : ProfessionType.values()) {
                if (pointsTag.contains(type.name())) skillPoints.put(type, pointsTag.getInt(type.name()));
            }
        }
        if (tag.contains("skills")) {
            CompoundTag skillsTag = tag.getCompound("skills");
            for (ProfessionType type : ProfessionType.values()) {
                if (skillsTag.contains(type.name())) {
                    ListTag list = skillsTag.getList(type.name(), Tag.TAG_STRING);
                    Set<String> skills = new HashSet<>();
                    for (int i = 0; i < list.size(); i++) skills.add(list.getString(i));
                    unlockedSkills.put(type, skills);
                }
            }
        }
    }

    public void copyFrom(PlayerProfessionData other) {
        this.mainProfession = other.mainProfession;
        this.secondaryProfession = other.secondaryProfession;
        this.levels.putAll(other.levels);
        this.xp.putAll(other.xp);
        this.skillPoints.putAll(other.skillPoints);
        for (Map.Entry<ProfessionType, Set<String>> entry : other.unlockedSkills.entrySet()) {
            this.unlockedSkills.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
    }
}
