package org.kinotic.summit42.internal.api.services;

import lombok.Getter;
import org.kinotic.domain.api.model.EmailTemplate;
import org.kinotic.summit42.api.config.Summit42Properties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Summit 42's invitation emails, which Kinotic sends in place of its own, with links to
 * Summit 42's UI at {@link Summit42Properties#getUiBaseUrl()}.
 */
@Getter
@Component
public class Summit42EmailTemplates {

    private static final String TEMPLATES = "email/";

    /**
     * The invitation into the user's organization or one of its apps.
     */
    private final EmailTemplate invitation;

    public Summit42EmailTemplates(Summit42Properties properties) {
        this.invitation = load("invitation", properties.getUiBaseUrl());
    }

    private static EmailTemplate load(String name, String uiBaseUrl) {
        return new EmailTemplate().setUiBaseUrl(uiBaseUrl)
                                  .setSubject(read(name, "subject.txt").strip())
                                  .setHtmlBody(read(name, "body.html"))
                                  .setTextBody(read(name, "body.txt"));
    }

    private static String read(String name, String file) {
        String path = TEMPLATES + name + "/" + file;
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read " + path, e);
        }
    }
}
