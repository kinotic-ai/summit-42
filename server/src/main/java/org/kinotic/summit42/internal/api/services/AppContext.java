package org.kinotic.summit42.internal.api.services;

import org.kinotic.management.api.model.Project;

/**
 * What a chat turn knows about its app: the project it is built in and the revision of Kinotic's skills the turn
 * reads, each read once as the turn starts.
 */
public record AppContext(Project project, SkillSet skills) {
}
