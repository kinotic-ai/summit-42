package org.kinotic.summit42.internal.api.proxies;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Proxy;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.domain.api.utils.DomainUtil;
import org.kinotic.system.api.model.llm.LlmConfiguration;

/**
 * The platform's LLM configurations, from the system server's {@code LlmConfigurationService}.
 */
@Proxy(namespace = "org.kinotic.system.api.services", name = "LlmConfigurationService")
@Zone(DomainUtil.SYSTEM_API_ZONE)
@Version("1.0.0")
public interface LlmConfigurationServiceProxy {

    /**
     * @return the configuration with the name, or null when there is none
     */
    Future<LlmConfiguration> findByName(String name);

    /**
     * @return the API key held for the configuration, or null when none is held
     */
    Future<String> findApiKey(String id);
}
