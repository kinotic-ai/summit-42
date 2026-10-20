package org.kinotic.summit42.internal.api.services;

/**
 * Thrown when the platform's LLM configuration the app builder talks to Claude through is missing or holds no API
 * key; the message tells the user what to add.
 */
public class MissingLlmConfigurationException extends RuntimeException {

    public MissingLlmConfigurationException(String message) {
        super(message);
    }
}
