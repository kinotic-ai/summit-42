package org.kinotic.summit42.api.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Summit 42's settings, bound under {@code summit42.*}.
 */
@Getter
@Setter
@Accessors(chain = true)
@NoArgsConstructor
@Validated
@ConfigurationProperties(prefix = "summit42")
public class Summit42Properties {

    /**
     * The name of the platform's LLM configuration the app builder calls, managed on the system console's
     * LLM configurations page.
     */
    @NotBlank
    private String llmConfigurationName = "summit-42";
}
