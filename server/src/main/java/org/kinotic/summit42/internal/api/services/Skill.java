package org.kinotic.summit42.internal.api.services;

import java.util.SortedMap;

/**
 * A skill of Kinotic's Claude plugin, which teaches Claude how to build one part of a Kinotic app.
 *
 * @param name        the skill's name, such as frontend
 * @param description what the skill covers and when to read it
 * @param files       the skill's files by their path in the skill: {@link #SKILL_FILE}, without its frontmatter, and
 *                    the references it points to, such as references/decorators.md
 */
public record Skill(String name, String description, SortedMap<String, String> files) {

    /**
     * The path of a skill's main file.
     */
    public static final String SKILL_FILE = "SKILL.md";
}
