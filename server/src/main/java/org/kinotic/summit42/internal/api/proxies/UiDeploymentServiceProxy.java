package org.kinotic.summit42.internal.api.proxies;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Proxy;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.domain.api.utils.DomainUtil;
import org.kinotic.management.api.model.deployment.UiDeployment;

import java.util.List;

/**
 * The management server's {@code UiDeploymentService}, acting for the organization of the participant the call is
 * made as.
 */
@Proxy(namespace = "org.kinotic.management.api.services.deployment", name = "UiDeploymentService")
@Zone(DomainUtil.MANAGEMENT_API_ZONE)
@Version("1.0.0")
public interface UiDeploymentServiceProxy {

    /**
     * @return the deployments of the UIs of the project, ordered by UI name
     */
    Future<List<UiDeployment>> findAllForProject(String projectId);

    /**
     * @return the deployments of the UIs of all the application's projects, ordered by UI name
     */
    Future<List<UiDeployment>> findAllForApplication(String applicationId);
}
