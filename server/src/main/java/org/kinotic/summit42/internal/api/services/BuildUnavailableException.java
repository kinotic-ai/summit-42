package org.kinotic.summit42.internal.api.services;

/**
 * Thrown when the builder cannot work on an app because something it needs is missing or unusable, such as the
 * system's LLM configuration or the app's GitHub repository; the message tells the user what is wrong.
 */
public class BuildUnavailableException extends RuntimeException {

    public BuildUnavailableException(String message) {
        super(message);
    }
}
