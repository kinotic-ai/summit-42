package org.kinotic.summit42.fakes;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Publish;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.domain.api.utils.DomainUtil;
import org.kinotic.system.api.model.llm.LlmConfiguration;

/**
 * Publishes {@link FakeLlmConfigurationService} under the system server's {@code LlmConfigurationService}, with the
 * functions Summit 42 calls.
 */
@Publish(namespace = "org.kinotic.system.api.services", name = "LlmConfigurationService")
@Zone(DomainUtil.SYSTEM_API_ZONE)
@Version("1.0.0")
public interface PublishedLlmConfigurationService {

    Future<LlmConfiguration> findByName(String name);

    Future<String> findApiKey(String id);
}
