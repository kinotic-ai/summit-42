package org.kinotic.summit42.api.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An app of the organization the builder works on: a Kinotic application with the project its code lives in.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BuilderApp {

    /**
     * The Kinotic application's id, its slugified name.
     */
    private String applicationId;

    private String name;

    /**
     * What the app is for, or null when the user gave no description.
     */
    private String description;

    /**
     * The URL the app's UI is served at, or null until the app has been deployed.
     */
    private String url;
}
