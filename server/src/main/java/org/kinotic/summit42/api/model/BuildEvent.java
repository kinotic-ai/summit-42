package org.kinotic.summit42.api.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One event of a chat turn.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BuildEvent {

    private BuildEventType type;

    /**
     * The reply chunk, status line or error message, or null for {@link BuildEventType#APP_READY}.
     */
    private String text;

    /**
     * The deployed app's URL for {@link BuildEventType#APP_READY}, otherwise null.
     */
    private String url;

    /**
     * What the step does for {@link BuildEventType#PROGRESS}, otherwise null.
     */
    private BuildStepType stepType;

    public static BuildEvent text(String text) {
        return new BuildEvent(BuildEventType.TEXT, text, null, null);
    }

    public static BuildEvent progress(BuildStepType stepType, String text) {
        return new BuildEvent(BuildEventType.PROGRESS, text, null, stepType);
    }

    public static BuildEvent appReady(String url) {
        return new BuildEvent(BuildEventType.APP_READY, null, url, null);
    }

    public static BuildEvent error(String text) {
        return new BuildEvent(BuildEventType.ERROR, text, null, null);
    }
}
