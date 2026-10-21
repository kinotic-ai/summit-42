package org.kinotic.summit42.fakes;

import io.vertx.core.Future;
import org.kinotic.core.api.crud.Page;
import org.kinotic.core.api.crud.Pageable;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.management.api.model.Project;
import org.kinotic.management.api.model.RepositoryConnectionStatus;
import org.kinotic.management.api.model.deployment.ProjectDeployment;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds each organization's projects and their deployments in memory. A project is created with the repository
 * status set for its organization by {@link #createWith}, {@link RepositoryConnectionStatus#CONNECTED} by default.
 */
public class FakeProjectService extends FakeOrganizationService implements PublishedProjectService {

    private final Map<String, List<Project>> projects = new ConcurrentHashMap<>();
    private final Map<String, ProjectDeployment> deployments = new ConcurrentHashMap<>();
    private final Map<String, RepositoryConnectionStatus> createdStatuses = new ConcurrentHashMap<>();
    private final Set<String> failingCreations = ConcurrentHashMap.newKeySet();
    private final Set<String> failingRetries = ConcurrentHashMap.newKeySet();

    public FakeProjectService(SecurityContext securityContext) {
        super(securityContext);
    }

    @Override
    public Future<Project> createSync(Project project) {
        String organizationId = organizationId();
        Future<Project> ret;
        if (failingCreations.contains(organizationId)) {
            ret = Future.failedFuture(new IllegalStateException("The repository could not be created"));
        } else {
            project.setId(UUID.randomUUID().toString());
            project.setRepoConnectionStatus(createdStatuses.getOrDefault(organizationId, RepositoryConnectionStatus.CONNECTED));
            projectsOf(organizationId).add(project);
            ret = Future.succeededFuture(project);
        }
        return ret;
    }

    @Override
    public Future<Page<Project>> findAll(Pageable pageable) {
        return Future.succeededFuture(page(projectsOf(organizationId()), pageable));
    }

    @Override
    public Future<Page<Project>> findAllForApplication(String applicationId, Pageable pageable) {
        List<Project> found = projectsOf(organizationId()).stream()
                                                          .filter(project -> applicationId.equals(project.getApplicationId()))
                                                          .toList();
        return Future.succeededFuture(page(found, pageable));
    }

    @Override
    public Future<Project> retryRepoInitialization(String projectId) {
        String organizationId = organizationId();
        Project project = projectsOf(organizationId).stream()
                                                    .filter(candidate -> projectId.equals(candidate.getId()))
                                                    .findFirst()
                                                    .orElseThrow();
        Future<Project> ret;
        if (failingRetries.contains(organizationId)) {
            ret = Future.failedFuture(new IllegalStateException("The template could not be rendered"));
        } else {
            project.setRepoConnectionStatus(RepositoryConnectionStatus.CONNECTED);
            ret = Future.succeededFuture(project);
        }
        return ret;
    }

    @Override
    public Future<ProjectDeployment> findDeployment(String projectId) {
        // refuses a caller that is not an organization participant, as the management server does
        organizationId();
        return Future.succeededFuture(deployments.get(projectId));
    }

    /**
     * Adds a project of the application to the organization, as one created earlier.
     */
    public Project add(String organizationId, String applicationId, RepositoryConnectionStatus status) {
        Project ret = new Project();
        ret.setId(UUID.randomUUID().toString());
        ret.setOrganizationId(organizationId);
        ret.setApplicationId(applicationId);
        ret.setName(applicationId);
        ret.setRepoConnectionStatus(status);
        projectsOf(organizationId).add(ret);
        return ret;
    }

    public void setDeployment(String projectId, ProjectDeployment deployment) {
        deployments.put(projectId, deployment);
    }

    /**
     * Sets the repository status the organization's next projects are created with.
     */
    public void createWith(String organizationId, RepositoryConnectionStatus status) {
        createdStatuses.put(organizationId, status);
    }

    /**
     * Fails the creation of the organization's projects.
     */
    public void failCreations(String organizationId) {
        failingCreations.add(organizationId);
    }

    /**
     * Fails the organization's retries of a repository's initialization.
     */
    public void failRetries(String organizationId) {
        failingRetries.add(organizationId);
    }

    /**
     * @return the organization's projects in the order they were created
     */
    public List<Project> projectsOf(String organizationId) {
        return projects.computeIfAbsent(organizationId, key -> new CopyOnWriteArrayList<>());
    }
}
