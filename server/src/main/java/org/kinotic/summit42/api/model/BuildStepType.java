package org.kinotic.summit42.api.model;

/**
 * What a {@link BuildEventType#PROGRESS} step of the builder does.
 */
public enum BuildStepType {

    /**
     * Reading the app's files, a skill or Kinotic's docs.
     */
    READING,

    /**
     * Committing the app's changed files.
     */
    COMMITTING,

    /**
     * Waiting on the deployment of the last commit, through each of its phases.
     */
    DEPLOYING
}
