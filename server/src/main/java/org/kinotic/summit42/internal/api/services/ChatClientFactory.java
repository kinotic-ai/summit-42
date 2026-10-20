package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.kinotic.summit42.api.config.Summit42Properties;
import org.kinotic.summit42.internal.api.proxies.LlmConfigurationServiceProxy;
import org.kinotic.system.api.model.llm.LlmConfiguration;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * Builds the {@link ChatClient} the app builder talks to Claude through, from the platform's LLM configuration
 * named by {@link Summit42Properties#getLlmConfigurationName()}.
 */
@Component
@RequiredArgsConstructor
public class ChatClientFactory {

    // a turn writes whole files as tool arguments, which needs far more than a chat reply's default
    private static final int DEFAULT_MAX_TOKENS = 32_000;

    private final LlmConfigurationServiceProxy llmConfigurationService;
    private final Summit42Properties properties;

    private volatile CachedClient cached;

    /**
     * @return a client for the configuration as it is now; one built earlier is reused while the configuration is
     * unchanged
     */
    public Future<ChatClient> chatClient() {
        String name = properties.getLlmConfigurationName();
        return llmConfigurationService.findByName(name).compose(configuration -> {
            if (configuration == null) {
                throw new IllegalStateException("No LLM configuration is named '" + name
                                                + "'. Add it on the system console's LLM configurations page.");
            }
            Future<ChatClient> ret;
            CachedClient current = cached;
            if (current != null && current.matches(configuration)) {
                ret = Future.succeededFuture(current.client());
            } else {
                ret = llmConfigurationService.findApiKey(configuration.getId()).map(apiKey -> {
                    if (apiKey == null) {
                        throw new IllegalStateException("The LLM configuration '" + name + "' holds no API key");
                    }
                    ChatClient client = ChatClient.create(chatModel(configuration, apiKey));
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
                    .options(AnthropicChatOptions.builder()
                                     .apiKey(apiKey)
                                     .baseUrl(configuration.getEndpoint())
                                     .model(configuration.getModel())
                                     .maxTokens(maxTokens)
                                     .temperature(configuration.getTemperature())
                                     .build())
                    .build();
            case OPENAI -> OpenAiChatModel.builder()
                    .options(OpenAiChatOptions.builder()
                                     .apiKey(apiKey)
                                     .baseUrl(configuration.getEndpoint())
                                     .model(configuration.getModel())
                                     .maxCompletionTokens(maxTokens)
                                     .temperature(configuration.getTemperature())
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
