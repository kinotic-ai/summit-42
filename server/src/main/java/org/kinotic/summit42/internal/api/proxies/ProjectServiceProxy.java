package org.kinotic.summit42.internal.api.proxies;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Proxy;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.core.api.crud.Page;
import org.kinotic.core.api.crud.Pageable;
import org.kinotic.domain.api.utils.DomainUtil;
import org.kinotic.management.api.model.Project;
import org.kinotic.management.api.model.deployment.ProjectDeployment;

/**
 * The management server's {@code ProjectService}, acting for the organization of the participant the call is made
 * as.
 */
@Proxy(namespace = "org.kinotic.management.api.services", name = "ProjectService")
@Zone(DomainUtil.MANAGEMENT_API_ZONE)
@Version("1.0.0")
public interface ProjectServiceProxy {

    /**
     * Creates the project and its GitHub repository, generated from the platform's app template.
     */
    Future<Project> createSync(Project project);

    Future<Project> findById(String id);

    Future<Page<Project>> findAllForApplication(String applicationId, Pageable pageable);

    /**
     * @return the project's deployment, or null before its first deployment
     */
    Future<ProjectDeployment> findDeployment(String projectId);
}
