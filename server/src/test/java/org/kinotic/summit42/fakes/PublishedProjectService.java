package org.kinotic.summit42.fakes;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Publish;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.core.api.crud.Page;
import org.kinotic.core.api.crud.Pageable;
import org.kinotic.domain.api.utils.DomainUtil;
import org.kinotic.management.api.model.Project;
import org.kinotic.management.api.model.deployment.ProjectDeployment;

/**
 * Publishes {@link FakeProjectService} under the management server's {@code ProjectService}, with the functions
 * Summit 42 calls.
 */
@Publish(namespace = "org.kinotic.management.api.services", name = "ProjectService")
@Zone(DomainUtil.MANAGEMENT_API_ZONE)
@Version("1.0.0")
public interface PublishedProjectService {

    Future<Project> createSync(Project project);

    Future<Page<Project>> findAll(Pageable pageable);

    Future<Page<Project>> findAllForApplication(String applicationId, Pageable pageable);

    Future<Project> retryRepoInitialization(String projectId);

    Future<ProjectDeployment> findDeployment(String projectId);
}
