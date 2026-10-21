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
     * Creates the project and its GitHub repository, generated from the platform's app template. The project is
     * marked {@link org.kinotic.management.api.model.RepositoryConnectionStatus#INITIALIZATION_FAILED} when the
     * repository was created but the template could not be rendered into it.
     */
    Future<Project> createSync(Project project);

    Future<Page<Project>> findAll(Pageable pageable);

    Future<Page<Project>> findAllForApplication(String applicationId, Pageable pageable);

    /**
     * Renders the template into the repository of a project marked
     * {@link org.kinotic.management.api.model.RepositoryConnectionStatus#INITIALIZATION_FAILED}.
     *
     * @return the project, marked {@link org.kinotic.management.api.model.RepositoryConnectionStatus#CONNECTED};
     * fails when the rendering fails again
     */
    Future<Project> retryRepoInitialization(String projectId);

    /**
     * @return the project's deployment, or null before its first deployment
     */
    Future<ProjectDeployment> findDeployment(String projectId);
}
