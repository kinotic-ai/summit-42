package org.kinotic.summit42.fakes;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.exceptions.AuthorizationException;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.system.api.model.llm.LlmConfiguration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds the system's LLM configurations and their API keys in memory. Like the real service, it refuses a key to a
 * call made for a participant.
 */
@RequiredArgsConstructor
public class FakeLlmConfigurationService implements PublishedLlmConfigurationService {

    private final SecurityContext securityContext;
    private final Map<String, LlmConfiguration> configurations = new ConcurrentHashMap<>();
    private final Map<String, String> apiKeys = new ConcurrentHashMap<>();
    private final AtomicInteger apiKeyReads = new AtomicInteger();

    @Override
    public Future<LlmConfiguration> findByName(String name) {
        return Future.succeededFuture(configurations.get(name));
    }

    @Override
    public Future<String> findApiKey(String id) {
        if (securityContext.currentParticipant() != null) {
            throw new AuthorizationException("Access denied");
        }
        apiKeyReads.incrementAndGet();
        return Future.succeededFuture(apiKeys.get(id));
    }

    /**
     * Saves the configuration under its name, holding the API key for it, or none when the key is null.
     */
    public void save(LlmConfiguration configuration, String apiKey) {
        configurations.put(configuration.getName(), configuration);
        if (apiKey == null) {
            apiKeys.remove(configuration.getId());
        } else {
            apiKeys.put(configuration.getId(), apiKey);
        }
    }

    public void delete(String name) {
        configurations.remove(name);
    }

    /**
     * @return how many times an API key has been read
     */
    public int apiKeyReads() {
        return apiKeyReads.get();
    }
}
