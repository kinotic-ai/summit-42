package org.kinotic.summit42.internal.api.services;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.SortedMap;
import java.util.stream.Collectors;

/**
 * The skills of one revision of Kinotic's agent plugin.
 *
 * @param version the plugin's commit the skills were read at
 * @param skills  the skills by name
 */
public record SkillSet(String version, SortedMap<String, Skill> skills) {

    /**
     * @return one line per skill, naming it and saying what it covers
     */
    public String catalog() {
        return skills.values().stream()
                     .map(skill -> "- " + skill.name() + ": " + skill.description())
                     .collect(Collectors.joining("\n"));
    }

    /**
     * Reads a file of a skill.
     *
     * @param skillName the skill's name
     * @param file      the file's path in the skill, or null for {@link Skill#SKILL_FILE}
     * @return the file's content followed by the paths of the skill's other files, or the skills or the skill's files
     * there are when there is no such skill or file
     */
    public String read(String skillName, String file) {
        Skill skill = skills.get(skillName);
        String path = StringUtils.isBlank(file) ? Skill.SKILL_FILE : Strings.CS.removeStart(file.strip(), "/");
        String ret;
        if (skill == null) {
            ret = "Unknown skill. The skills are: " + String.join(", ", skills.keySet());
        } else if (!skill.files().containsKey(path)) {
            ret = "The skill " + skillName + " has no file " + path + ". Its files are:\n"
                  + String.join("\n", skill.files().keySet());
        } else {
            String others = skill.files().keySet().stream()
                                 .filter(other -> !other.equals(path))
                                 .collect(Collectors.joining("\n"));
            ret = skill.files().get(path)
                  + (others.isEmpty() ? "" : "\n\nThe skill's other files, which readSkill reads:\n" + others);
        }
        return ret;
    }
}
