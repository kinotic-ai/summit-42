package org.kinotic.summit42.api.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * An organization's chat with the app builder.
 */
@Getter
@Setter
@Accessors(chain = true)
@NoArgsConstructor
public class Conversation {

    /**
     * The messages in the order they were written.
     */
    private List<ChatMessage> messages = new ArrayList<>();

    /**
     * The URL of the organization's deployed app, or null until one is deployed.
     */
    private String appUrl;
}
