package org.kinotic.summit42.api.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One event of creating an app: the step that has started, or the created app.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AppCreationEvent {

    private AppCreationEventType type;

    /**
     * The created app for {@link AppCreationEventType#CREATED}, otherwise null.
     */
    private BuilderApp app;

    public static AppCreationEvent step(AppCreationEventType type) {
        return new AppCreationEvent(type, null);
    }

    public static AppCreationEvent created(BuilderApp app) {
        return new AppCreationEvent(AppCreationEventType.CREATED, app);
    }
}
