package org.kinotic.summit42.api.model;

/**
 * What a {@link BuildEvent} reports.
 */
public enum BuildEventType {

    /**
     * A chunk of Claude's reply, to append to what came before it.
     */
    TEXT,

    /**
     * A one-line status of what the builder is doing, such as committing files or waiting on a deployment.
     */
    PROGRESS,

    /**
     * The app is deployed; the event's URL opens it.
     */
    APP_READY,

    /**
     * A message the user sent to steer the running turn, which Claude reads from this point of the turn on; the
     * event's text is the message.
     */
    USER_MESSAGE,

    /**
     * The turn failed; the event's text says why.
     */
    ERROR
}
