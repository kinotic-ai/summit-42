package org.kinotic.summit42.internal.api.services;

import org.kinotic.domain.api.model.security.participant.OrganizationParticipant;
import org.kinotic.summit42.api.model.BuildEvent;
import org.kinotic.summit42.internal.api.repositories.BuilderSession;

import java.util.function.Consumer;

/**
 * One chat turn, as the app builder's tools see it: the signed-in user it acts for, their organization's session,
 * and where the tools report progress.
 *
 * @param participant the participant of the chat call, authenticated by the management gateway
 * @param session     the organization's session
 * @param events      receives the progress events the turn streams
 */
public record BuilderTurn(OrganizationParticipant participant, BuilderSession session, Consumer<BuildEvent> events) {
}
