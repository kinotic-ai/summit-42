package org.kinotic.summit42.fakes;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Publish;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.domain.api.utils.DomainUtil;
import org.kinotic.management.api.model.deployment.UiDeployment;

import java.util.List;

/**
 * Publishes {@link FakeUiDeploymentService} under the management server's {@code UiDeploymentService}, with the
 * functions Summit 42 calls.
 */
@Publish(namespace = "org.kinotic.management.api.services.deployment", name = "UiDeploymentService")
@Zone(DomainUtil.MANAGEMENT_API_ZONE)
@Version("1.0.0")
public interface PublishedUiDeploymentService {

    Future<List<UiDeployment>> findAllForProject(String projectId);

    Future<List<UiDeployment>> findAllForApplication(String applicationId);
}
