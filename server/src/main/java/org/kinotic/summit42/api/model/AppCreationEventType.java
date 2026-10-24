package org.kinotic.summit42.api.model;

/**
 * What an {@link AppCreationEvent} reports: a step of creating an app that has started, or the created app.
 */
public enum AppCreationEventType {

    /**
     * Claude is summarizing what the user wants the app to do into its one-line description.
     */
    DESCRIBING,

    /**
     * The app's Kinotic application is being created.
     */
    CREATING_APPLICATION,

    /**
     * The app's GitHub repository is being created and filled from Kinotic's app template.
     */
    CREATING_REPOSITORY,

    /**
     * The app is created and ready to chat about; the last event.
     */
    CREATED
}
