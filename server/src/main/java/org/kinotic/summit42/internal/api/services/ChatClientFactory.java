package org.kinotic.summit42.internal.api.services;

import lombok.RequiredArgsConstructor;
import io.micrometer.observation.ObservationRegistry;
import okhttp3.Interceptor;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.summit42.api.config.Summit42Properties;
import org.kinotic.system.api.model.llm.LlmConfiguration;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Builds the {@link ChatClient} the app builder talks to Claude through, from the platform's LLM configuration
 * named by {@link Summit42Properties#getLlmConfigurationName()}. Between the steps of a chat turn, the client gives
 * Claude the messages the user sent to steer the turn.
 */
@Component
@RequiredArgsConstructor
public class ChatClientFactory {

    // a turn writes whole files as tool arguments, which needs far more than a chat reply's default
    private static final int DEFAULT_MAX_TOKENS = 32_000;
    // the providers' clients end a call at 60 seconds by default, yet a response writing whole files streams for many
    // minutes; a zero timeout lets the call run until the provider ends it
    private static final Duration NO_TIMEOUT = Duration.ZERO;
    // a response streams its tokens as they are generated, so one that sends nothing for this long has lost its
    // connection or its server, and its call fails rather than holding the turn open
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(5);
    // set on each call's chain, since the providers' clients apply NO_TIMEOUT as the read timeout of every call
    private static final Interceptor IDLE_TIMEOUT_INTERCEPTOR =
            chain -> chain.withReadTimeout((int) IDLE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).proceed(chain.request());

    private final LlmConfigurationServiceProxy llmConfigurationService;
    private final SecurityContext securityContext;
    private final Summit42Properties properties;
    private final SteeringToolCallingManager toolCallingManager;

    private volatile CachedClient cached;

    /**
     * Reads the configuration and its API key as this cluster member itself, since the LLM configuration service
     * refuses a key to a call made for a participant.
     *
     * @return a client for the configuration as it is now; one built earlier is reused while the configuration is
     * unchanged. Fails with {@link BuildUnavailableException} when there is no such configuration or it
     * holds no API key
     */
    public CompletionStage<ChatClient> chatClient() {
        String name = properties.getLlmConfigurationName();
        // callWithoutParticipant covers only proxy calls its supplier starts, so each call gets its own
        return securityContext.callWithoutParticipant(() -> llmConfigurationService.findByName(name).toCompletionStage())
                .thenCompose(configuration -> {
                    if (configuration == null) {
                        throw new BuildUnavailableException("No LLM configuration is named '" + name
                                + "'. Add it on the system console's LLM configurations page.");
                    }
                    CompletionStage<ChatClient> ret;
                    CachedClient current = cached;
                    if (current != null && current.matches(configuration)) {
                        ret = CompletableFuture.completedFuture(current.client());
                    } else {
                        ret = securityContext.callWithoutParticipant(
                                        () -> llmConfigurationService.findApiKey(configuration.getId()).toCompletionStage())
                                .thenApply(apiKey -> {
                                    if (apiKey == null) {
                                        throw new BuildUnavailableException("The LLM configuration '" + name
                                                                                   + "' holds no API key");
                                    }
                                    ChatClient client = ChatClient.builder(chatModel(configuration, apiKey),
                                                                           ObservationRegistry.NOOP, null, null,
                                                                           ToolCallingAdvisor.builder()
                                                                                             .toolCallingManager(toolCallingManager))
                                                                  .build();
                                    cached = new CachedClient(configuration.getId(), configuration.getUpdated(), client);
                                    return client;
                                });
                    }
                    return ret;
                });
    }

    private static ChatModel chatModel(LlmConfiguration configuration, String apiKey) {
        int maxTokens = configuration.getMaxTokens() != null ? configuration.getMaxTokens() : DEFAULT_MAX_TOKENS;
        return switch (configuration.getProvider()) {
            case ANTHROPIC -> AnthropicChatModel.builder()
                    .httpClientBuilderCustomizer(http -> http.interceptor(IDLE_TIMEOUT_INTERCEPTOR))
                    .options(AnthropicChatOptions.builder()
                                     .apiKey(apiKey)
                                     .baseUrl(configuration.getEndpoint())
                                     .model(configuration.getModel())
                                     .maxTokens(maxTokens)
                                     .temperature(configuration.getTemperature())
                                     .timeout(NO_TIMEOUT)
                                     .build())
                    .build();
            case OPENAI -> OpenAiChatModel.builder()
                    .httpClientBuilderCustomizer(http -> http.interceptor(IDLE_TIMEOUT_INTERCEPTOR))
                    .options(OpenAiChatOptions.builder()
                                     .apiKey(apiKey)
                                     .baseUrl(configuration.getEndpoint())
                                     .model(configuration.getModel())
                                     .maxCompletionTokens(maxTokens)
                                     .temperature(configuration.getTemperature())
                                     .timeout(NO_TIMEOUT)
                                     .build())
                    .build();
        };
    }

    private record CachedClient(String configurationId, Date updated, ChatClient client) {

        boolean matches(LlmConfiguration configuration) {
            return configurationId.equals(configuration.getId())
                   && updated != null && updated.equals(configuration.getUpdated());
        }
    }
}
